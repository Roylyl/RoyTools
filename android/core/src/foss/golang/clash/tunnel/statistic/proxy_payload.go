//go:build roytools_packet_engine

package statistic

import "sync/atomic"

var (
	proxyUploads   atomic.Int64
	proxyDownloads atomic.Int64
	proxyWiFi      atomic.Int64
	proxyCellular  atomic.Int64
)

func trackProxyPayload(size int64, upload bool, network int32) {
	if size <= 0 {
		return
	}
	if upload {
		proxyUploads.Add(size)
	} else {
		proxyDownloads.Add(size)
	}
	switch network {
	case TrafficNetworkWifi:
		proxyWiFi.Add(size)
	case TrafficNetworkMobile:
		proxyCellular.Add(size)
	}
}

// ProxyPayloadTotal excludes traffic routed to direct and reject adapters.
func ProxyPayloadTotal() (upload int64, download int64) {
	return proxyUploads.Load(), proxyDownloads.Load()
}

func ProxyPayloadTransportTotal() (wifi int64, cellular int64) {
	return proxyWiFi.Load(), proxyCellular.Load()
}
