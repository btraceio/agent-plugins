#!/usr/bin/env node
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const root = path.resolve(__dirname, '..');
const runner = process.argv[2];
const output = process.argv[3] || 'eval-responses.jsonl';
if (!runner) throw new Error('usage: run-evals.js <runner-executable> [output.jsonl]');
const cases = JSON.parse(fs.readFileSync(path.join(root, 'evals/btrace-observability.json'), 'utf8'));
const stream = fs.createWriteStream(output);

async function run(item) {
  return new Promise((resolve, reject) => {
    const child = spawn(runner, [], { cwd: root, stdio: ['pipe', 'pipe', 'inherit'] });
    let text = '';
    child.stdout.on('data', chunk => { text += chunk; });
    child.on('error', reject);
    child.on('close', code => code === 0 ? resolve(text.trim()) : reject(new Error(`${runner} exited ${code}`)));
    child.stdin.end(JSON.stringify({ id: item.id, prompt: item.prompt, requiredSkills: item.requiredSkills }) + '\n');
  });
}

(async () => {
  for (const item of cases) stream.write(JSON.stringify({ id: item.id, text: await run(item) }) + '\n');
  stream.end();
})().catch(error => { stream.destroy(); console.error(error.message); process.exit(1); });
