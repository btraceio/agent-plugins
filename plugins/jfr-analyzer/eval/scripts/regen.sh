#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EVAL_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CORPUS_DIR="$EVAL_DIR/corpus"
TESTAPP_DIR="$EVAL_DIR/testapp"
MANIFEST="$CORPUS_DIR/manifest.json"

# shellcheck source=preflight.sh
. "$SCRIPT_DIR/preflight.sh"
require_jbang
require_async_profiler
require_jq

ap_agent_path  # sets AP_AGENT_PATH

echo "=== jfr-analyzer eval corpus regen ==="
echo "Corpus dir : $CORPUS_DIR"
echo "Agent path : $AP_AGENT_PATH"
echo ""

# ── Build source flags (jbang v0.101 doesn't resolve //SOURCE from subdirs) ──
# Build -s=<file> list for every .java except main.java. Run from TESTAPP_DIR
# so relative paths resolve correctly.
SOURCE_FLAGS=""
for f in "$TESTAPP_DIR"/*.java; do
  base=$(basename "$f")
  if [[ "$base" != "main.java" ]]; then
    SOURCE_FLAGS="$SOURCE_FLAGS -s=$base"
  fi
done

# ── Build / dependency warm-up ────────────────────────────────────────────────
echo "Step 1: Warming up jbang dependencies..."
# Run from TESTAPP_DIR so -s= relative paths resolve correctly
# shellcheck disable=SC2086
(cd "$TESTAPP_DIR" && jbang --fresh $SOURCE_FLAGS --java-options=-Xmx64m \
  -D=scenarios=healthy_baseline -D=duration=2 main.java) 2>&1 \
  | grep -v "^Starting\|^Duration\|^Done\|^Scenario" \
  || true
echo "✓ Build warm-up done"
echo ""

# ── Per-scenario recording ────────────────────────────────────────────────────
echo "Step 2: Recording scenarios..."
SCENARIO_COUNT=$(jq '.scenarios | length' "$MANIFEST")
JDK_VERSION=$(java -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')

for i in $(seq 0 $((SCENARIO_COUNT - 1))); do
  ID=$(jq -r ".scenarios[$i].id" "$MANIFEST")
  FILE=$(jq -r ".scenarios[$i].file" "$MANIFEST")
  DURATION=$(jq -r ".scenarios[$i].duration_seconds" "$MANIFEST")
  # Build --java-options= list for per-scenario JVM flags
  JVM_FLAGS_JSON=$(jq -r ".scenarios[$i].jvm_flags | .[]" "$MANIFEST")
  JVM_ARGS=""
  while IFS= read -r flag; do
    JVM_ARGS="$JVM_ARGS --java-options=$flag"
  done <<< "$JVM_FLAGS_JSON"

  OUT_JFR="$CORPUS_DIR/$FILE"
  echo "  [$((i+1))/$SCENARIO_COUNT] $ID → $FILE (${DURATION}s) ..."

  # Run from TESTAPP_DIR so -s= relative paths resolve correctly
  # shellcheck disable=SC2086
  (cd "$TESTAPP_DIR" && jbang \
    $SOURCE_FLAGS \
    "--java-options=-agentpath:${AP_AGENT_PATH}=start,event=cpu+alloc,interval=10ms,file=${OUT_JFR},jfr" \
    $JVM_ARGS \
    "-D=scenarios=${ID}" \
    "-D=duration=${DURATION}" \
    main.java)

  echo "    ✓ recorded $FILE ($(du -sh "$OUT_JFR" | cut -f1))"
done

echo ""
echo "Step 3: Computing source hash..."
# Hash covers all testapp .java files + per-scenario recording configs
HASH_INPUT=""
if command -v sha256sum &>/dev/null; then
  hash_file() { sha256sum "$1" | awk '{print $1}'; }
  hash_text() { printf '%s' "$1" | sha256sum | awk '{print $1}'; }
else
  hash_file() { shasum -a 256 "$1" | awk '{print $1}'; }
  hash_text() { printf '%s' "$1" | shasum -a 256 | awk '{print $1}'; }
fi
while IFS= read -r -d '' f; do
  HASH_INPUT="$HASH_INPUT$(hash_file "$f")"
done < <(find "$TESTAPP_DIR" -name '*.java' -print0 | sort -z)

# Append recording configs (id, jvm_flags, duration_seconds)
RECORDING_CONFIG=$(jq -c '[.scenarios[] | {id, jvm_flags, duration_seconds}]' "$MANIFEST")
HASH_INPUT="$HASH_INPUT$RECORDING_CONFIG"

SOURCE_HASH="sha256:$(hash_text "$HASH_INPUT")"
echo "  hash: $SOURCE_HASH"

# ── Partial manifest update ───────────────────────────────────────────────────
echo ""
echo "Step 4: Updating manifest.json (generated_at, source_hash, jdk_version)..."
GENERATED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# Update top-level fields
TMP=$(mktemp)
jq \
  --arg ga "$GENERATED_AT" \
  --arg sh "$SOURCE_HASH" \
  '.generated_at = $ga | .source_hash = $sh' \
  "$MANIFEST" > "$TMP"

# Update jdk_version per scenario
SCENARIO_COUNT=$(jq '.scenarios | length' "$TMP")
for i in $(seq 0 $((SCENARIO_COUNT - 1))); do
  jq --arg v "$JDK_VERSION" --argjson i "$i" \
    '.scenarios[$i].jdk_version = $v' \
    "$TMP" > "${TMP}.2" && mv "${TMP}.2" "$TMP"
done

mv "$TMP" "$MANIFEST"
echo "  ✓ manifest.json updated"

echo ""
echo "=== regen.sh complete ==="
echo "Next step: run '/jfr-analyzer eval regen' in Claude Code to extract"
echo "event types from the JFR files via MCP and validate oracle outputs."
