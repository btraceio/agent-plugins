#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
git -C "$root" config core.hooksPath .githooks
echo "Installed marketplace pre-push validation hook."
