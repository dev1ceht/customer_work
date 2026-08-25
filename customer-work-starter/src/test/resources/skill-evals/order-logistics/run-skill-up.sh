#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../../../../../.." && pwd)
skill_source="$repo_root/customer-work-app-server/src/main/resources/skills/order-logistics"
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT HUP INT TERM

command -v skill-up >/dev/null 2>&1 || {
  echo "skill-up is required (Linux/macOS only)." >&2
  exit 127
}

cp -R "$skill_source/." "$work_dir/"
mkdir -p "$work_dir/evals"
cp "$script_dir/eval.yaml" "$work_dir/evals/eval.yaml"
cp -R "$script_dir/cases" "$work_dir/evals/cases"

skill-up validate "$work_dir/evals/eval.yaml"
skill-up run "$work_dir/evals/eval.yaml" "$@"
