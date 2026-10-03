#!/usr/bin/env node
// Fails when a skill or agent names an MCP tool the jfr-mcp server does not expose.
//
// Skills and agents in the plugins that use jfr-mcp name its tools explicitly, and the server is
// developed in another repository (btraceio/jafar). Nothing in either repository's tests connects
// the two, so a tool rename there turns a skill here into confident instructions for a call that
// fails. This script is the connection.
//
// Ground truth is the server itself: started, handshaken, and asked `tools/list`. Parsing its Java
// would encode assumptions about how tools are registered today, and would miss a tool that fails
// to register at runtime. Asking the server is exactly what an MCP client does.
//
// Usage:
//   node scripts/check-tool-references.js                     # jbang jfr-mcp@btraceio --stdio
//   node scripts/check-tool-references.js --jar path/to.jar   # a locally built shadow jar
//   node scripts/check-tool-references.js --command "..."     # any command speaking MCP on stdio
//
// Needs no attach/daemon: it runs the server as a plain stdio process and stops it afterwards.
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const root = path.resolve(__dirname, '..');

// The four namespaces the server's tools live in. A token with one of these prefixes is a tool
// reference; anything else in the prose is not our business.
const TOOL_TOKEN = /\b(?:jfr|hdump|pprof|otlp)_[a-z_]+\b/g;
// Names that look like tools but are not: the field names of the "shared evidence record" in the
// jfr-analyzer interop skill. They are data the agent writes, not calls it makes.
const NOT_TOOLS = new Set(['jfr_session', 'jfr_window', 'jfr_evidence']);
// Agents declare their allowlist as `mcp__<server>__<tool>`.
const MCP_QUALIFIED = /\bmcp__[a-z0-9_]+__([a-z0-9_]+)\b/g;

const HANDSHAKE = [
  { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'tool-drift-check', version: '1' } } },
  { jsonrpc: '2.0', method: 'notifications/initialized' },
  { jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} },
];

function parseArgs(argv) {
  const args = { command: 'jbang jfr-mcp@btraceio --stdio', timeout: 180 };
  for (let i = 2; i < argv.length; i++) {
    if (argv[i] === '--jar') args.command = `java -jar ${argv[++i]} --stdio`;
    else if (argv[i] === '--command') args.command = argv[++i];
    else if (argv[i] === '--timeout') args.timeout = Number(argv[++i]);
    else throw new Error(`unknown argument: ${argv[i]}`);
  }
  return args;
}

// Plugins that start jfr-mcp, found from their own .mcp.json rather than a hard-coded list.
function jfrMcpPlugins() {
  const found = [];
  for (const entry of fs.readdirSync(path.join(root, 'plugins'), { withFileTypes: true })) {
    const mcp = path.join(root, 'plugins', entry.name, '.mcp.json');
    if (entry.isDirectory() && fs.existsSync(mcp) && fs.readFileSync(mcp, 'utf8').includes('jfr-mcp')) found.push(entry.name);
  }
  return found.sort();
}

function referencedTools(plugins) {
  const found = new Map();
  const files = [];
  for (const plugin of plugins) {
    const base = path.join(root, 'plugins', plugin);
    const skills = path.join(base, 'skills');
    if (fs.existsSync(skills)) {
      for (const d of fs.readdirSync(skills, { withFileTypes: true })) {
        const file = path.join(skills, d.name, 'SKILL.md');
        if (d.isDirectory() && fs.existsSync(file)) files.push(file);
      }
    }
    const agents = path.join(base, 'agents');
    if (fs.existsSync(agents)) {
      for (const f of fs.readdirSync(agents)) if (f.endsWith('.md')) files.push(path.join(agents, f));
    }
  }
  if (files.length === 0) throw new Error('no skill or agent files found for any jfr-mcp plugin');
  for (const file of files.sort()) {
    const rel = path.relative(root, file);
    fs.readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
      const names = new Set([...line.matchAll(MCP_QUALIFIED)].map(m => m[1]));
      // Strip the qualified forms before scanning for bare ones, so a `tools:` line does not
      // report the same name twice.
      for (const m of line.replace(MCP_QUALIFIED, '').matchAll(TOOL_TOKEN)) {
        if (!NOT_TOOLS.has(m[0])) names.add(m[0]);
      }
      for (const name of names) {
        if (!found.has(name)) found.set(name, []);
        found.get(name).push(`${rel}:${i + 1}`);
      }
    });
  }
  return found;
}

// Starts the server, sends the handshake and `tools/list`, and resolves with what it advertises.
function serverTools(command, timeoutSeconds) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, { shell: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let buffer = '';
    let stderr = '';
    let info = { name: '?', version: '?' };
    let done = false;
    const finish = (fn, value) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      child.kill();
      fn(value);
    };
    const timer = setTimeout(
      () => finish(reject, new Error(`no tools/list response within ${timeoutSeconds}s\nstderr:\n${stderr.slice(-2000)}`)),
      timeoutSeconds * 1000);
    child.stderr.on('data', d => { stderr += d; });
    child.on('error', e => finish(reject, e));
    child.on('exit', code => finish(reject, new Error(`server exited (${code}) before answering tools/list\nstderr:\n${stderr.slice(-2000)}`)));
    child.stdout.on('data', chunk => {
      buffer += chunk;
      let nl;
      while ((nl = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, nl).trim();
        buffer = buffer.slice(nl + 1);
        if (!line.startsWith('{')) continue;
        let msg;
        try { msg = JSON.parse(line); } catch { continue; }
        if (msg.id === 1 && msg.result) info = { name: msg.result.serverInfo?.name ?? '?', version: msg.result.serverInfo?.version ?? '?' };
        if (msg.id === 2 && msg.result) finish(resolve, { tools: new Set((msg.result.tools || []).map(t => t.name)), info });
      }
    });
    for (const m of HANDSHAKE) child.stdin.write(JSON.stringify(m) + '\n');
  });
}

async function main() {
  const args = parseArgs(process.argv);
  const plugins = jfrMcpPlugins();
  const referenced = referencedTools(plugins);

  console.log(`plugins using jfr-mcp: ${plugins.join(', ')}`);
  console.log(`asking the server for its tools: ${args.command}`);
  const { tools, info } = await serverTools(args.command, args.timeout);
  console.log(`server exposes ${tools.size} tools (reported as ${info.name} ${info.version})\n`);

  const missing = [...referenced].filter(([name]) => !tools.has(name));
  if (missing.length > 0) {
    console.log('FAIL: these tools are named here but the server does not expose them:\n');
    for (const [name, locations] of missing.sort()) {
      console.log(`  ${name}`);
      for (const loc of locations) console.log(`      ${loc}`);
    }
    console.log('\nThree things cause this, in rough order of likelihood:\n');
    console.log('  1. This repository is ahead of the published server: the tool exists in');
    console.log('     btraceio/jafar but is not in a release yet. Release it, or hold the skill');
    console.log('     back until it is. Nothing here is wrong; the two are just out of step.');
    console.log('  2. The tool was renamed or removed upstream, and the skill needs updating.');
    console.log('  3. The name is a typo.\n');
    console.log('In all three cases an agent following that skill makes a call that fails.');
    process.exit(1);
  }

  console.log(`OK: all ${referenced.size} referenced tools exist on the server.`);
  const unused = [...tools].filter(t => !referenced.has(t)).sort();
  if (unused.length > 0) {
    // Not a failure: a tool no skill mentions is a coverage gap, not a broken reference.
    console.log(`\n${unused.length} server tools are not mentioned by any skill or agent:\n  ${unused.join(' ')}`);
  }
}

main().catch(e => { console.error(e.message); process.exit(2); });
