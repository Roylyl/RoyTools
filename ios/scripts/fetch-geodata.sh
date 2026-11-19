#!/usr/bin/env bash
set -euo pipefail

ios_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
output="$ios_dir/Engine/GeoData"
release_url='https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest'
names=(geoip.metadb geoip.dat geosite.dat ASN.mmdb)

mkdir -p -- "$output"
staging="$(mktemp -d "$output/.geodata.XXXXXX")"
cleanup() {
  for name in "${names[@]}"; do rm -f -- "$staging/$name"; done
  rmdir -- "$staging"
}
trap cleanup EXIT

local_dir="${ROYTOOLS_GEODATA_DIR:-}"
if [[ -n "$local_dir" ]]; then
  local_dir="$(cd -- "$local_dir" && pwd -P)"
fi
for name in "${names[@]}"; do
  upstream="$name"
  if [[ "$name" == ASN.mmdb ]]; then upstream=GeoLite2-ASN.mmdb; fi
  if [[ -n "$local_dir" ]]; then
    source="$local_dir/$name"
    if [[ ! -s "$source" ]]; then source="$local_dir/$upstream"; fi
    if [[ ! -f "$source" || ! -s "$source" ]]; then
      printf 'Missing GeoData input: %s in %s\n' "$name" "$local_dir" >&2
      exit 1
    fi
    cp -- "$source" "$staging/$name"
  elif [[ ! -f "$output/$name" || ! -s "$output/$name" ]]; then
    curl --fail --silent --show-error --location \
      --proto '=https' --proto-redir '=https' --retry 2 \
      --connect-timeout 15 --max-time 180 \
      --output "$staging/$name" "$release_url/$upstream"
  fi
  if [[ -e "$staging/$name" && ! -s "$staging/$name" ]]; then
    printf 'Empty GeoData input: %s\n' "$name" >&2
    exit 1
  fi
done

for name in "${names[@]}"; do
  if [[ -f "$staging/$name" ]]; then mv -f -- "$staging/$name" "$output/$name"; fi
  [[ -f "$output/$name" && -s "$output/$name" ]]
done
printf 'Mihomo GeoData ready in %s\n' "$output"
