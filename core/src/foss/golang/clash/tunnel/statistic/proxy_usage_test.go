package statistic

import (
	"io"
	"net"
	"testing"
	"time"
	_ "time/tzdata"

	C "github.com/metacubex/mihomo/constant"
)

type usageConnection struct {
	C.Connection
	chain C.Chain
}

func (c usageConnection) Chains() C.Chain { return c.chain }

type typedUsageConnection struct {
	usageConnection
	kind C.AdapterType
}

func (c typedUsageConnection) TrafficAdapterType() C.AdapterType { return c.kind }

type wrappedUsageConnection struct {
	C.Connection
	upstream any
}

func (c wrappedUsageConnection) Upstream() any { return c.upstream }

func TestProxyUsageClassifiesActualOutbound(t *testing.T) {
	cases := []struct {
		name string
		conn C.Connection
		want bool
	}{
		{"direct group", usageConnection{chain: C.Chain{"DIRECT", "代理选择"}}, false},
		{"reject group", usageConnection{chain: C.Chain{"REJECT", "代理选择"}}, false},
		{"proxy inside group named DIRECT", usageConnection{chain: C.Chain{"香港节点", "DIRECT"}}, true},
		{"empty chain", usageConnection{}, false},
		{"custom direct alias", typedUsageConnection{usageConnection{chain: C.Chain{"我的直连", "代理选择"}}, C.Direct}, false},
		{"custom reject alias", typedUsageConnection{usageConnection{chain: C.Chain{"我的拦截"}}, C.Reject}, false},
		{"custom reject-drop alias", typedUsageConnection{usageConnection{chain: C.Chain{"我的丢弃"}}, C.RejectDrop}, false},
		{"actual proxy named DIRECT", typedUsageConnection{usageConnection{chain: C.Chain{"DIRECT"}}, C.Socks5}, true},
		{"wrapped direct alias", wrappedUsageConnection{
			Connection: usageConnection{chain: C.Chain{"我的直连", "代理选择"}},
			upstream:   typedUsageConnection{usageConnection{chain: C.Chain{"我的直连"}}, C.Direct},
		}, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := isProxyConnection(tc.conn); got != tc.want {
				t.Fatalf("isProxyConnection()=%v, want %v", got, tc.want)
			}
		})
	}
}

func TestProxyUsageNetworksAndReset(t *testing.T) {
	var manager Manager
	manager.SetTrafficNetwork(TrafficNetworkWifi)
	const large = int64(1<<40) + 17
	manager.pushProxyTraffic(large)
	manager.SetTrafficNetwork(TrafficNetworkMobile)
	manager.pushProxyTraffic(29)
	manager.pushProxyTraffic(-1)
	manager.SetTrafficNetwork(999)
	manager.pushProxyTraffic(100)
	manager.PushUploaded(20)
	manager.PushDownloaded(30)
	manager.ResetStatistic()
	if got := manager.ProxyUsage(); got.Wifi != large || got.Mobile != 29 {
		t.Fatalf("proxy usage reset/truncated/misclassified: %+v", got)
	}
	if up, down := manager.Total(); up != 0 || down != 0 {
		t.Fatalf("existing ResetStatistic semantics changed: %d/%d", up, down)
	}
}

type usageTCPConn struct {
	C.Conn
	kind C.AdapterType
}

func (c usageTCPConn) Chains() C.Chain                   { return C.Chain{"test-outbound", "policy"} }
func (c usageTCPConn) ProviderChains() C.Chain           { return nil }
func (c usageTCPConn) RemoteDestination() string         { return "127.0.0.1:443" }
func (c usageTCPConn) TrafficAdapterType() C.AdapterType { return c.kind }
func (c usageTCPConn) Read(b []byte) (int, error)        { return copy(b, "hello"), io.EOF }
func (c usageTCPConn) Write(b []byte) (int, error)       { return len(b), nil }
func (c usageTCPConn) Close() error                      { return nil }

func TestProxyUsageCountsClosedShortTCPAndUnwrappedIO(t *testing.T) {
	var manager Manager
	manager.SetTrafficNetwork(TrafficNetworkWifi)
	tracker := NewTCPTracker(usageTCPConn{kind: C.Socks5}, &manager, &C.Metadata{}, nil, 7, 3, true)
	_, _ = tracker.Read(make([]byte, 16))
	_, _ = tracker.Write([]byte("four"))
	_, reads := tracker.UnwrapReader()
	reads[0](3)
	_, writes := tracker.UnwrapWriter()
	writes[0](2)
	_ = tracker.Close()
	if got := manager.ProxyUsage(); got.Wifi != 24 || got.Mobile != 0 {
		t.Fatalf("short connection/final bytes lost or duplicated: %+v", got)
	}
	connections := 0
	manager.Range(func(Tracker) bool { connections++; return true })
	if connections != 0 {
		t.Fatalf("closed connection retained: %d", connections)
	}
	manager.SetTrafficNetwork(TrafficNetworkMobile)
	internal := NewTCPTracker(usageTCPConn{kind: C.Socks5}, &manager, &C.Metadata{}, nil, 100, 100, false)
	_, _ = internal.Write([]byte("internal"))
	_ = internal.Close()
	direct := NewTCPTracker(usageTCPConn{kind: C.Direct}, &manager, &C.Metadata{}, nil, 200, 200, true)
	_, _ = direct.Read(make([]byte, 16))
	_ = direct.Close()
	if got := manager.ProxyUsage(); got.Wifi != 24 || got.Mobile != 0 {
		t.Fatalf("internal traffic or DIRECT entered proxy usage: %+v", got)
	}
}

type usageUDPConn struct{ C.PacketConn }

func (usageUDPConn) Chains() C.Chain                           { return C.Chain{"udp-proxy"} }
func (usageUDPConn) ProviderChains() C.Chain                   { return nil }
func (usageUDPConn) RemoteDestination() string                 { return "127.0.0.1:443" }
func (usageUDPConn) TrafficAdapterType() C.AdapterType         { return C.Socks5 }
func (usageUDPConn) ReadFrom(b []byte) (int, net.Addr, error)  { return copy(b, "udp"), nil, nil }
func (usageUDPConn) WriteTo(b []byte, _ net.Addr) (int, error) { return len(b), nil }
func (usageUDPConn) Close() error                              { return nil }

func TestProxyUsageUDPUsesNetworkAtEachIO(t *testing.T) {
	var manager Manager
	manager.SetTrafficNetwork(TrafficNetworkWifi)
	tracker := NewUDPTracker(usageUDPConn{}, &manager, &C.Metadata{}, nil, 0, 0, true)
	_, _ = tracker.WriteTo([]byte("wifi"), nil)
	manager.SetTrafficNetwork(TrafficNetworkMobile)
	_, _, _ = tracker.ReadFrom(make([]byte, 16))
	_ = tracker.Close()
	if got := manager.ProxyUsage(); got.Wifi != 4 || got.Mobile != 3 {
		t.Fatalf("network transition misclassified: %+v", got)
	}
}

func TestProxyUsageMidnightAndLateIO(t *testing.T) {
	location := time.FixedZone("UTC+8", 8*60*60)
	before := time.Date(2026, 11, 4, 23, 59, 59, 900_000_000, location)
	boundary := before.Add(100 * time.Millisecond)
	var manager Manager
	manager.SetTrafficNetwork(TrafficNetworkWifi)
	manager.pushProxyTrafficAt(40, before)
	manager.pushProxyTrafficAt(70, boundary)
	// A pre-midnight IO completing out of order must retain both days' totals.
	manager.pushProxyTrafficAt(2, before)
	if got := manager.proxyUsageAt(before); got != (ProxyUsage{Day: "2026-11-04", Wifi: 42}) {
		t.Fatalf("old-day IO misclassified: %+v", got)
	}
	if got := manager.proxyUsageAt(boundary); got != (ProxyUsage{Day: "2026-11-05", Wifi: 70}) {
		t.Fatalf("midnight IO lost or assigned to yesterday: %+v", got)
	}
	manager.ResetStatistic()
	if got := manager.proxyUsageAt(boundary); got.Wifi != 70 {
		t.Fatalf("same-day configuration reset erased usage: %+v", got)
	}
}

func TestProxyUsageQueryRollsOverWithoutTraffic(t *testing.T) {
	location := time.FixedZone("UTC+8", 8*60*60)
	before := time.Date(2026, 11, 4, 23, 59, 59, 0, location)
	var manager Manager
	manager.SetTrafficNetwork(TrafficNetworkMobile)
	manager.pushProxyTrafficAt(99, before)
	if got := manager.proxyUsageAt(before.Add(time.Second)); got != (ProxyUsage{Day: "2026-11-05"}) {
		t.Fatalf("idle new-day query kept yesterday's bytes: %+v", got)
	}
}

func TestProxyUsageTimezoneChangesKeepSeparateLocalDates(t *testing.T) {
	east := time.FixedZone("east", 8*60*60)
	west := time.FixedZone("west", -5*60*60)
	instant := time.Date(2026, 11, 4, 20, 0, 0, 0, time.UTC)
	var manager Manager
	manager.SetTrafficNetwork(TrafficNetworkWifi)
	manager.pushProxyTrafficAt(11, instant.In(east))
	manager.SetTrafficNetwork(TrafficNetworkMobile)
	manager.pushProxyTrafficAt(22, instant.In(west))
	if got := manager.proxyUsageAt(instant.In(west)); got != (ProxyUsage{Day: "2026-11-04", Mobile: 22}) {
		t.Fatalf("timezone crossing local date reused another day's bucket: %+v", got)
	}
	if got := manager.proxyUsageAt(instant.In(east)); got != (ProxyUsage{Day: "2026-11-05", Wifi: 11}) {
		t.Fatalf("timezone return lost the original day's bytes: %+v", got)
	}
	eastNine := time.FixedZone("east", 9*60*60)
	manager.pushProxyTrafficAt(3, instant.In(eastNine))
	if got := manager.proxyUsageAt(instant.In(eastNine)); got != (ProxyUsage{Day: "2026-11-05", Wifi: 11, Mobile: 3}) {
		t.Fatalf("same-date timezone change lost prior traffic: %+v", got)
	}
	newMidnight := time.Date(2026, 11, 6, 0, 0, 0, 0, eastNine)
	if got := manager.proxyUsageAt(newMidnight); got != (ProxyUsage{Day: "2026-11-06"}) {
		t.Fatalf("timezone boundary cache did not advance: %+v", got)
	}
}

func TestProxyUsageDSTCalendarBoundaries(t *testing.T) {
	location, err := time.LoadLocation("America/New_York")
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name  string
		month time.Month
		day   int
		hours time.Duration
	}{
		{"spring", time.March, 8, 23},
		{"fall", time.November, 1, 25},
	} {
		t.Run(tc.name, func(t *testing.T) {
			start := time.Date(2026, tc.month, tc.day, 0, 0, 0, 0, location)
			end := start.AddDate(0, 0, 1)
			var manager Manager
			manager.SetTrafficNetwork(TrafficNetworkWifi)
			bucket := manager.proxyBucketAt(start)
			if time.Duration(bucket.end-bucket.start)*time.Second != tc.hours*time.Hour {
				t.Fatalf("DST day has wrong duration: %d", bucket.end-bucket.start)
			}
			manager.pushProxyTrafficAt(8, end.Add(-time.Nanosecond))
			manager.pushProxyTrafficAt(9, end)
			if got := manager.proxyUsageAt(end.Add(-time.Nanosecond)); got.Wifi != 8 {
				t.Fatalf("last old-day byte crossed the DST boundary: %+v", got)
			}
			if got := manager.proxyUsageAt(end); got != (ProxyUsage{Day: end.Format("2006-01-02"), Wifi: 9}) {
				t.Fatalf("first new-day byte stayed in old DST day: %+v", got)
			}
		})
	}
}
