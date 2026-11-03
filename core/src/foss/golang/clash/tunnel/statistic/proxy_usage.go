package statistic

import (
	"strings"
	"time"

	"github.com/metacubex/mihomo/common/atomic"
	N "github.com/metacubex/mihomo/common/net"
	C "github.com/metacubex/mihomo/constant"
)

const (
	TrafficNetworkOther int32 = iota
	TrafficNetworkWifi
	TrafficNetworkMobile
)

// ProxyUsage is this local calendar day's payload bytes in the current process.
// ResetStatistic leaves the daily counters intact; Android persists per-day deltas.
type ProxyUsage struct {
	Day    string `json:"day"`
	Wifi   int64  `json:"wifi"`
	Mobile int64  `json:"mobile"`
}

type proxyUsageTotals struct {
	wifi   atomic.Int64
	mobile atomic.Int64
}

type proxyUsageBucket struct {
	day      string
	start    int64
	end      int64
	location *time.Location
	totals   *proxyUsageTotals
}

func (bucket *proxyUsageBucket) contains(now time.Time) bool {
	stamp := now.Unix()
	return bucket.location == now.Location() && stamp >= bucket.start && stamp < bucket.end
}

// The hot path only compares an atomic pointer, location and two Unix boundaries.
// Day formatting and allocation happen on rollover/timezone changes, not per IO.
func (m *Manager) proxyBucketAt(now time.Time) *proxyUsageBucket {
	if bucket := m.proxyUsageBucket.Load(); bucket != nil && bucket.contains(now) {
		return bucket
	}
	m.proxyUsageMu.Lock()
	defer m.proxyUsageMu.Unlock()
	if bucket := m.proxyUsageBucket.Load(); bucket != nil && bucket.contains(now) {
		return bucket
	}
	year, month, day := now.Date()
	start := time.Date(year, month, day, 0, 0, 0, 0, now.Location())
	name := start.Format("2006-01-02")
	if m.proxyUsageDays == nil {
		m.proxyUsageDays = make(map[string]*proxyUsageTotals)
	}
	totals := m.proxyUsageDays[name]
	if totals == nil {
		totals = &proxyUsageTotals{}
		m.proxyUsageDays[name] = totals
	}
	// Keep each day's counters separate. An IO captured just before midnight may
	// finish after a newer IO; it must not wipe the newer day's accumulated bytes.
	bucket := &proxyUsageBucket{
		day: name, start: start.Unix(), end: start.AddDate(0, 0, 1).Unix(),
		location: now.Location(), totals: totals,
	}
	m.proxyUsageBucket.Store(bucket)
	return bucket
}

func (m *Manager) SetTrafficNetwork(network int32) {
	if network != TrafficNetworkWifi && network != TrafficNetworkMobile {
		network = TrafficNetworkOther
	}
	m.trafficNetwork.Store(network)
}

func (m *Manager) ProxyUsage() ProxyUsage {
	return m.proxyUsageAt(time.Now())
}

func (m *Manager) proxyUsageAt(now time.Time) ProxyUsage {
	bucket := m.proxyBucketAt(now)
	return ProxyUsage{Day: bucket.day, Wifi: bucket.totals.wifi.Load(), Mobile: bucket.totals.mobile.Load()}
}

func (m *Manager) pushProxyTraffic(size int64) {
	m.pushProxyTrafficAt(size, time.Now())
}

func (m *Manager) pushProxyTrafficAt(size int64, now time.Time) {
	if size <= 0 {
		return
	}
	switch m.trafficNetwork.Load() {
	case TrafficNetworkWifi:
		m.proxyBucketAt(now).totals.wifi.Add(size)
	case TrafficNetworkMobile:
		m.proxyBucketAt(now).totals.mobile.Add(size)
	}
}

type trafficAdapter interface {
	TrafficAdapterType() C.AdapterType
}

func isProxyConnection(connection C.Connection) bool {
	if adapter, ok := N.FindUpstream[trafficAdapter](connection, nil); ok {
		switch adapter.TrafficAdapterType() {
		case C.Direct, C.Reject, C.RejectDrop, C.Pass, C.PassRule, C.Rematch, C.Dns, C.Compatible:
			return false
		default:
			return true
		}
	}
	// Older wrappers may not expose adapter metadata. Chains.Last is the leaf at [0],
	// never the outer policy group at the end of the slice.
	switch strings.ToUpper(connection.Chains().Last()) {
	case "", "DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "DNS":
		return false
	default:
		return true
	}
}
