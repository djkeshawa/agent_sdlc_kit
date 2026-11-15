#!/bin/sh
# Prints the path of a verification tool's download, fetching it first when it is not in the cache.
# The SHA-256 in verify/tools.lock must match, for a fresh download and for a cached one alike.
#   usage: sh verify/fetch.sh <name>
#   cache: $VERIFY_CACHE, else $XDG_CACHE_HOME/sdlc-verify, else ~/.cache/sdlc-verify
set -eu

here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
name=${1:?usage: fetch.sh <name>}
cache=${VERIFY_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/sdlc-verify}/dl

line=$(grep -v '^#' "$here/tools.lock" | awk -v n="$name" '$1 == n { print; exit }')
[ -n "$line" ] || { echo "fetch.sh: no tool named $name in verify/tools.lock" >&2; exit 2; }
want=$(printf '%s\n' "$line" | awk '{ print $3 }')
url=$(printf '%s\n' "$line" | awk '{ print $4 }')
case $url in
  https://*) ;;
  *) echo "fetch.sh: $name is not a download ($url)" >&2; exit 2 ;;
esac
file=$cache/$(basename -- "$url")

sum() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{ print $1 }'
  else shasum -a 256 "$1" | awk '{ print $1 }'; fi
}

mkdir -p "$cache"
if [ ! -f "$file" ]; then
  tmp=$file.part.$$
  trap 'rm -f "$tmp"' EXIT INT TERM
  curl -fsSL --retry 2 --max-time 600 -o "$tmp" "$url" || { echo "fetch.sh: could not download $url" >&2; exit 1; }
  got=$(sum "$tmp")
  if [ "$got" != "$want" ]; then
    echo "fetch.sh: $name: the download has SHA-256 $got, verify/tools.lock says $want. Not used." >&2
    exit 1
  fi
  mv "$tmp" "$file"
  trap - EXIT INT TERM
fi
got=$(sum "$file")
if [ "$got" != "$want" ]; then
  echo "fetch.sh: $name: the cached file $file has SHA-256 $got, verify/tools.lock says $want. Remove it and run again." >&2
  exit 1
fi
printf '%s\n' "$file"
