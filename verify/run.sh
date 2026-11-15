#!/bin/sh
# Runs the verification layers of the kit and prints one line per layer.
#
#   sh verify/run.sh                 every layer, in order
#   sh verify/run.sh compile lint    only the layers named
#   sh verify/run.sh --list          the layers and what each one catches
#
# Layers (docs/11-verification.md says what each catches and what it cannot):
#   compile   strict compile: every compiler warning is an error
#   lint      ShellCheck on the shell scripts, actionlint on the workflows, ruff on the Python files
#   bugs      SpotBugs on the compiled tool (verify/spotbugs-exclude.xml lists what it is not asked)
#   selftest  the tool's self tests: their count against verify/selftest-count.txt, and line
#             coverage per package against verify/coverage-floors.txt
#   e2e       the end-to-end flow against mock services (tests/e2e/run.py)
#   real      sdlc check against real Maven with JUnit 4 and 5, and real Vitest
#   package   the bundle and the container image, with their smoke test
#
# A layer ends as PASS, FAIL or NOT RUN (a tool it needs is missing, with what to install).
# Exit code: 0 when every layer asked for passed; 1 when one failed; 2 when none failed but one
# could not run (VERIFY_ALLOW_NOT_RUN=1 turns that 2 into 0); 3 for a wrong layer name or when
# another run of this checkout is going on.
#
# Environment:
#   VERIFY_CACHE   downloads and work folders (default: $XDG_CACHE_HOME/sdlc-verify or ~/.cache/sdlc-verify).
#                  With Docker Desktop it must be a folder Docker may mount, as the repository must.
#   JAVA           the java to use (default: $JAVA_HOME/bin/java, else java on the PATH). It must be a JDK.
#   VERIFY_MAVEN_MIRROR  only for Maven in a container (a machine without Maven): the address of a
#                  mirror of Maven Central to fetch from, when Maven Central itself refuses the machine.
set -u

here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
repo=$(dirname -- "$here")
cache=${VERIFY_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/sdlc-verify}
all="compile lint bugs selftest e2e real package"

if [ "${1:-}" = "--list" ]; then
  sed -n '/^# Layers/,/^#   package/p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
fi
if [ "$#" -eq 0 ]; then layers=$all; else layers=$*; fi
[ -n "$(printf '%s' "$layers" | tr -d ' ')" ] || { echo "verify/run.sh: name a layer, or give no argument for all. Layers: $all" >&2; exit 3; }
for l in $layers; do
  case " $all " in
    *" $l "*) ;;
    *) echo "verify/run.sh: no layer named $l. Layers: $all" >&2; exit 3 ;;
  esac
done

if [ -n "${JAVA:-}" ]; then java=$JAVA
elif [ -n "${JAVA_HOME:-}" ]; then java=$JAVA_HOME/bin/java
else java=java; fi

sum256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum | awk '{ print $1 }'
  else shasum -a 256 | awk '{ print $1 }'; fi
}

# One work folder per checkout, and one run in it at a time: two runs that shared the compiled
# classes, the scratch install or the summary could show each other's result.
work=$cache/work/$(printf '%s' "$repo" | sum256 | cut -c1-12)
logs=$work/logs
mkdir -p "$work"
lock=$work/.lock
if ! mkdir "$lock" 2>/dev/null; then
  other=$(cat "$lock/pid" 2>/dev/null || echo '?')
  if [ "$other" != '?' ] && kill -0 "$other" 2>/dev/null; then
    echo "verify/run.sh: another run of this checkout is going on (process $other). Wait for it, or use another VERIFY_CACHE." >&2
    exit 3
  fi
  rm -rf "$lock"
  mkdir "$lock" || { echo "verify/run.sh: cannot lock $work" >&2; exit 3; }
fi
echo $$ > "$lock/pid"
trap 'rm -rf "$lock"' EXIT
trap 'exit 130' INT TERM

mkdir -p "$logs"
summary=$work/summary.txt
: > "$summary"
failed=0
missing=0

case "$(uname -s)-$(uname -m)" in
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

locked() { awk -v n="$1" '$1 == n { print $2 }' "$here/tools.lock"; }

# A pinned tool, unpacked afresh from its checked archive for this run: an unpacked copy left by an
# earlier run is not trusted, as nothing checks it. $1 the lock name without the platform, $2 the
# file inside. Prints the path; exit 1 when the platform has no pinned build, 2 when the archive
# could not be fetched or its checksum is wrong (said on stderr).
pinned() {
  [ -n "$platform" ] || return 1
  archive=$(sh "$here/fetch.sh" "$1-$platform") || return 2
  dir=$work/tools/$1
  rm -rf "$dir"
  mkdir -p "$dir"
  case $archive in
    *.tar.xz) tar -xJf "$archive" -C "$dir" --strip-components=1 || return 2 ;;
    *.tar.gz) tar -xzf "$archive" -C "$dir" || return 2 ;;
  esac
  [ -x "$dir/$2" ] || return 2
  printf '%s\n' "$dir/$2"
}

# The compiled tool, with debug information, in $work/classes. Every warning is an error.
build_classes() {
  rm -rf "$work/classes"
  mkdir -p "$work/classes"
  # an argument file, one quoted path per line: a path of the repository may hold a blank
  { find "$repo/template/.agent/src" -name '*.java'; printf '%s\n' "$repo/template/.agent/Sdlc.java"; } \
    | sed 's/\\/\\\\/g; s/"/\\"/g; s/^/"/; s/$/"/' > "$work/sources.txt"
  if "$java" -m jdk.compiler/com.sun.tools.javac.Main -Xlint:all -Werror -g -encoding UTF-8 -d "$work/classes" "@$work/sources.txt"; then
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

is_jdk() { "$java" --list-modules 2>/dev/null | grep -q '^jdk.compiler'; }

layer_compile() {
  is_jdk || { result compile "NOT RUN" "this java is not a JDK: install a JDK 17 or newer, or set JAVA_HOME"; return; }
  if build_classes > "$logs/compile.log" 2>&1; then
    result compile PASS "$(grep -c . "$work/sources.txt") files, no warning ($("$java" -version 2>&1 | head -1))"
  else
    result compile FAIL "see $logs/compile.log"
  fi
}

# The files of the checkout, tracked or new, one per line. Fails outside a git checkout.
files() { (cd "$repo" && git ls-files -co --exclude-standard); }

# Runs "$@" with the paths on stdin as further arguments, whatever characters the paths hold.
with_paths() { tr '\n' '\0' | (cd "$repo" && xargs -0 "$@"); }

layer_lint() {
  log=$logs/lint.log
  : > "$log"
  bad=
  not=
  files > "$work/files.txt" 2>> "$log" || { result lint "NOT RUN" "not a git checkout: the lint reads the list of files from git"; return; }
  # shell scripts: named .sh, or with a first line that names sh, bash, dash or ksh; Python the same way
  : > "$work/shell.txt"
  : > "$work/python.txt"
  while IFS= read -r f; do
    [ -f "$repo/$f" ] || continue
    case $f in
      *.sh) echo "$f" >> "$work/shell.txt" ;;
      *.py) echo "$f" >> "$work/python.txt" ;;
      *)
        first=$(head -n 1 "$repo/$f" 2>/dev/null | tr -d '\000')
        if printf '%s\n' "$first" | grep -Eq '^#! *(/[^ ]*/)?(env +)?(ba|da|k)?sh( |$)'; then echo "$f" >> "$work/shell.txt"; fi
        if printf '%s\n' "$first" | grep -Eq '^#! *(/[^ ]*/)?(env +)?python[0-9.]*( |$)'; then echo "$f" >> "$work/python.txt"; fi
        ;;
    esac
  done < "$work/files.txt"

  sc=$(pinned shellcheck shellcheck 2>> "$log")
  rc=$?
  if [ "$rc" -eq 1 ] && sc=$(command -v shellcheck); then rc=0; fi
  if [ "$rc" -eq 0 ]; then
    with_paths "$sc" < "$work/shell.txt" >> "$log" 2>&1 || bad="$bad shellcheck"
  elif [ "$rc" -eq 2 ]; then
    sc=
    bad="$bad shellcheck(not fetched, or its checksum is wrong)"
  else
    sc=
    not="$not shellcheck"
  fi

  al=$(pinned actionlint actionlint 2>> "$log")
  rc=$?
  if [ "$rc" -eq 1 ] && al=$(command -v actionlint); then rc=0; fi
  if [ "$rc" -eq 0 ]; then
    if [ -n "$sc" ]; then (cd "$repo" && "$al" -shellcheck "$sc") >> "$log" 2>&1 || bad="$bad actionlint"
    else (cd "$repo" && "$al") >> "$log" 2>&1 || bad="$bad actionlint"; fi
  elif [ "$rc" -eq 2 ]; then bad="$bad actionlint(not fetched, or its checksum is wrong)"
  else not="$not actionlint"; fi
  # actionlint knows the inputs of an action only by its tag, so it cannot check one pinned by a
  # commit. What is checked here instead: every action is pinned by a full commit id.
  unpinned=$(grep -En '^ *-? *uses: *[^ #]+' "$repo"/.github/workflows/*.yml | grep -Ev 'uses: *\./|@[0-9a-f]{40}( |$)')
  if [ -n "$unpinned" ]; then
    printf 'actions not pinned by a full commit id:\n%s\n' "$unpinned" >> "$log"
    bad="$bad unpinned-actions"
  fi

  if command -v ruff >/dev/null 2>&1; then ruff=ruff
  elif python3 -m ruff --version >/dev/null 2>&1; then ruff="python3 -m ruff"
  else ruff=; fi
  if [ -n "$ruff" ]; then
    # shellcheck disable=SC2086 # two words when it is the module form
    with_paths $ruff check --config verify/ruff.toml < "$work/python.txt" >> "$log" 2>&1 || bad="$bad ruff"
  else
    not="$not ruff(pip install ruff==$(locked ruff))"
  fi

  if [ -n "$bad" ]; then result lint FAIL "findings from:$bad; see $log"
  elif [ -n "$not" ]; then result lint "NOT RUN" "missing:$not"
  else result lint PASS "$(grep -c . "$work/shell.txt") shell scripts, the workflows, $(grep -c . "$work/python.txt") Python files"; fi
}

layer_bugs() {
  log=$logs/bugs.log
  : > "$log"
  is_jdk || { result bugs "NOT RUN" "this java is not a JDK: install a JDK 17 or newer, or set JAVA_HOME"; return; }
  # always from the sources as they are now: classes left by an earlier run may be of other code
  build_classes >> "$log" 2>&1 || { result bugs FAIL "the tool does not compile, so there is nothing to analyse; see $log"; return; }
  tgz=$(sh "$here/fetch.sh" spotbugs 2>> "$log") || { result bugs FAIL "SpotBugs could not be fetched, or its checksum is wrong; see $log"; return; }
  sb=$work/tools/spotbugs
  rm -rf "$sb"
  mkdir -p "$sb"
  tar -xzf "$tgz" -C "$sb" --strip-components=1 || { result bugs FAIL "SpotBugs could not be unpacked; see $log"; return; }
  rm -f "$work/spotbugs.txt"
  "$java" -jar "$sb/lib/spotbugs.jar" -textui -effort:max -medium -exclude "$here/spotbugs-exclude.xml" \
    -sourcepath "$repo/template/.agent/src" "$work/classes" > "$work/spotbugs.txt" 2>> "$log"
  rc=$?
  n=$(grep -c . "$work/spotbugs.txt")
  if [ "$rc" -ne 0 ]; then result bugs FAIL "SpotBugs did not finish (exit $rc); see $log"
  elif grep -q 'classes needed for analysis were missing' "$log"; then result bugs FAIL "SpotBugs could not see every class of the tool; see $log"
  elif [ "$n" -gt 0 ]; then
    cat "$work/spotbugs.txt" >> "$log"
    result bugs FAIL "$n findings; see $work/spotbugs.txt"
  else result bugs PASS "SpotBugs $(locked spotbugs), no finding at its default level"; fi
}

layer_selftest() {
  log=$logs/selftest.log
  : > "$log"
  if ! agent=$(sh "$here/fetch.sh" jacoco-agent 2>> "$log") || ! cli=$(sh "$here/fetch.sh" jacoco-cli 2>> "$log"); then
    result selftest FAIL "the coverage tool could not be fetched, or its checksum is wrong; see $log"
    return
  fi
  kit=$(installed)
  rm -f "$work/jacoco.exec" "$work/coverage.csv"
  (cd "$kit" && "$java" "-javaagent:$agent=destfile=$work/jacoco.exec,includes=sdlc.*" .agent/Sdlc.java selftest) >> "$log" 2>&1
  rc=$?
  # The result line itself, not the exit code alone: a run that ends early with exit 0 has no such line.
  line=$(grep '^SELFTEST' "$log" | tail -1 | tr -s ' ')
  count=$(printf '%s\n' "$line" | sed -n 's/^SELFTEST PASSED (\([0-9][0-9]*\) passed, 0 failed)$/\1/p')
  floor=$(grep -v '^#' "$here/selftest-count.txt" | grep -Eo '^[0-9]+' | head -1)
  if [ "$rc" -ne 0 ] || [ -z "$count" ]; then
    result selftest FAIL "${line:-the self tests printed no result} (exit $rc); see $log"
    return
  fi
  if [ -z "$floor" ] || [ "$count" -lt "$floor" ]; then
    result selftest FAIL "$line, but verify/selftest-count.txt asks for at least ${floor:-a number}: self tests were lost; see $log"
    return
  fi
  if ! "$java" -jar "$cli" report "$work/jacoco.exec" --classfiles "$kit/.agent/.build" --csv "$work/coverage.csv" >> "$log" 2>&1 || [ ! -s "$work/coverage.csv" ]; then
    result selftest FAIL "$line, but the coverage report could not be made; see $log"
    return
  fi
  if cov=$(python3 "$here/coverage.py" "$work/coverage.csv" "$here/coverage-floors.txt" 2>> "$log"); then
    printf '%s\n' "$cov" >> "$log"
    result selftest PASS "$line; $(printf '%s\n' "$cov" | tail -1)"
  else
    printf '%s\n' "$cov" >> "$log"
    result selftest FAIL "$line; coverage $(printf '%s\n' "$cov" | grep '^UNDER:'); see $log"
  fi
}

layer_e2e() {
  log=$logs/e2e.log
  command -v python3 >/dev/null 2>&1 || { result e2e "NOT RUN" "python3 is missing"; return; }
  (cd "$repo" && python3 tests/e2e/run.py) > "$log" 2>&1
  rc=$?
  line=$(grep -E '^[0-9]+/[0-9]+ checks passed$' "$log" | tail -1)
  got=${line%%/*}
  of=$(printf '%s' "$line" | sed 's/^[0-9]*\/\([0-9]*\) .*/\1/')
  if [ "$rc" -eq 0 ] && [ -n "$line" ] && [ "$got" = "$of" ]; then result e2e PASS "$line"
  else result e2e FAIL "${line:-no result} (exit $rc); see $log"; fi
}

maven_image=maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320
jdk_image=eclipse-temurin:21-jdk@sha256:0a3f329821dcadf54b2a3033a15e2e9f007069a9db43d7f51897e6b80e726b07

# mvn on the PATH, or Maven in a container when Docker runs. Prints the PATH to use.
maven_path() {
  if command -v mvn >/dev/null 2>&1; then
    printf '%s\n' "$PATH"
    return 0
  fi
  command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 || return 1
  mkdir -p "$work/bin" "$cache/m2/repository"
  settings=
  if [ -n "${VERIFY_MAVEN_MIRROR:-}" ]; then
    case $VERIFY_MAVEN_MIRROR in
      https://*) ;;
      *) echo "verify/run.sh: VERIFY_MAVEN_MIRROR must be an https address" >&2; return 1 ;;
    esac
    printf '<settings><mirrors><mirror><id>verify-mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>\n' \
      "$VERIFY_MAVEN_MIRROR" > "$work/bin/settings.xml"
    settings="-v $work/bin/settings.xml:/settings.xml:ro"
    mirror="-s /settings.xml"
  else
    mirror=
  fi
  cat > "$work/bin/mvn" <<EOF
#!/bin/sh
# Maven in a container, for a machine without Maven: the current folder is mounted at its own path,
# and Maven's repository is the cache folder (named outright: in the image the user of this id has
# another home). Offline first: Maven asks the network about every plugin on each run, which takes
# most of a minute through a container. Only when Maven says it lacks something offline is the run
# made again online.
run() {
  docker run --rm -u "\$(id -u):\$(id -g)" -e HOME=/tmp/home -v "$cache/m2/repository:/m2" $settings -v "\$PWD:\$PWD" -w "\$PWD" \\
    --entrypoint mvn $maven_image -Dmaven.repo.local=/m2 $mirror "\$@"
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
  command -v python3 >/dev/null 2>&1 || { result real "NOT RUN" "python3 is missing"; return; }
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
    if (cd "$rt" && npm ci --ignore-scripts --no-audit --no-fund) >> "$log" 2>&1; then mods=$rt/node_modules; fi
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
    (cd "$repo" && docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -v "$repo:/w" -w /w "$jdk_image" sh packaging/build.sh \
      && sh packaging/smoke.sh) > "$log" 2>&1
    rc=$?
    if [ "$rc" -ne 0 ] && grep -q 'mounts denied\|is not shared' "$log"; then
      result package "NOT RUN" "Docker may not mount $repo: build with a full JDK (set JAVA_HOME), or keep the checkout in a folder Docker shares"
      return
    fi
  else
    result package "NOT RUN" "no javac on the PATH, no JAVA_HOME and no Docker: use a full JDK"
    return
  fi
  if [ "$rc" -eq 0 ] && grep -q '^SMOKE PASSED' "$log"; then result package PASS "$(grep -c '^  PASS' "$log") smoke checks"
  else result package FAIL "see $log"; fi
}

for l in $layers; do
  note "verify: $l ..."
  "layer_$l"
  tail -1 "$summary"
done

note ""
note "Verification of $(cd "$repo" && git rev-parse --short HEAD 2>/dev/null || echo 'a copy that is not a git checkout')$(cd "$repo" && [ -n "$(git status --porcelain 2>/dev/null)" ] && echo ' with uncommitted changes')"
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
