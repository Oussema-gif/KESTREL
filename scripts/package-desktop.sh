#!/usr/bin/env bash
set -euo pipefail
scripts_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec python3 -B "$scripts_dir/package-desktop.py" "$@"
