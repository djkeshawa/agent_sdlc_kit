#!/bin/sh
set -eu

here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
repo=$(dirname -- "$here")
src=$repo/template/.agent/src
dist=${DIST:-$repo/dist}

# jdk.crypto.ec is no compile-time dependency, but without it TLS has no elliptic-curve key exchange
# (JDK 22 and newer fold it into java.base).
modules=${MODULES:-java.base,java.net.http,java.xml,java.desktop,jdk.crypto.ec}

if [ -n "${JAVA_HOME:-}" ]; then
  jdk_bin=$JAVA_HOME/bin
else
  javac_path=$(command -v javac || true)
  [ -n "$javac_path" ] || { echo "build.sh: no JDK found. Set JAVA_HOME to a JDK 21 or newer." >&2; exit 1; }
  while [ -h "$javac_path" ]; do
    link=$(readlink "$javac_path")
    case $link in /*) javac_path=$link ;; *) javac_path=$(dirname -- "$javac_path")/$link ;; esac
  done
  jdk_bin=$(dirname -- "$javac_path")
fi
for tool in javac jar jlink; do
  [ -x "$jdk_bin/$tool" ] || { echo "build.sh: $jdk_bin/$tool is missing; a full JDK 21 or newer is needed." >&2; exit 1; }
done
feature=$("$jdk_bin/javac" -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')
[ "${feature:-0}" -ge 21 ] || { echo "build.sh: JDK $feature found; jlink of JDK 21 or newer is needed." >&2; exit 1; }
if [ "$feature" -ge 22 ]; then
  modules=$(printf '%s' "$modules" | sed 's/,jdk\.crypto\.ec//; s/jdk\.crypto\.ec,//')
fi

case $(uname -s) in
  Linux) os=linux ;;
  Darwin) os=macos ;;
  *) echo "build.sh: unsupported OS $(uname -s)" >&2; exit 1 ;;
esac
case $(uname -m) in
  x86_64 | amd64) arch=x64 ;;
  aarch64 | arm64) arch=arm64 ;;
  *) echo "build.sh: unsupported CPU $(uname -m)" >&2; exit 1 ;;
esac
name=sdlc-$os-$arch
out=$dist/$name
work=$dist/.work-$name
version=${SDLC_VERSION:-$(git -C "$repo" describe --tags --always --dirty 2>/dev/null || echo unknown)}

echo "Building $name ($version) with JDK $feature from $jdk_bin"
rm -rf "$out" "$work" "$dist/$name.tar.gz"
mkdir -p "$work/classes" "$out/bin" "$out/lib"

# javac splits an @argfile on white space: each path goes in double quotes.
find "$src" -name '*.java' | sed 's/\\/\\\\/g; s/"/\\"/g; s/.*/"&"/' > "$work/sources.txt"
"$jdk_bin/javac" -nowarn -encoding UTF-8 --release 17 -d "$work/classes" @"$work/sources.txt"

printf 'Implementation-Title: sdlc\nImplementation-Version: %s\n' "$version" > "$work/manifest.txt"
"$jdk_bin/jar" --create --file "$out/lib/sdlc.jar" --main-class sdlc.Main --manifest "$work/manifest.txt" -C "$work/classes" .

if ! "$jdk_bin/jlink" --add-modules "$modules" --strip-debug --no-man-pages --no-header-files \
  --compress=zip-6 --generate-cds-archive --output "$out/runtime" > "$work/jlink.log" 2>&1; then
  cat "$work/jlink.log" >&2
  exit 1
fi
grep -v '\[warning\]\[cds\]' "$work/jlink.log" || true
rm -f "$out/runtime/lib/server/classes_nocoops.jsa"

cp "$here/sdlc" "$out/bin/sdlc"
chmod 755 "$out/bin/sdlc"
cp "$here/README.md" "$out/README.md"
printf '%s\n' "$version" > "$out/VERSION"
rm -rf "$work"
tar -C "$dist" -czf "$dist/$name.tar.gz" "$name"

echo "Modules: $modules"
echo "Bundle:  $out ($(du -sk "$out" | cut -f1) KB)"
echo "Archive: $dist/$name.tar.gz ($(du -k "$dist/$name.tar.gz" | cut -f1) KB)"
