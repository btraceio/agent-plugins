#!/usr/bin/env bash
# Sourced by regen.sh and other eval scripts.
# Defines require_jbang, require_async_profiler, require_jq.
# Each function: check → explain why → prompt [y/N] → auto-install.
# After install, re-exports PATH / ASYNC_PROFILER_HOME.

set -euo pipefail

_preflight_os() {
  uname -s | tr '[:upper:]' '[:lower:]'
}

require_jbang() {
  if command -v jbang &>/dev/null; then
    return 0
  fi
  echo ""
  echo "jbang is required to build and run the eval test app."
  echo "It manages Java version and dependency resolution without Maven."
  printf "Install jbang now? [y/N] "
  read -r _ans
  if [[ "$_ans" != "y" && "$_ans" != "Y" ]]; then
    echo "Aborted — install jbang manually: https://www.jbang.dev/download/"
    exit 1
  fi
  if [[ "$(_preflight_os)" == "darwin" ]] && command -v brew &>/dev/null; then
    brew install jbangdev/tap/jbang
  else
    curl -Ls https://sh.jbang.dev | bash
    export PATH="$HOME/.jbang/bin:$PATH"
  fi
  if ! command -v jbang &>/dev/null; then
    echo "jbang install appears to have succeeded but is not in PATH."
    echo "Open a new shell and re-run this script."
    exit 1
  fi
  echo "✓ jbang installed"
}

require_async_profiler() {
  local _ver="4.4"
  local _dir="$HOME/.local/lib/async-profiler-${_ver}"
  if [[ -n "${ASYNC_PROFILER_HOME:-}" && -f "$ASYNC_PROFILER_HOME/lib/libasyncProfiler.so" ]]; then
    return 0
  fi
  # macOS uses .dylib
  if [[ -n "${ASYNC_PROFILER_HOME:-}" && -f "$ASYNC_PROFILER_HOME/lib/libasyncProfiler.dylib" ]]; then
    return 0
  fi
  if [[ -f "$_dir/lib/libasyncProfiler.so" || -f "$_dir/lib/libasyncProfiler.dylib" ]]; then
    export ASYNC_PROFILER_HOME="$_dir"
    return 0
  fi
  echo ""
  echo "async-profiler v${_ver} is required to record JFR profiles."
  echo "It will be downloaded to $_dir"
  printf "Download async-profiler v${_ver} now? [y/N] "
  read -r _ans
  if [[ "$_ans" != "y" && "$_ans" != "Y" ]]; then
    echo "Aborted — download from: https://github.com/async-profiler/async-profiler/releases"
    exit 1
  fi
  mkdir -p "$_dir"
  local _os
  _os="$(_preflight_os)"
  local _arch
  _arch="$(uname -m)"
  local _classifier _ext
  if [[ "$_os" == "darwin" ]]; then
    _classifier="macos"
    _ext="zip"
  elif [[ "$_arch" == "aarch64" || "$_arch" == "arm64" ]]; then
    _classifier="linux-arm64"
    _ext="tar.gz"
  else
    _classifier="linux-x64"
    _ext="tar.gz"
  fi
  local _archive="async-profiler-${_ver}-${_classifier}.${_ext}"
  local _url="https://github.com/async-profiler/async-profiler/releases/download/v${_ver}/${_archive}"
  echo "Downloading $_url ..."
  curl -fsSL "$_url" -o "/tmp/${_archive}"
  if [[ "$_ext" == "zip" ]]; then
    unzip -q "/tmp/${_archive}" -d "$_dir"
    # zip puts files in a subdirectory — flatten one level if needed
    local _inner
    _inner=$(find "$_dir" -maxdepth 1 -mindepth 1 -type d | head -1)
    if [[ -n "$_inner" && "$_inner" != "$_dir" ]]; then
      mv "$_inner"/* "$_dir/"
      rmdir "$_inner"
    fi
  else
    tar -xzf "/tmp/${_archive}" -C "$_dir" --strip-components=1
  fi
  rm "/tmp/${_archive}"
  export ASYNC_PROFILER_HOME="$_dir"
  echo "✓ async-profiler ${_ver} installed to $_dir"
}

require_jq() {
  if command -v jq &>/dev/null; then
    return 0
  fi
  echo ""
  echo "jq is required for JSON manipulation in regen.sh."
  printf "Install jq now? [y/N] "
  read -r _ans
  if [[ "$_ans" != "y" && "$_ans" != "Y" ]]; then
    echo "Aborted — install jq: https://jqlang.github.io/jq/download/"
    exit 1
  fi
  if [[ "$(_preflight_os)" == "darwin" ]] && command -v brew &>/dev/null; then
    brew install jq
  elif command -v apt-get &>/dev/null; then
    sudo apt-get install -y jq
  elif command -v dnf &>/dev/null; then
    sudo dnf install -y jq
  else
    echo "Cannot auto-install jq on this platform. Install manually."
    exit 1
  fi
  echo "✓ jq installed"
}

# Determine the async-profiler native agent path (platform-aware).
# Call after require_async_profiler. Sets AP_AGENT_PATH.
ap_agent_path() {
  if [[ -f "${ASYNC_PROFILER_HOME}/lib/libasyncProfiler.dylib" ]]; then
    AP_AGENT_PATH="${ASYNC_PROFILER_HOME}/lib/libasyncProfiler.dylib"
  else
    AP_AGENT_PATH="${ASYNC_PROFILER_HOME}/lib/libasyncProfiler.so"
  fi
}
