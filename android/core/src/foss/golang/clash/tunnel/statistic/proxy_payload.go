//go:build roytools_packet_engine

package statistic

import "sync/atomic"

var (
	proxyUploads   atomic.Int64
	proxyDownloads atomic.Int64
)

func trackProxyPayload(size int64, upload bool) {
	if size <= 0 {
		return
	}
	if upload {
		proxyUploads.Add(size)
	} else {
		proxyDownloads.Add(size)
	}
}

// ProxyPayloadTotal excludes traffic routed to direct and reject adapters.
func ProxyPayloadTotal() (upload int64, download int64) {
	return proxyUploads.Load(), proxyDownloads.Load()
}
