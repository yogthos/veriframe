#!/usr/bin/env bash
# Setup script: fetch and build leanprover-community/repl pinned to
# Lean 4.29.1 (matching tools/lean-workspace's toolchain). Idempotent
# — safe to re-run.
#
# Output binary: tools/lean-repl/.lake/build/bin/repl
# Used by:        src/harness/lean-repl.ts (long-lived REPL subprocess
#                 backing proof_start / proof_step).
#
# Prereq: elan + a default Lean toolchain installed. Bootstrap:
#   curl https://raw.githubusercontent.com/leanprover/elan/master/elan-init.sh -sSf | sh
#   source $HOME/.elan/env

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPL_DIR="$REPO_ROOT/tools/lean-repl"

# We pin to commit 4957772 ("chore: bump toolchain to v4.29.0") and
# patch the toolchain to v4.29.1 to match our workspace exactly.
PIN_COMMIT="4957772"
TARGET_TOOLCHAIN="leanprover/lean4:v4.29.1"

# Make sure elan/lake are visible.
export PATH="$HOME/.elan/bin:$PATH"
if ! command -v lake >/dev/null 2>&1; then
  echo "ERROR: lake not found on PATH. Install elan first:" >&2
  echo "  curl https://raw.githubusercontent.com/leanprover/elan/master/elan-init.sh -sSf | sh" >&2
  exit 1
fi

if [[ ! -d "$REPL_DIR" ]]; then
  echo "==> cloning leanprover-community/repl into $REPL_DIR"
  git clone https://github.com/leanprover-community/repl.git "$REPL_DIR"
fi

cd "$REPL_DIR"
echo "==> checking out pinned commit $PIN_COMMIT"
git fetch --unshallow 2>/dev/null || true
git checkout "$PIN_COMMIT" -- .
echo "$TARGET_TOOLCHAIN" > lean-toolchain

if [[ -x ".lake/build/bin/repl" ]]; then
  echo "==> repl binary already built; skipping (delete .lake to force rebuild)"
else
  echo "==> building repl (first time: ~1-3 min)"
  lake build
fi

echo
echo "Done. The harness will spawn this binary lazily on first proof_start call."
echo "Path: $REPL_DIR/.lake/build/bin/repl"
