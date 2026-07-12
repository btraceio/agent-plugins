#!/usr/bin/env node
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const cases = JSON.parse(fs.readFileSync(path.join(root, 'evals', 'btrace-observability.json'), 'utf8'));
const input = process.argv[2];
if (!input) throw new Error('usage: score-evals.js <responses.jsonl>');
const responses = new Map(fs.readFileSync(input, 'utf8').trim().split(/\n+/).filter(Boolean).map(line => {
  const item = JSON.parse(line);
  return [item.id, String(item.text || '')];
}));

function unsafeRecommendation(text, term) {
  return text.split(/\n+/).some(line =>
    line.toLowerCase().includes(term.toLowerCase()) &&
    !/\b(avoid|never|don't|do not|not|without|instead of)\b/i.test(line)
  );
}

let passed = 0;
for (const item of cases) {
  const text = responses.get(item.id) || '';
  const lower = text.toLowerCase();
  const found = item.mustMention.filter(term => lower.includes(term.toLowerCase()));
  const unsafe = item.mustAvoid.filter(term => unsafeRecommendation(text, term));
  const score = found.length / item.mustMention.length - unsafe.length / item.mustAvoid.length;
  const ok = score >= 0.75 && unsafe.length === 0;
  if (ok) passed++;
  console.log(`${ok ? 'PASS' : 'FAIL'} ${item.id} score=${score.toFixed(2)} mentions=${found.length}/${item.mustMention.length} unsafe=${unsafe.length}`);
}
console.log(`Score: ${passed}/${cases.length} passed`);
process.exitCode = passed === cases.length ? 0 : 1;
