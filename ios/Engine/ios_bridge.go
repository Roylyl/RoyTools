//go:build ios && cgo

package main

/*
#include <stdint.h>
*/
import "C"

import (
	"context"
	"errors"
	"io"
	"runtime/debug"
	"sync"
	"time"
	"unsafe"

	"github.com/metacubex/mihomo/tunnel/statistic"
)

var lastError struct {
	sync.Mutex
	message string
}

func init() {
	// Network extensions have a much smaller memory budget than the main app.
	debug.SetGCPercent(55)
	debug.SetMemoryLimit(42 << 20)
}

func statusCode(err error) C.int32_t {
	lastError.Lock()
	defer lastError.Unlock()
	lastError.message = ""
	if err != nil {
		lastError.message = err.Error()
		return -1
	}
	return 0
}

func bytesAt(ptr *C.uint8_t, length C.int32_t, maximum int) []byte {
	if ptr == nil || length <= 0 || int(length) > maximum {
		return nil
	}
	return unsafe.Slice((*byte)(unsafe.Pointer(ptr)), int(length))
}

//export RTCoreStart
func RTCoreStart(home *C.char, config *C.uint8_t, configSize C.int32_t, mtu C.int32_t) C.int32_t {
	if home == nil {
		return statusCode(errors.New("missing tunnel home"))
	}
	return statusCode(startEngine(C.GoString(home), bytesAt(config, configSize, 32<<20), int(mtu)))
}

//export RTCoreReload
func RTCoreReload(config *C.uint8_t, configSize C.int32_t) C.int32_t {
	return statusCode(reloadEngine(bytesAt(config, configSize, 32<<20)))
}

//export RTCoreStop
func RTCoreStop() C.int32_t {
	stopEngine()
	return statusCode(nil)
}

//export RTCoreWritePacket
func RTCoreWritePacket(packet *C.uint8_t, packetSize C.int32_t) C.int32_t {
	device := machine.device.Load()
	if device == nil {
		return -1
	}
	raw := bytesAt(packet, packetSize, 65535)
	if raw == nil {
		return -2
	}
	// gVisor may retain its packet buffer after this call returns. Copy here
	// so it never holds access to the Swift-owned Data memory.
	if _, err := device.Write(append([]byte(nil), raw...)); err != nil {
		return -2
	}
	return 0
}

//export RTCoreReadPacket
func RTCoreReadPacket(buffer *C.uint8_t, capacity C.int32_t, timeoutMS C.int32_t) C.int32_t {
	device := machine.device.Load()
	if device == nil {
		return -1
	}
	out := bytesAt(buffer, capacity, 65536)
	if out == nil {
		return -2
	}
	var n int
	var err error
	if timeoutMS < 0 {
		n, err = device.Read(out)
	} else {
		wait := time.Duration(min(1000, int(timeoutMS))) * time.Millisecond
		n, err = device.readPacket(out, wait)
	}
	if err == nil {
		return C.int32_t(n)
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return 0
	}
	if errors.Is(err, io.EOF) {
		return -1
	}
	return -2
}

//export RTCoreUploaded
func RTCoreUploaded() C.int64_t {
	upload, _ := statistic.ProxyPayloadTotal()
	return C.int64_t(upload)
}

//export RTCoreDownloaded
func RTCoreDownloaded() C.int64_t {
	_, download := statistic.ProxyPayloadTotal()
	return C.int64_t(download)
}

//export RTCoreSetTrafficNetwork
func RTCoreSetTrafficNetwork(network C.int32_t) {
	statistic.DefaultManager.SetTrafficNetwork(int32(network))
}

//export RTCoreProxyUsage
func RTCoreProxyUsage(wifi, cellular *C.int64_t) {
	wifiBytes, cellularBytes := statistic.ProxyPayloadTransportTotal()
	*wifi = C.int64_t(wifiBytes)
	*cellular = C.int64_t(cellularBytes)
}

//export RTCoreLastError
func RTCoreLastError(buffer *C.char, capacity C.int32_t) C.int32_t {
	lastError.Lock()
	defer lastError.Unlock()
	if buffer == nil || capacity < 1 || capacity > 2<<20 {
		return 0
	}
	dest := unsafe.Slice((*byte)(unsafe.Pointer(buffer)), int(capacity))
	count := copy(dest[:len(dest)-1], lastError.message)
	dest[count] = 0
	return C.int32_t(count)
}

//export RTCoreState
func RTCoreState(buffer *C.char, capacity C.int32_t) C.int32_t {
	data := engineStatus()
	if buffer != nil && capacity > 0 && capacity <= 16<<20 {
		dest := unsafe.Slice((*byte)(unsafe.Pointer(buffer)), int(capacity))
		count := copy(dest[:len(dest)-1], data)
		dest[count] = 0
	}
	return C.int32_t(len(data))
}

//export RTCoreTestProxyLatencies
func RTCoreTestProxyLatencies(request *C.uint8_t, size C.int32_t, output *C.int32_t, count C.int32_t) C.int32_t {
	if output == nil || count < 1 || count > latencyBatchSize {
		return -1
	}
	results := unsafe.Slice((*int32)(unsafe.Pointer(output)), int(count))
	return C.int32_t(measureProxyLatencies(bytesAt(request, size, 64<<10), results))
}
