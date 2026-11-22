#!/usr/bin/env bash
# Launch metals-mcp for the Mill project with clean state.
#
# The Mill codebase has ~393 BSP targets. BSP init enumerates all of them,
# which takes several seconds. If a previous metals-mcp / Mill BSP process
# is still running, the new one will fight over the BSP lock and both die
# in a SIGTERM cascade.  This script ensures a clean slate before launching.
#
# Usage:
#   ./scripts/launch-metals-mcp.sh [--port PORT]
#
# The script:
#   1. Kills stale metals-mcp / Mill BSP processes
#   2. Cleans lock files and stale .mcp.json
#   3. Launches metals-mcp with MILL_NO_SEPARATE_BSP_OUTPUT_DIR=1
#   4. Polls for .mcp.json (up to 180s — Mill BSP init is slow)
#   5. Stays alive to keep the server running

set -euo pipefail

WORKSPACE="$(cd "$(dirname "$0")/.." && pwd)"
PORT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

# --- Step 1: Kill previous instances ---

echo ">> Cleaning up stale processes..."

# Kill any metals-mcp java processes for this workspace
pgrep -f "metals-mcp.*${WORKSPACE}" | while read -r pid; do
  echo "   Killing metals-mcp process $pid"
  kill "$pid" 2>/dev/null || true
done

# Kill Mill BSP processes for this workspace
pgrep -f "mill.*--bsp.*${WORKSPACE}\|${WORKSPACE}.*mill.*--bsp" | while read -r pid; do
  echo "   Killing Mill BSP process $pid"
  kill "$pid" 2>/dev/null || true
done

# Also check for mill daemon processes holding the BSP lock
if [[ -d "${WORKSPACE}/.bsp/out" ]]; then
  pgrep -f "mill.*${WORKSPACE}" | while read -r pid; do
    # Only kill if it looks like a BSP-related mill process
    if grep -q "bsp" /proc/"$pid"/cmdline 2>/dev/null; then
      echo "   Killing Mill daemon $pid (BSP lock holder)"
      kill "$pid" 2>/dev/null || true
    fi
  done
fi

sleep 1

# Force-kill anything that survived
pgrep -f "metals-mcp.*${WORKSPACE}" | while read -r pid; do
  echo "   Force-killing metals-mcp $pid"
  kill -9 "$pid" 2>/dev/null || true
done

# --- Step 2: Clean stale files ---

echo ">> Cleaning stale MCP/BSP files..."

rm -f "${WORKSPACE}/.mcp.json"
rm -f "${WORKSPACE}/.metals/mcp.json"
rm -f "${WORKSPACE}/.metals/metals.lock.db"

# --- Step 3: Launch metals-mcp ---

PORT_ARGS=""
if [[ -n "$PORT" ]]; then
  PORT_ARGS="--port $PORT"
fi

# MILL_NO_SEPARATE_BSP_OUTPUT_DIR: reuse out/ instead of .bsp/out/
# This avoids duplicate compilation and reduces BSP init overhead.
export MILL_NO_SEPARATE_BSP_OUTPUT_DIR=1

echo ">> Starting metals-mcp for ${WORKSPACE}"
echo "   MILL_NO_SEPARATE_BSP_OUTPUT_DIR=1"

metals-mcp \
  --workspace "${WORKSPACE}" \
  --client claude \
  $PORT_ARGS \
  2>"${WORKSPACE}/metals_error.log" &

MCP_PID=$!
echo "   metals-mcp PID: ${MCP_PID}"

# --- Step 4: Poll for .mcp.json ---

echo ">> Waiting for MCP server to be ready (this may take 2-3 minutes for Mill)..."

TIMEOUT=180
ELAPSED=0
INTERVAL=3

while [[ $ELAPSED -lt $TIMEOUT ]]; do
  # Check if process is still alive
  if ! kill -0 "$MCP_PID" 2>/dev/null; then
    echo "!! metals-mcp exited unexpectedly. Check metals_error.log"
    exit 1
  fi

  # Check for .mcp.json at project root
  if [[ -f "${WORKSPACE}/.mcp.json" ]]; then
    MCP_URL=$(grep -o '"url"[[:space:]]*:[[:space:]]*"[^"]*"' "${WORKSPACE}/.mcp.json" | head -1 | sed 's/.*"url"[[:space:]]*:[[:space:]]*"\([^"]*\)"/\1/')
    if [[ -n "$MCP_URL" ]]; then
      echo ">> MCP server ready at: ${MCP_URL}"
      echo "   .mcp.json written — Claude Code will auto-discover it"
      echo ""
      echo "   Keep this terminal open. Press Ctrl+C to stop."
      wait "$MCP_PID"
      exit 0
    fi
  fi

  # Also check .metals/mcp.json (metals may write there instead)
  if [[ -f "${WORKSPACE}/.metals/mcp.json" ]]; then
    MCP_URL=$(grep -o '"url"[[:space:]]*:[[:space:]]*"[^"]*"' "${WORKSPACE}/.metals/mcp.json" | head -1 | sed 's/.*"url"[[:space:]]*:[[:space:]]*"\([^"]*\)"/\1/')
    if [[ -n "$MCP_URL" ]]; then
      # Copy to project root for Claude Code discovery if not already there
      if [[ ! -f "${WORKSPACE}/.mcp.json" ]]; then
        cat > "${WORKSPACE}/.mcp.json" <<EOF
{
  "mcpServers": {
    "metals": {
      "url": "${MCP_URL}",
      "type": "http"
    }
  }
}
EOF
      fi
      echo ">> MCP server ready at: ${MCP_URL}"
      echo "   .mcp.json written — Claude Code will auto-discover it"
      echo ""
      echo "   Keep this terminal open. Press Ctrl+C to stop."
      wait "$MCP_PID"
      exit 0
    fi
  fi

  sleep "$INTERVAL"
  ELAPSED=$((ELAPSED + INTERVAL))
  echo "   ... ${ELAPSED}s / ${TIMEOUT}s"
done

echo "!! Timed out waiting for MCP server after ${TIMEOUT}s"
echo "   Check metals_error.log for details"
kill "$MCP_PID" 2>/dev/null || true
exit 1
