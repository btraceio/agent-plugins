#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

if ! command -v claude >/dev/null 2>&1; then
  echo "error: Claude Code CLI is required for marketplace validation" >&2
  exit 1
fi

claude plugin validate .

node <<'NODE'
const fs = require('fs');
const path = require('path');

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const child = path.join(dir, entry.name);
    return entry.isDirectory() ? walk(child) : [child];
  });
}

for (const file of walk('.').filter(file => file.endsWith('.json') && !file.includes('/.git/'))) {
  JSON.parse(fs.readFileSync(file, 'utf8'));
}
NODE

while IFS= read -r skill; do
  grep -q '^---$' "$skill" || { echo "error: missing front matter: $skill" >&2; exit 1; }
  grep -q '^name: ' "$skill" || { echo "error: missing skill name: $skill" >&2; exit 1; }
  grep -q '^description: ' "$skill" || { echo "error: missing skill description: $skill" >&2; exit 1; }
done < <(find plugins -path '*/skills/*/SKILL.md' -type f | sort)

if rg -n 'https?://github\.com/btraceio/btrace/blob/' plugins; then
  echo "error: copy operational guidance into skills; do not link to BTrace source docs" >&2
  exit 1
fi

source_dir=${BTRACE_SOURCE_DIR:-}
if [[ -z "$source_dir" && -d ../btrace ]]; then
  source_dir=../btrace
fi
if [[ -z "$source_dir" || ! -d "$source_dir/.git" ]]; then
  echo "error: set BTRACE_SOURCE_DIR to a BTrace source checkout for consistency validation" >&2
  exit 1
fi

while IFS='|' read -r relative_path expected; do
  [[ -z "$relative_path" || "$relative_path" == \#* ]] && continue
  source_file="$source_dir/$relative_path"
  if [[ ! -f "$source_file" ]] || ! grep -Fq "$expected" "$source_file"; then
    echo "error: BTrace source fact changed or is missing: $relative_path :: $expected" >&2
    exit 1
  fi
done < references/btrace-source-facts.txt

echo "Marketplace validation passed."
