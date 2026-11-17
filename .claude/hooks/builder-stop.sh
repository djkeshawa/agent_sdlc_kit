#!/bin/sh
# Stop check of the builder agent (.claude/agents/builder.md).
#
# Claude Code runs this when the builder tries to end its turn. It runs the two fast
# verification layers in the checkout the builder works in. When one fails, the builder
# is sent back to work with the result as the reason.
#
#   layers PASS                      the builder may stop
#   a layer FAILS                    the stop is blocked
#   the check itself cannot run      the stop is allowed, with a warning (the lead runs
#                                    every layer again before it accepts the chunk)
set -u

in=$(cat)
dir=$(printf '%s' "$in" | jq -r '.cwd // empty' 2>/dev/null) || dir=
if [ -z "$dir" ] || [ ! -d "$dir" ]; then dir=$PWD; fi

top=$(git -C "$dir" rev-parse --show-toplevel 2>/dev/null) || top=
if [ -z "$top" ] || [ ! -f "$top/verify/run.sh" ]; then
  echo "builder-stop: no verify/run.sh above $dir, so the stop check did not run" >&2
  exit 1
fi

out=$(sh "$top/verify/run.sh" compile selftest 2>&1)
rc=$?

if [ "$rc" -eq 0 ]; then exit 0; fi

if [ "$rc" -ne 1 ]; then
  {
    echo "builder-stop: the stop check could not run in $top (verify/run.sh ended with $rc):"
    printf '%s\n' "$out" | tail -n 15
  } >&2
  exit 1
fi

reason=$(
  echo "The stop check failed in $top: sh verify/run.sh compile selftest"
  printf '%s\n' "$out" | tail -n 40
  echo "Fix the cause, run the layers yourself, and only then finish. Do not weaken a test, a coverage floor or verify/selftest-count.txt to get past this check."
)

if command -v jq >/dev/null 2>&1; then
  jq -n --arg r "$reason" '{decision: "block", reason: $r}'
  exit 0
fi
printf '%s\n' "$reason" >&2
exit 2
