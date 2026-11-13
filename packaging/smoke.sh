#!/bin/sh
# Smoke test of the packaged tool, against a throwaway git repository that has no .agent/ folder.
#   1. The bundle (dist/sdlc-<os>-<arch>, or the folder given as $1), run with a PATH that holds
#      only sh, git and the launcher's helpers: no java, no JDK, no JAVA_HOME.
#   2. When a Docker daemon is reachable: the image, built from packaging/Dockerfile (or $IMAGE with
#      SMOKE_BUILD=0), run with the repository mounted read-only. Otherwise this part is skipped.
#
# Environment:
#   SMOKE_TLS_URL      an https address for the TLS check (default https://example.com). A fake
#                      token is sent there; the check passes on any HTTP answer, fails on a TLS error.
#   SMOKE_REQUIRE_TLS  1: fail, not skip, when that address cannot be reached at all (CI sets it)
#   SKIP_DOCKER        1: skip the image part
#   IMAGE              image tag to build and test (default sdlc:smoke)
#   SMOKE_BUILD        0: test $IMAGE as it is, do not build it
set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo=$(dirname -- "$here")
dist=${DIST:-$repo/dist}
tls_url=${SMOKE_TLS_URL:-https://example.com}
image=${IMAGE:-sdlc:smoke}

case $(uname -s) in Linux) os=linux ;; Darwin) os=macos ;; *) os=unknown ;; esac
case $(uname -m) in x86_64 | amd64) arch=x64 ;; aarch64 | arm64) arch=arm64 ;; *) arch=unknown ;; esac
bundle=${1:-$dist/sdlc-$os-$arch}
[ -x "$bundle/bin/sdlc" ] || { echo "smoke: no bundle at $bundle (run packaging/build.sh first)" >&2; exit 1; }
bundle=$(CDPATH= cd -- "$bundle" && pwd -P)

# Under the repository, not /tmp: Docker Desktop only mounts folders it shares, and /tmp is often not one.
work=$dist/.smoke
rm -rf "$work"
mkdir -p "$work/repo" "$work/path" "$work/out"
target=$work/repo

failures=0
pass() { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; failures=$((failures + 1)); }
skip() { echo "  SKIP  $1"; }

# The throwaway repository: one commit, no .agent/, no sdlc.yml.
git -C "$target" init -q -b main
printf '# demo\n' > "$target/README.md"
git -C "$target" add README.md
git -C "$target" -c user.name=smoke -c user.email=smoke@example.com -c commit.gpgsign=false \
  -c core.hooksPath=/dev/null commit -q -m "chore: smoke fixture"

# A PATH with only what the launcher and the tool start: sh, git, dirname, readlink.
for tool in sh git dirname readlink; do
  ln -s "$(command -v "$tool")" "$work/path/$tool"
done
printf '# PROJ-1 Smoke\n\n## Area\n\n### TS-01 a scenario\n' > "$work/out/test-design.md"

# Runners. $envs holds extra KEY=value words for one call (no spaces in the values).
envs=
run_bundle() {
  # shellcheck disable=SC2086 # $envs is a list of words
  (cd "$target" && env -i PATH="$work/path" HOME="$work/out" $envs "$bundle/bin/sdlc" "$@")
}
run_image() {
  flags=
  for kv in $envs; do flags="$flags -e $kv"; done
  # shellcheck disable=SC2086
  docker run --rm $flags -v "$target:/repo:ro" "$image" "$@"
}

# Runs <runner> <args...>; sets $out and $rc.
call() {
  if out=$("$@" 2>&1 </dev/null); then rc=0; else rc=$?; fi
}

checks() { # <runner> <label>
  runner=$1
  call "$runner" help
  if [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q '^usage:'; then pass "help"; else fail "help (exit $rc): $out"; fi

  call "$runner" status
  if [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q '^Branch: main'; then pass "status reads the repository with no .agent/"
  else fail "status (exit $rc): $out"; fi

  call "$runner" config check
  if [ "$rc" -le 1 ] && printf '%s' "$out" | grep -q 'problem(s)\|OK'; then pass "config check runs on the built-in defaults (exit $rc)"
  else fail "config check (exit $rc): $out"; fi

  call "$runner" selftest
  if [ "$rc" -eq 0 ]; then pass "selftest: $(printf '%s\n' "$out" | tail -1)"
  else fail "selftest (exit $rc): $(printf '%s\n' "$out" | tail -20)"; fi

  envs="JIRA_BASE_URL=$tls_url JIRA_USER=smoke@example.com JIRA_TOKEN=smoke-not-a-token"
  call "$runner" jira get SMOKE-1
  envs=
  if printf '%s' "$out" | grep -qi 'tls\|ssl\|handshake\|certificate\|pkix\|cipher'; then fail "TLS to $tls_url: $out"
  elif [ "$rc" -eq 4 ] && printf '%s' "$out" | grep -q 'Remote call failed: [0-9]'; then pass "TLS to $tls_url (the server answered: $(printf '%s' "$out" | cut -c 21-60)...)"
  elif [ "${SMOKE_REQUIRE_TLS:-0}" = 1 ]; then fail "TLS to $tls_url could not be checked (exit $rc): $out"
  else skip "TLS to $tls_url could not be checked, no network? ($out)"; fi
}

echo "Bundle: $bundle ($(cat "$bundle/VERSION" 2>/dev/null || echo '?'))"
if env -i PATH="$work/path" sh -c 'command -v java' >/dev/null 2>&1; then
  fail "java is on the stripped PATH; the test would prove nothing"
else
  pass "no java on the PATH the bundle runs with"
fi
checks run_bundle
if "$bundle/runtime/bin/java" --list-modules | grep -q '^java.desktop@'; then
  call run_bundle mindmap --file "$work/out/test-design.md" --out "$work/out/mindmap.png"
  if [ "$rc" -eq 0 ] && [ "$(od -An -c -N4 "$work/out/mindmap.png" 2>/dev/null | tr -d ' ')" = '211PNG' ]; then pass "mindmap draws a PNG"
  else fail "mindmap (exit $rc): $out"; fi
fi

echo "Image: $image"
if [ "${SKIP_DOCKER:-0}" = 1 ]; then
  echo "  SKIPPED: SKIP_DOCKER=1."
elif ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "  SKIPPED: no Docker daemon is reachable. The image was NOT tested."
else
  if [ "${SMOKE_BUILD:-1}" != 0 ]; then
    version=$(cat "$bundle/VERSION" 2>/dev/null || echo unknown)
    docker build -q -f "$here/Dockerfile" --build-arg SDLC_VERSION="$version" -t "$image" "$repo" >/dev/null
  fi
  echo "  size: $(docker image inspect "$image" --format '{{.Size}}' | awk '{printf "%.1f MB", $1 / 1000000}') (image store's figure)"
  call docker run --rm --entrypoint id "$image" -u
  if [ "$out" != 0 ]; then pass "runs as a non-root user (uid $out)"; else fail "runs as root"; fi
  checks run_image
  call docker run --rm -v "$target:/repo:ro" --entrypoint sh "$image" -c 'touch /repo/x'
  if [ "$rc" -ne 0 ]; then pass "the repository was mounted read-only"; else fail "the repository mount was writable"; fi
fi

rm -rf "$work"
if [ "$failures" -eq 0 ]; then echo "SMOKE PASSED"; else echo "SMOKE FAILED ($failures)"; exit 1; fi
