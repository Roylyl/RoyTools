#!/usr/bin/env bash
set -euo pipefail

ios_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
engine="$ios_root/Engine"
output="$engine/RoyToolsCore.xcframework"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

if [[ "$(uname -s)" != Darwin ]]; then
    printf '%s\n' 'The iOS XCFramework requires macOS and the Xcode iOS SDKs.' >&2
    exit 1
fi

go version
xcodebuild -version
deployment=17.0

build_slice() {
    local sdk="$1" goarch="$2" target="$3" archive="$4"
    local clang sdkroot
    clang="$(xcrun --sdk "$sdk" --find clang)"
    sdkroot="$(xcrun --sdk "$sdk" --show-sdk-path)"
    local -a quoted_flags=("-isysroot" "\"$sdkroot\"" "-target" "$target")
    (
        cd "$engine"
        export GOOS=ios GOARCH="$goarch" CGO_ENABLED=1 GOWORK=off
        export CC="$clang"
        export CGO_CFLAGS="${quoted_flags[*]}"
        export CGO_LDFLAGS="${quoted_flags[*]}"
        go build -mod=readonly -trimpath -buildvcs=false -buildmode=c-archive \
            -tags with_gvisor,roytools_packet_engine -ldflags '-s -w -buildid=' \
            -o "$archive" .
    )
}

mkdir -p "$scratch/device" "$scratch/simulator"
build_slice iphoneos arm64 "arm64-apple-ios$deployment" "$scratch/device/libRoyToolsCore.a"
build_slice iphonesimulator arm64 "arm64-apple-ios$deployment-simulator" "$scratch/sim-arm64.a"
build_slice iphonesimulator amd64 "x86_64-apple-ios$deployment-simulator" "$scratch/sim-amd64.a"
xcrun lipo -create "$scratch/sim-arm64.a" "$scratch/sim-amd64.a" \
    -output "$scratch/simulator/libRoyToolsCore.a"

xcodebuild -create-xcframework \
    -library "$scratch/device/libRoyToolsCore.a" -headers "$engine/include" \
    -library "$scratch/simulator/libRoyToolsCore.a" -headers "$engine/include" \
    -output "$scratch/RoyToolsCore.xcframework"

if [[ -e "$output" ]]; then
    mv "$output" "$scratch/previous.xcframework"
fi
if ! mv "$scratch/RoyToolsCore.xcframework" "$output"; then
    if [[ -e "$scratch/previous.xcframework" ]]; then
        mv "$scratch/previous.xcframework" "$output"
    fi
    exit 1
fi
printf 'Engine built at %s\n' "$output"
