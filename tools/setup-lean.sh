#!/usr/bin/env bash
# Fetch Mathlib (prebuilt oleans via lake exe cache get, not a source build)
# and build the leanprover-community/repl binary the harness talks to.
# Idempotent. See PLAN.md Phase 5.
set -euo pipefail
export PATH="$HOME/.elan/bin:$PATH"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if ! command -v lake >/dev/null 2>&1; then
  cat >&2 <<'MSG'
ERROR: `lake` is not on PATH, so elan (Lean's toolchain manager) is not
installed. Install it and re-run this script:

  curl https://raw.githubusercontent.com/leanprover/elan/master/elan-init.sh -sSf \
    | sh -s -- -y --default-toolchain none
  source "$HOME/.elan/env"

Do not install a Lean release directly. The workspace pins its own toolchain in
tools/lean-workspace/lean-toolchain and elan fetches that version on demand.
MSG
  exit 1
fi

echo "==> resolving Mathlib in tools/lean-workspace"
cd "$ROOT/tools/lean-workspace"
lake update -R
echo "==> fetching prebuilt Mathlib oleans (large, but far faster than building)"
lake exe cache get

REPL_DIR="$ROOT/tools/lean-repl"
if [[ ! -d "$REPL_DIR" ]]; then
  echo "==> cloning leanprover-community/repl"
  git clone https://github.com/leanprover-community/repl.git "$REPL_DIR"
fi
cd "$REPL_DIR"
# Pin the commit, don't just force a toolchain onto HEAD. HEAD tracks a newer
# Lean and its REPL/Lean/Replay.lean uses options v4.29.1 does not have, so a
# toolchain override alone fails the build. 4957772 is the commit whose
# toolchain is v4.29.0, close enough to patch to .1 and match the workspace.
git fetch --unshallow 2>/dev/null || true
git checkout -- lean-toolchain 2>/dev/null || true
git checkout -q 4957772
echo "leanprover/lean4:v4.29.1" > lean-toolchain
if [[ -x ".lake/build/bin/repl" ]]; then
  echo "==> repl already built"
else
  echo "==> building repl"
  lake build
fi
echo "done: $REPL_DIR/.lake/build/bin/repl"
