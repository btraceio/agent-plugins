#!/usr/bin/env bash
# Records one eval scenario and converts it to pprof and OTLP, so jfr-analyzer's non-JFR paths can be
# run against a real workload. Usage: regen-formats.sh [scenario-id] [duration-seconds]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EVAL_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CORPUS_DIR="$EVAL_DIR/corpus"
TESTAPP_DIR="$EVAL_DIR/testapp"
ID="${1:-cpu_hotspot}"
DURATION="${2:-20}"

# shellcheck source=preflight.sh
. "$SCRIPT_DIR/preflight.sh"
require_jbang
require_async_profiler

ap_agent_path  # sets AP_AGENT_PATH
JFRCONV="$(dirname "$(dirname "$AP_AGENT_PATH")")/bin/jfrconv"

SOURCE_FLAGS=()
for f in "$TESTAPP_DIR"/*.java; do
  base=$(basename "$f")
  if [[ "$base" != "main.java" ]]; then
    SOURCE_FLAGS+=("-s=$base")
  fi
done

JFR="$CORPUS_DIR/$ID-format-source.jfr"
(cd "$TESTAPP_DIR" && jbang "${SOURCE_FLAGS[@]}" \
  "--java-options=-agentpath:${AP_AGENT_PATH}=start,event=cpu,interval=10ms,file=${JFR},jfr" \
  "-D=scenarios=${ID}" "-D=duration=${DURATION}" main.java)

# jfrconv writes uncompressed pprof whatever the file is called; keep both forms.
"$JFRCONV" -o pprof "$JFR" "$CORPUS_DIR/$ID.pprof"
gzip -c "$CORPUS_DIR/$ID.pprof" > "$CORPUS_DIR/$ID.pb.gz"
"$JFRCONV" -o otlp "$JFR" "$CORPUS_DIR/$ID.otlp"
ls -la "$CORPUS_DIR/$ID.pprof" "$CORPUS_DIR/$ID.pb.gz" "$CORPUS_DIR/$ID.otlp"
