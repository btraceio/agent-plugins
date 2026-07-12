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

const skillFiles = [];
function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(file);
    else if (entry.name === 'SKILL.md') skillFiles.push(file);
  }
}
walk(path.join(root, 'plugins'));
for (const file of skillFiles) {
  const body = fs.readFileSync(file, 'utf8');
  if (!/^---\nname: [a-z0-9-]+\ndescription: .+\n---/m.test(body)) errors.push(`${path.relative(root, file)}: invalid front matter`);
}

if (errors.length) {
  console.error(errors.map(error => `- ${error}`).join('\n'));
  process.exit(1);
}
console.log(`Repository consistency valid: ${claude.plugins.length} plugins, ${skillFiles.length} skills`);
