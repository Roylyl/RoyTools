#!/usr/bin/env bash
set -euo pipefail

mac_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$mac_root/.." && pwd)"

usage() {
    cat <<'EOF'
Usage: bash macos/build-app.sh [--output-dir DIR] [--configuration Debug|Release]
       [--helper FILE] [--dmg]

Build an Apple Silicon Mac Catalyst app from the iPad source.
--helper bundles Mihomo and the native system proxy helper for local use.
EOF
}

output=""
configuration=Release
helper=""
make_dmg=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --output-dir)
            output="${2:?--output-dir requires a directory}"
            shift 2
            ;;
        --configuration)
            configuration="${2:?--configuration requires Debug or Release}"
            shift 2
            ;;
        --helper)
            helper="${2:?--helper requires an executable path}"
            shift 2
            ;;
        --dmg)
            make_dmg=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            exit 2
            ;;
    esac
done

if [[ "$(uname -s)" != Darwin ]]; then
    printf 'The Mac app requires macOS and Xcode.\n' >&2
    exit 1
fi
if [[ "$configuration" != Debug && "$configuration" != Release ]]; then
    printf 'Unsupported configuration: %s\n' "$configuration" >&2
    exit 2
fi
if [[ -n "$helper" && ! -x "$helper" ]]; then
    printf 'Mac helper is not executable: %s\n' "$helper" >&2
    exit 2
fi

output="${output:-$mac_root/build}"
mkdir -p "$output"
output="$(cd "$output" && pwd)"
workspace="$(mktemp -d "${TMPDIR:-/tmp}/roytools-macos.XXXXXX")"
trap 'rm -rf "$workspace"' EXIT

rsync -a --exclude '/Engine/' --exclude 'xcuserdata/' "$repo_root/ios/" "$workspace/ios/"
cp "$repo_root"/{LICENSE,NOTICE,routing-rules.json,routing-version.json} "$workspace/"
catalog_dir=android/app/src/main/assets/music/data
mkdir -p "$workspace/$catalog_dir"
cp "$repo_root/$catalog_dir/catalog.json" "$workspace/$catalog_dir/"

menu_bundle="$workspace/RoyToolsMenuBar.bundle"
mkdir -p "$menu_bundle/Contents/MacOS" "$menu_bundle/Contents/Resources"
cp "$mac_root/MenuBar/Info.plist" "$menu_bundle/Contents/Info.plist"
python3 - "$repo_root/ios/RoyTools/Assets.xcassets/LaunchWordmark.imageset/RoyTools.svg" \
    "$menu_bundle/Contents/Resources/MenuWordmark.svg" <<'PY'
import sys
import xml.etree.ElementTree as ET

namespace = "http://www.w3.org/2000/svg"
ET.register_namespace("", namespace)
mark = ET.parse(sys.argv[1])
root = mark.getroot()
# Crop to the original transformed outlines and preserve their two-line arrangement.
root.attrib.update(width="260", height="223", viewBox="126 145 260 223")
for path in root.iter("{%s}path" % namespace):
    path.set("fill", "#FFFFFF")
mark.write(sys.argv[2], encoding="utf-8")
PY
xcrun --sdk macosx swiftc -O -emit-library -module-name RoyToolsMenuBar -target arm64-apple-macos14.0 \
    "$mac_root"/MenuBar/*.swift -o "$menu_bundle/Contents/MacOS/RoyToolsMenuBar"
codesign --force --sign - "$menu_bundle"

if [[ -n "$helper" ]]; then
    python3 "$mac_root/prepare-project.py" "$workspace/ios" proxy
    shopt -s nullglob
    system_proxy_sources=("$mac_root"/SystemProxy/*.swift)
    shopt -u nullglob
    if [[ -z "${system_proxy_sources[0]:-}" ]]; then
        printf 'Native system proxy helper sources are missing.\n' >&2
        exit 1
    fi
    xcrun --sdk macosx swiftc -O -target arm64-apple-macos14.0 \
        "${system_proxy_sources[@]}" -o "$workspace/RoyToolsMacSystemProxy"
else
    python3 "$mac_root/prepare-project.py" "$workspace/ios"
fi

compile_conditions='$(inherited)'
if [[ -n "$helper" ]]; then
    compile_conditions+=' ROYTOOLS_MAC_PROXY'
fi
xcodebuild -project "$workspace/ios/RoyTools.xcodeproj" -scheme RoyTools \
    -configuration "$configuration" -destination 'generic/platform=macOS,variant=Mac Catalyst' \
    -derivedDataPath "$workspace/DerivedData" \
    ARCHS=arm64 CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
    "SWIFT_ACTIVE_COMPILATION_CONDITIONS=$compile_conditions" -quiet build

app="$workspace/DerivedData/Build/Products/$configuration-maccatalyst/RoyTools.app"
mkdir -p "$app/Contents/PlugIns"
ditto "$menu_bundle" "$app/Contents/PlugIns/RoyToolsMenuBar.bundle"
if [[ -n "$helper" ]]; then
    helpers_dir="$app/Contents/Helpers"
    mkdir -p "$helpers_dir"
    install -m 755 "$helper" "$helpers_dir/RoyToolsMacCore"
    install -m 755 "$workspace/RoyToolsMacSystemProxy" "$helpers_dir/RoyToolsMacSystemProxy"
    codesign --force --sign - "$helpers_dir/RoyToolsMacCore"
    codesign --force --sign - "$helpers_dir/RoyToolsMacSystemProxy"
    geo_data="$repo_root/ios/Engine/GeoData"
    if [[ -d "$geo_data" ]]; then
        resources="$app/Contents/Resources/GeoData"
        mkdir -p "$resources"
        for name in ASN.mmdb geoip.dat geoip.metadb geosite.dat; do
            if [[ -f "$geo_data/$name" ]]; then
                cp "$geo_data/$name" "$resources/"
            fi
        done
    fi
    codesign --force --sign - "$app"
else
    codesign --force --sign - --entitlements "$mac_root/Config/App.entitlements" "$app"
fi

version="$(plutil -extract CFBundleShortVersionString raw "$app/Contents/Info.plist")"
variant=NoVPN
if [[ -n "$helper" ]]; then
    variant=Proxy
fi
artifact_dir="$(mktemp -d "$output/RoyTools-$version-$variant.XXXXXX")"
ditto "$app" "$artifact_dir/RoyTools.app"
printf '%s\n' "$artifact_dir/RoyTools.app"

if [[ "$make_dmg" == true ]]; then
    payload="$workspace/dmg"
    mkdir "$payload"
    ditto "$app" "$payload/RoyTools.app"
    ln -s /Applications "$payload/Applications"
    dmg="$artifact_dir/RoyTools-$version-macOS-arm64.dmg"
    if [[ -z "$helper" ]]; then
        dmg="$artifact_dir/RoyTools-$version-macOS-arm64-NoVPN.dmg"
    fi
    hdiutil create -fs HFS+ -format UDZO -volname "RoyTools $version" -srcfolder "$payload" "$dmg"
    printf '%s\n' "$dmg"
fi
