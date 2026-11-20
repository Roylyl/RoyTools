package main

import (
	"context"
	"encoding/json"
	"slices"
	"sync"
	"time"

	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel"
)

const latencyBatchSize = 12

var latencySlots = make(chan struct{}, 6)

type proxyLatencyRequest struct {
	Group *string  `json:"group"`
	Nodes []string `json:"nodes"`
}

func measureProxyLatencies(input []byte, results []int32) int {
	var request proxyLatencyRequest
	if json.Unmarshal(input, &request) != nil || len(request.Nodes) == 0 ||
		len(request.Nodes) > latencyBatchSize || len(results) != len(request.Nodes) {
		return -1
	}

	machine.Lock()
	device := machine.device.Load()
	if device == nil {
		machine.Unlock()
		return -1
	}
	members := make(map[string]C.Proxy)
	if request.Group == nil {
		for _, name := range request.Nodes {
			if slices.Contains(machine.proxyNodes, name) {
				members[name] = tunnel.Proxies()[name]
			}
		}
	} else {
		group := proxyGroup(*request.Group)
		if group == nil || !slices.Contains(machine.groupOrder, *request.Group) {
			machine.Unlock()
			return -1
		}
		for _, proxy := range group.Proxies() {
			members[proxy.Name()] = proxy
		}
	}
	machine.Unlock()

	ctx, cancel := context.WithTimeout(device.ctx, 8*time.Second)
	defer cancel()
	var tests sync.WaitGroup
	for index, name := range request.Nodes {
		results[index] = -1
		proxy := members[name]
		if proxy == nil {
			continue
		}
		tests.Go(func() {
			select {
			case latencySlots <- struct{}{}:
				defer func() { <-latencySlots }()
			case <-ctx.Done():
				return
			}
			testContext, stop := context.WithTimeout(ctx, 4*time.Second)
			defer stop()
			if delay, err := proxy.URLTest(testContext, C.DefaultTestURL, nil); err == nil {
				results[index] = int32(delay)
			}
		})
	}
	tests.Wait()
	return len(results)
}
