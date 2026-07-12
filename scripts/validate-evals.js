#!/usr/bin/env node
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const file = path.join(root, 'evals', 'btrace-observability.json');
const cases = JSON.parse(fs.readFileSync(file, 'utf8'));
if (!Array.isArray(cases) || cases.length < 5) throw new Error('eval corpus must contain at least five cases');

const skillPaths = new Set();
function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const child = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(child);
    else if (entry.name === 'SKILL.md') skillPaths.add(path.relative(root, child).split(path.sep).slice(-2, -1)[0]);
  }
}
walk(path.join(root, 'plugins'));

const ids = new Set();
for (const item of cases) {
  for (const key of ['id', 'prompt', 'rubric']) if (typeof item[key] !== 'string' || !item[key].trim()) throw new Error(`missing ${key} in eval case`);
  if (ids.has(item.id)) throw new Error(`duplicate eval id: ${item.id}`);
  ids.add(item.id);
  for (const key of ['requiredSkills', 'mustMention', 'mustAvoid']) {
    if (!Array.isArray(item[key]) || item[key].length === 0 || item[key].some(value => typeof value !== 'string' || !value.trim())) {
      throw new Error(`${item.id}: ${key} must be a non-empty string array`);
    }
  }
  for (const skill of item.requiredSkills) if (!skillPaths.has(skill)) throw new Error(`${item.id}: unknown skill ${skill}`);
}
console.log(`Eval corpus valid: ${cases.length} cases`);
