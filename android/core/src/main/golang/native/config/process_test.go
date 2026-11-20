package config

import (
	"slices"
	"testing"

	processMode "github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel"
)

func TestSessionRoutingPreservesSubscriptionAndDistinguishesGlobalMode(t *testing.T) {
	home := C.Path.HomeDir()
	C.SetHomeDir(t.TempDir())
	t.Cleanup(func() {
		ClearOverride(OverrideSlotSession)
		C.SetHomeDir(home)
	})
	for _, tt := range []struct {
		name      string
		session   string
		mode      tunnel.TunnelMode
		force     bool
		musicGate bool
	}{
		{name: "subscription global", session: `{}`, mode: tunnel.Global},
		{name: "RoyTools global", session: `{"mode":"global"}`, mode: tunnel.Global, force: true},
		{name: "music", session: `{"mode":"rule","roytools-preset":"music","roytools-proxy-rules":["MATCH,DIRECT"]}`,
			mode: tunnel.Rule, musicGate: true},
	} {
		t.Run(tt.name, func(t *testing.T) {
			originalRules := []string{"DOMAIN,video.test,Video", "MATCH,Default"}
			cfg := config.DefaultRawConfig()
			cfg.Mode = tunnel.Global
			cfg.FindProcessMode = processMode.FindProcessOff
			cfg.Rule = slices.Clone(originalRules)
			WriteOverride(OverrideSlotSession, tt.session)
			if err := patchOverride(cfg, ""); err != nil {
				t.Fatal(err)
			}
			if cfg.Mode != tt.mode || cfg.RoyToolsForceGlobal != tt.force {
				t.Fatalf("session mode = %v proxy-only = %t, want %v proxy-only = %t", cfg.Mode, cfg.RoyToolsForceGlobal, tt.mode, tt.force)
			}
			if !slices.Equal(cfg.Rule, originalRules) {
				t.Fatalf("subscription rules = %v, want %v", cfg.Rule, originalRules)
			}
			if tt.musicGate {
				if !slices.Equal(cfg.RoyToolsProxyRules, []string{"MATCH,DIRECT"}) || cfg.FindProcessMode != processMode.FindProcessStrict {
					t.Fatalf("music gate = %v, process mode = %v", cfg.RoyToolsProxyRules, cfg.FindProcessMode)
				}
			} else if len(cfg.RoyToolsProxyRules) != 0 || cfg.FindProcessMode != processMode.FindProcessOff {
				t.Fatalf("unexpected gate = %v, process mode = %v", cfg.RoyToolsProxyRules, cfg.FindProcessMode)
			}
		})
	}
}
