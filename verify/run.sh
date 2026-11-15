#!/bin/sh
# Runs the verification layers of the kit and prints one line per layer.
#
#   sh verify/run.sh                 every layer, in order
#   sh verify/run.sh compile lint    only the layers named
#   sh verify/run.sh --list          the layers and what each one catches
#
# Layers (docs/11-verification.md says what each catches and what it cannot):
#   compile   strict compile: every compiler warning is an error
#   lint      ShellCheck on the shell scripts, actionlint on the workflows, ruff on the Python tests
#   bugs      SpotBugs on the compiled tool (verify/spotbugs-exclude.xml lists what it is not asked)
#   selftest  the tool's self tests, with line coverage per package against verify/coverage-floors.txt
#   e2e       the end-to-end flow against mock services (tests/e2e/run.py)
#   real      sdlc check against real Maven with JUnit 4 and 5, and real Vitest
#   package   the bundle and the container image, with their smoke test
#
# A layer ends as PASS, FAIL or NOT RUN (a tool it needs is missing, with what to install).
# Exit code: 0 when every layer asked for passed; 1 when one failed; 2 when none failed but one
# could not run. VERIFY_ALLOW_NOT_RUN=1 turns that 2 into 0.
#
# Environment:
#   VERIFY_CACHE   downloads and work folders (default: $XDG_CACHE_HOME/sdlc-verify or ~/.cache/sdlc-verify).
#                  With Maven through Docker Desktop it must be a folder Docker may mount.
#   JAVA           the java to use (default: $JAVA_HOME/bin/java, else java on the PATH). It must be a JDK.
set -u

here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
repo=$(dirname -- "$here")
cache=${VERIFY_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/sdlc-verify}
work=$cache/work
logs=$work/logs
all="compile lint bugs selftest e2e real package"

if [ "${1:-}" = "--list" ]; then
  sed -n '/^# Layers/,/^#   package/p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
fi
layers=${*:-$all}
for l in $layers; do
  case " $all " in
    *" $l "*) ;;
    *) echo "verify/run.sh: no layer named $l. Layers: $all" >&2; exit 3 ;;
  esac
done

if [ -n "${JAVA:-}" ]; then java=$JAVA
elif [ -n "${JAVA_HOME:-}" ]; then java=$JAVA_HOME/bin/java
else java=java; fi

mkdir -p "$logs"
summary=$work/summary.txt
: > "$summary"
failed=0
missing=0

os=$(uname -s)
arch=$(uname -m)
case "$os-$arch" in
  Linux-x86_64) platform=linux-x86_64 ;;
  Darwin-arm64) platform=darwin-arm64 ;;
  *) platform= ;;
esac

note() { printf '%s\n' "$*"; }

# result <layer> <PASS|FAIL|NOT RUN> <what>
result() {
  printf '%-9s %-8s %s\n' "$1" "$2" "$3" >> "$summary"
  case $2 in
    FAIL) failed=1 ;;
    "NOT RUN") missing=1 ;;
  esac
}

# A pinned tool: prints its path. $1 the lock name without the platform, $2 the file inside the archive.
pinned() {
  [ -n "$platform" ] || return 1
  archive=$(sh "$here/fetch.sh" "$1-$platform") || return 1
  dir=$cache/tools/$1-$platform
  if [ ! -x "$dir/$2" ]; then
    rm -rf "$dir"
    mkdir -p "$dir"
    case $archive in
      *.tar.xz) tar -xJf "$archive" -C "$dir" --strip-components=1 || return 1 ;;
      *.tar.gz) tar -xzf "$archive" -C "$dir" || return 1 ;;
    esac
  fi
  [ -x "$dir/$2" ] && printf '%s\n' "$dir/$2"
}

sources() { find "$repo/template/.agent/src" -name '*.java'; }

# The compiled tool, with debug information, in $work/classes. Every warning is an error.
build_classes() {
  rm -rf "$work/classes"
  mkdir -p "$work/classes"
  sources > "$work/sources.txt"
  printf '%s\n' "$repo/template/.agent/Sdlc.java" >> "$work/sources.txt"
  # shellcheck disable=SC2046 # one argument per file: the paths of the repository hold no blanks
  if "$java" -m jdk.compiler/com.sun.tools.javac.Main -Xlint:all -Werror -g -encoding UTF-8 -d "$work/classes" $(cat "$work/sources.txt"); then
    return 0
  fi
  # the compiler writes some classes before it fails: they must not be taken for the tool by a later layer
  rm -rf "$work/classes"
  return 1
}

# The tool installed into a scratch repository, compiled by itself: what a user runs. Prints the folder.
installed() {
  kit=$work/kit
  rm -rf "$kit"
  mkdir -p "$kit"
  cp -R "$repo/template/." "$kit/"
  git -C "$kit" init -q -b develop
  printf '%s\n' "$kit"
}

layer_compile() {
  if ! "$java" --list-modules 2>/dev/null | grep -q '^jdk.compiler'; then
    result compile "NOT RUN" "this java is not a JDK: install a JDK 17 or newer, or set JAVA_HOME"
    return
  fi
  if build_classes > "$logs/compile.log" 2>&1; then
    result compile PASS "$(wc -l < "$work/sources.txt" | tr -d ' ') files, no warning ($("$java" -version 2>&1 | head -1))"
  else
    result compile FAIL "see $logs/compile.log"
  fi
}

layer_lint() {
  log=$logs/lint.log
  : > "$log"
  bad=
  not=
  # shell scripts: everything tracked that ends in .sh or starts with a sh or bash line
  scripts=$(cd "$repo" && git ls-files -co --exclude-standard | while read -r f; do
    [ -f "$f" ] || continue
    case $f in
      *.sh) echo "$f" ;;
      *) head -n 1 "$f" 2>/dev/null | grep -q '^#!.*[/ ]\(ba\)\{0,1\}sh' && echo "$f" ;;
    esac
  done)
  if sc=$(pinned shellcheck shellcheck) || sc=$(command -v shellcheck); then
    # shellcheck disable=SC2086 # a list of paths without blanks
    (cd "$repo" && "$sc" $scripts) >> "$log" 2>&1 || bad="$bad shellcheck"
  else
    not="$not shellcheck"
  fi
  if al=$(pinned actionlint actionlint) || al=$(command -v actionlint); then
    if [ -n "${sc:-}" ]; then (cd "$repo" && "$al" -shellcheck "$sc") >> "$log" 2>&1 || bad="$bad actionlint"
    else (cd "$repo" && "$al") >> "$log" 2>&1 || bad="$bad actionlint"; fi
  else
    not="$not actionlint"
  fi
  if command -v ruff >/dev/null 2>&1; then ruff=ruff
  elif python3 -m ruff --version >/dev/null 2>&1; then ruff="python3 -m ruff"
  else ruff=; fi
  if [ -n "$ruff" ]; then
    # shellcheck disable=SC2086 # two words when it is the module form
    (cd "$repo" && $ruff check --config verify/ruff.toml tests/e2e) >> "$log" 2>&1 || bad="$bad ruff"
  else
    not="$not ruff(pip install ruff==$(awk '$1 == "ruff" { print $2 }' "$here/tools.lock"))"
  fi
  if [ -n "$bad" ]; then result lint FAIL "findings from:$bad; see $log"
  elif [ -n "$not" ]; then result lint "NOT RUN" "missing:$not"
  else result lint PASS "$(printf '%s\n' "$scripts" | grep -c .) shell scripts, the workflows, the Python tests"; fi
}

layer_bugs() {
  log=$logs/bugs.log
  # always from the sources as they are now: classes left by an earlier run may be of other code
  build_classes > "$log" 2>&1 || { result bugs FAIL "the tool does not compile, so there is nothing to analyse; see $log"; return; }
  tgz=$(sh "$here/fetch.sh" spotbugs 2>> "$log") || { result bugs "NOT RUN" "SpotBugs could not be fetched; see $log"; return; }
  sb=$cache/tools/spotbugs
  if [ ! -f "$sb/lib/spotbugs.jar" ]; then
    rm -rf "$sb"
    mkdir -p "$sb"
    tar -xzf "$tgz" -C "$sb" --strip-components=1
  fi
  "$java" -jar "$sb/lib/spotbugs.jar" -textui -effort:max -medium -exclude "$here/spotbugs-exclude.xml" \
    -sourcepath "$repo/template/.agent/src" "$work/classes" > "$work/spotbugs.txt" 2>> "$log"
  rc=$?
  n=$(grep -c . "$work/spotbugs.txt")
  if [ "$rc" -ne 0 ]; then result bugs FAIL "SpotBugs did not finish (exit $rc); see $log"
  elif grep -q 'classes needed for analysis were missing' "$log"; then result bugs FAIL "SpotBugs could not see every class of the tool; see $log"
  elif [ "$n" -gt 0 ]; then
    cat "$work/spotbugs.txt" >> "$log"
    result bugs FAIL "$n findings; see $work/spotbugs.txt"
  else result bugs PASS "SpotBugs $(awk '$1 == "spotbugs" { print $2 }' "$here/tools.lock"), no finding at its default level"; fi
}

layer_selftest() {
  log=$logs/selftest.log
  agent=$(sh "$here/fetch.sh" jacoco-agent 2> "$log") && cli=$(sh "$here/fetch.sh" jacoco-cli 2>> "$log") || agent=
  kit=$(installed)
  rm -f "$work/jacoco.exec"
  if [ -n "$agent" ]; then
    (cd "$kit" && "$java" "-javaagent:$agent=destfile=$work/jacoco.exec,includes=sdlc.*" .agent/Sdlc.java selftest) >> "$log" 2>&1
  else
    (cd "$kit" && "$java" .agent/Sdlc.java selftest) >> "$log" 2>&1
  fi
  rc=$?
  line=$(grep '^SELFTEST' "$log" | tail -1)
  if [ "$rc" -ne 0 ]; then
    result selftest FAIL "${line:-no result}; see $log"
    return
  fi
  if [ -z "$agent" ]; then
    result selftest "NOT RUN" "$line, but the coverage tool could not be fetched; see $log"
    return
  fi
  "$java" -jar "$cli" report "$work/jacoco.exec" --classfiles "$kit/.agent/.build" --csv "$work/coverage.csv" >> "$log" 2>&1
  if cov=$(python3 "$here/coverage.py" "$work/coverage.csv" "$here/coverage-floors.txt" 2>> "$log"); then
    printf '%s\n' "$cov" >> "$log"
    result selftest PASS "$(printf '%s' "$line" | tr -s ' '); $(printf '%s\n' "$cov" | tail -1)"
  else
    printf '%s\n' "$cov" >> "$log"
    result selftest FAIL "$(printf '%s' "$line" | tr -s ' '); coverage $(printf '%s\n' "$cov" | grep '^UNDER:'); see $log"
  fi
}

layer_e2e() {
  log=$logs/e2e.log
  command -v python3 >/dev/null 2>&1 || { result e2e "NOT RUN" "python3 is missing"; return; }
  (cd "$repo" && python3 tests/e2e/run.py) > "$log" 2>&1
  rc=$?
  line=$(grep 'checks passed' "$log" | tail -1)
  if [ "$rc" -eq 0 ]; then result e2e PASS "${line:-passed}"
  else result e2e FAIL "${line:-no result}; see $log"; fi
}

# mvn on the PATH, or Maven in a container when Docker runs and the image is there or can be pulled.
maven_path() {
  if command -v mvn >/dev/null 2>&1; then
    printf '%s\n' "$PATH"
    return 0
  fi
  command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 || return 1
  mkdir -p "$work/bin" "$cache/m2"
  cat > "$work/bin/mvn" <<EOF
#!/bin/sh
# Maven in a container, for a machine without Maven: the current folder is mounted at its own path.
# Offline first: Maven asks the network about every plugin on each run, which takes most of a minute
# through a container. Only when Maven says it lacks something offline is the run made again online.
run() {
  docker run --rm -u "\$(id -u):\$(id -g)" -e HOME=/tmp/home -e MAVEN_CONFIG=/tmp/home/.m2 \\
    -v "$cache/m2:/tmp/home/.m2" -v "\$PWD:\$PWD" -w "\$PWD" maven:3.9-eclipse-temurin-21 mvn "\$@"
}
out=\$(mktemp)
run -o "\$@" > "\$out" 2>&1
rc=\$?
if [ "\$rc" -ne 0 ] && grep -q 'offline mode' "\$out"; then
  rm -f "\$out"
  run "\$@"
  exit \$?
fi
cat "\$out"
rm -f "\$out"
exit "\$rc"
EOF
  chmod +x "$work/bin/mvn"
  printf '%s\n' "$work/bin:$PATH"
}

layer_real() {
  log=$logs/real.log
  : > "$log"
  kit=$(installed)
  (cd "$kit" && "$java" .agent/Sdlc.java help) > /dev/null 2>> "$log" || { result real FAIL "the tool does not start; see $log"; return; }
  rm -rf "$work/build"
  cp -R "$kit/.agent/.build" "$work/build"
  bad=
  not=
  said=
  if path=$(maven_path); then
    PATH=$path python3 "$repo/tests/e2e/maven.py" "$work/build" "$work/maven" >> "$log" 2>&1 || bad="$bad maven"
    said="$said $(grep 'real-Maven checks passed' "$log" | tail -1);"
  else
    not="$not maven(mvn on the PATH, or Docker running)"
  fi
  mods=${SDLC_E2E_VITEST_NODE_MODULES:-}
  if [ -z "$mods" ] && command -v npm >/dev/null 2>&1; then
    rt=$cache/vitest-runtime
    mkdir -p "$rt"
    cp "$repo/tests/e2e/vitest-runtime/package.json" "$repo/tests/e2e/vitest-runtime/package-lock.json" "$rt/"
    (cd "$rt" && npm ci --ignore-scripts --no-audit --no-fund) >> "$log" 2>&1 && mods=$rt/node_modules
  fi
  if [ -n "$mods" ] && command -v node >/dev/null 2>&1; then
    python3 -I "$repo/tests/e2e/vitest.py" "$work/build" "$mods" "$work/vitest" >> "$log" 2>&1 || bad="$bad vitest"
    said="$said $(grep 'real-Vitest checks passed' "$log" | tail -1)"
  else
    not="$not vitest(node and npm on the PATH)"
  fi
  if [ -n "$bad" ]; then result real FAIL "failed:$bad;$said see $log"
  elif [ -n "$not" ]; then result real "NOT RUN" "missing:$not;$said"
  else result real PASS "$(printf '%s' "$said" | tr -s ' ' | sed 's/^ //')"; fi
}

layer_package() {
  log=$logs/package.log
  if command -v javac >/dev/null 2>&1 || [ -n "${JAVA_HOME:-}" ]; then
    (cd "$repo" && sh packaging/build.sh && sh packaging/smoke.sh) > "$log" 2>&1
    rc=$?
  elif command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    # no javac here: the bundle is built in a JDK container, and smoke-tested on this machine
    (cd "$repo" && docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -v "$repo:/w" -w /w eclipse-temurin:21-jdk sh packaging/build.sh \
      && sh packaging/smoke.sh) > "$log" 2>&1
    rc=$?
  else
    result package "NOT RUN" "no javac on the PATH, no JAVA_HOME and no Docker: use a full JDK"
    return
  fi
  if [ "$rc" -eq 0 ]; then result package PASS "$(grep -c '^  PASS' "$log") smoke checks"
  else result package FAIL "see $log"; fi
}

for l in $layers; do
  note "verify: $l ..."
  "layer_$l"
  tail -1 "$summary"
done

note ""
note "Verification of $(cd "$repo" && git rev-parse --short HEAD 2>/dev/null || echo '?')$(cd "$repo" && [ -n "$(git status --porcelain 2>/dev/null)" ] && echo ' with uncommitted changes')"
cat "$summary"
if [ "$failed" -ne 0 ]; then
  note "RESULT: FAILED"
  exit 1
fi
if [ "$missing" -ne 0 ]; then
  note "RESULT: INCOMPLETE (a layer could not run)"
  [ "${VERIFY_ALLOW_NOT_RUN:-0}" = 1 ] && exit 0
  exit 2
fi
note "RESULT: PASSED"
