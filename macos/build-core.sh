#!/usr/bin/env bash
set -euo pipefail

mac_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$mac_root/.." && pwd)"
output="${1:-$mac_root/build/RoyToolsMacCore}"
mkdir -p "$(dirname "$output")"
output="$(cd "$(dirname "$output")" && pwd)/$(basename "$output")"

workspace="$(mktemp -d "${TMPDIR:-/tmp}/roytools-macos-core.XXXXXX")"
trap 'rm -rf "$workspace"' EXIT
engine="$repo_root/ios/Engine"
for source in go.mod go.sum engine.go profile.go lan.go proxy_latency.go packet_device.go; do
    cp "$engine/$source" "$workspace/"
done
cp "$mac_root/Core/main.go" "$workspace/main.go"

# Keep the shared engine and routing policy canonical on iOS. The Mac launcher
# owns the entry point, host restrictions, and the latency request lifetime.
python3 - "$workspace" <<'PY'
import sys
from pathlib import Path

workspace = Path(sys.argv[1])

def replace(text, old, new):
    if text.count(old) != 1:
        raise ValueError(f"Unexpected shared engine source for {old!r}")
    return text.replace(old, new)

engine = workspace / "engine.go"
text = replace(engine.read_text(), "\nfunc main() {}\n", "\n")
text = replace(text, "\tcfg, err := coreconfig.ParseRawConfig(raw)", "\trestrictMacProfile(raw)\n\tcfg, err := coreconfig.ParseRawConfig(raw)")
engine.write_text(text)

latency = workspace / "proxy_latency.go"
text = replace(latency.read_text(), "func measureProxyLatencies(input", "func measureProxyLatencies(lifetime context.Context, input")
text = replace(text, "\tdevice := machine.device.Load()\n\tif device == nil {\n\t\tmachine.Unlock()\n\t\treturn -1\n\t}\n", "")
text = replace(text, "context.WithTimeout(device.ctx,", "context.WithTimeout(lifetime,")
latency.write_text(text)
PY

cd "$workspace"
GOWORK=off go mod edit -replace "github.com/metacubex/mihomo=$repo_root/android/core/src/foss/golang/clash"
GOOS=darwin GOARCH=arm64 CGO_ENABLED=0 GOWORK=off \
    go build -mod=readonly -trimpath -buildvcs=false -ldflags '-s -w' \
    -tags with_gvisor,roytools_packet_engine -o "$output" .
printf '%s\n' "$output"
