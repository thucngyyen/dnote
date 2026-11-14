# Shared by the build scripts: prefer rustup's cargo (needed for cross targets).
for d in "$HOME/.cargo/bin" "/opt/homebrew/opt/rustup/bin"; do
  [ -d "$d" ] && PATH="$d:$PATH"
done
export PATH
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
