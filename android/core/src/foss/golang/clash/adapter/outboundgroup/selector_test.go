package outboundgroup_test

import (
	"testing"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/outbound"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/adapter/provider"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
)

func TestResetSelectionRestoresProviderDefault(t *testing.T) {
	firstNode := adapter.NewProxy(outbound.NewDirectWithOption(outbound.DirectOption{Name: "first"}))
	defaultNode := adapter.NewProxy(outbound.NewDirectWithOption(outbound.DirectOption{Name: "provider-default"}))
	proxies := []C.Proxy{firstNode, defaultNode}
	available, err := provider.NewCompatibleProvider("fixture", proxies, provider.NewHealthCheck(proxies, "", 5000, 0, true, nil))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = available.Close() })
	selector, err := outboundgroup.NewSelector(outboundgroup.GroupCommonOption{Name: "video"},
		outboundgroup.SelectorOption{DefaultSelected: defaultNode.Name()}, firstNode, []P.ProxyProvider{available})
	if err != nil {
		t.Fatal(err)
	}
	if err := selector.Set(firstNode.Name()); err != nil {
		t.Fatal(err)
	}
	if got := selector.Now(); got != firstNode.Name() {
		t.Fatalf("manual selection = %q, want %q", got, firstNode.Name())
	}
	selector.ResetSelection()
	if got := selector.Now(); got != defaultNode.Name() {
		t.Fatalf("reset selection = %q, want %q", got, defaultNode.Name())
	}
}
