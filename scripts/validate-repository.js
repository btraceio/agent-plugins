#!/usr/bin/env node
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const readJson = file => JSON.parse(fs.readFileSync(path.join(root, file), 'utf8'));
const exists = file => fs.existsSync(path.join(root, file));
const errors = [];
const claude = readJson('.claude-plugin/marketplace.json');
const codex = readJson('.agents/plugins/marketplace.json');
const pi = readJson('package.json');

function checkCatalog(catalog, kind) {
  if (!Array.isArray(catalog.plugins) || catalog.plugins.length === 0) errors.push(`${kind}: plugins must be non-empty`);
  for (const entry of catalog.plugins || []) {
    if (!entry.name) errors.push(`${kind}: plugin entry is missing name`);
    const pluginRoot = `plugins/${entry.name}`;
    if (!exists(pluginRoot)) errors.push(`${kind}: missing ${pluginRoot}`);
    if (kind === 'claude') {
      if (entry.source !== `./${pluginRoot}`) errors.push(`${kind}: ${entry.name} has wrong source path`);
      if (!exists(`${pluginRoot}/.claude-plugin/plugin.json`)) errors.push(`${entry.name}: missing Claude manifest`);
    }
    if (kind === 'codex') {
      if (!entry.source || entry.source.source !== 'local' || entry.source.path !== `./${pluginRoot}`) errors.push(`${kind}: ${entry.name} has wrong source`);
      if (!exists(`${pluginRoot}/.codex-plugin/plugin.json`)) errors.push(`${entry.name}: missing Codex manifest`);
    }
    for (const manifestPath of [`${pluginRoot}/.claude-plugin/plugin.json`, `${pluginRoot}/.codex-plugin/plugin.json`]) {
      if (exists(manifestPath) && readJson(manifestPath).name !== entry.name) errors.push(`${manifestPath}: name does not match catalog`);
    }
  }
}

checkCatalog(claude, 'claude');
checkCatalog(codex, 'codex');
for (const resource of pi.pi?.skills || []) if (!exists(resource.replace(/^\.\//, ''))) errors.push(`pi: missing ${resource}`);
for (const resource of pi.pi?.extensions || []) if (!exists(resource.replace(/^\.\//, ''))) errors.push(`pi: missing ${resource}`);

const skillFiles = [];
const agentFiles = [];
const javaFiles = [];
function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(file);
    else if (entry.name === 'SKILL.md') skillFiles.push(file);
    else if (entry.name.endsWith('.md') && path.basename(dir) === 'agents') agentFiles.push(file);
    else if (entry.name.endsWith('.java')) javaFiles.push(file);
  }
}
walk(path.join(root, 'plugins'));
for (const file of skillFiles) {
  const body = fs.readFileSync(file, 'utf8');
  // name and description first, then any other single-line fields (e.g. allowed-tools) before the closing rule.
  if (!/^---\nname: [a-z0-9-]+\ndescription: .+\n(?:[a-z][a-z-]*: .+\n)*---/m.test(body)) errors.push(`${path.relative(root, file)}: invalid front matter`);
}

// Claude Code names plugin tools mcp__plugin_<plugin>_<server>__<tool>; any other form in an
// allowlist matches nothing, and the agent silently runs without the tool.
for (const file of [...skillFiles, ...agentFiles]) {
  const plugin = path.relative(path.join(root, 'plugins'), file).split(path.sep)[0];
  const mcpConfig = `plugins/${plugin}/.mcp.json`;
  const servers = exists(mcpConfig) ? Object.keys(readJson(mcpConfig).mcpServers) : [];
  const frontMatter = fs.readFileSync(file, 'utf8').match(/^---\n[\s\S]*?\n---/)?.[0] || '';
  for (const [name] of frontMatter.matchAll(/\bmcp__[a-z0-9_-]+/g)) {
    if (!servers.some(server => name.startsWith(`mcp__plugin_${plugin}_${server}__`))) errors.push(`${path.relative(root, file)}: ${name} is not a tool of this plugin's MCP server`);
  }
}

// JBang searches only Maven Central unless told otherwise, so a snapshot dependency needs a repository.
for (const file of javaFiles) {
  const body = fs.readFileSync(file, 'utf8');
  if (/^\/\/DEPS .*-SNAPSHOT/m.test(body) && !/^\/\/REPOS .*snapshots/m.test(body)) errors.push(`${path.relative(root, file)}: snapshot //DEPS without a snapshot //REPOS`);
}

// Every jfr-mcp launch attaches to one shared daemon, so they must all name the same exact release.
// The catalog alias floats to whatever was published last, which is what the pin exists to prevent.
const jfrMcpFiles = ['extensions/mcp-servers.ts', 'plugins/jafar-perf/.mcp.json', 'plugins/jafar-perf/README.md', 'plugins/jfr-analyzer/.mcp.json', 'plugins/jfr-analyzer/README.md'];
const jfrMcpRefs = new Set();
for (const file of jfrMcpFiles) {
  const refs = fs.readFileSync(path.join(root, file), 'utf8').match(/jfr-mcp@btraceio|io\.btrace:jfr-mcp:[^\s"]*/g) || [];
  if (refs.length === 0) errors.push(`${file}: no jfr-mcp launch found`);
  for (const ref of refs) {
    if (!/^io\.btrace:jfr-mcp:\d+\.\d+\.\d+$/.test(ref)) errors.push(`${file}: ${ref} is not pinned to an exact release`);
    jfrMcpRefs.add(ref);
  }
}
if (jfrMcpRefs.size > 1) errors.push(`jfr-mcp launches disagree: ${[...jfrMcpRefs].join(', ')}`);

if (errors.length) {
  console.error(errors.map(error => `- ${error}`).join('\n'));
  process.exit(1);
}
console.log(`Repository consistency valid: ${claude.plugins.length} plugins, ${skillFiles.length} skills`);
