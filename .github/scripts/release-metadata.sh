#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

case "$1" in
  mod)
    tag_prefix=v
    heading_prefix='## '
    configured_version=$(sed -n 's/^mod_version=//p' gradle.properties)
    ;;
  format)
    tag_prefix=world-mirror-format-v
    heading_prefix='## world-mirror-format '
    configured_version=$(sed -n "s/^version = '\([^']*\)'$/\1/p" world-mirror-format/build.gradle)
    ;;
  *) fail "Unknown release component: $1" ;;
esac

tag=$2
[[ "$tag" != *[!a-z0-9.-]* ]] || fail 'Release tags must use lowercase letters, digits, dots, and hyphens.'
number='(0|[1-9][0-9]*)'
version_pattern="^${tag_prefix}${number}\.${number}\.${number}(-(alpha|beta|rc)([0-9]+|\.${number})?)?$"
[[ "$tag" =~ $version_pattern ]] || fail 'Expected a version tag with an optional alpha, beta, or rc suffix.'
version=${tag#"$tag_prefix"}
base=${version%%-*}
[[ "$configured_version" == "$base" || "$configured_version" == "$version" ]] ||
  fail "Tag $tag does not match the configured version."

case "$version" in
  *-alpha*) channel=alpha; prerelease=true ;;
  *-*) channel=beta; prerelease=true ;;
  *) channel=release; prerelease=false ;;
esac

if ! awk -v prefix="$heading_prefix" -v version="$version" -v base="$base" '
  found && (/^## / || /^---$/) { exit }
  found { print; next }
  index($0, prefix "[") == 1 {
    if (index($0, prefix "[" version "]") == 1) print
    else if (version != base && index($0, prefix "[" base "]") == 1) print prefix "[" version "]"
    else exit 1
    found = 1
  }
  END { if (!found) exit 1 }
' CHANGELOG.md > "$4"; then
  fail "The first changelog section for $1 must match $version or its base version $base."
fi

printf 'version=%s\ntag=%s\nchannel=%s\nprerelease=%s\n' "$version" "$tag" "$channel" "$prerelease" >> "$3"
