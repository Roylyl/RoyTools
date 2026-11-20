package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/metacubex/gvisor/pkg/buffer"
	"github.com/metacubex/gvisor/pkg/tcpip"
	"github.com/metacubex/gvisor/pkg/tcpip/adapters/gonet"
	"github.com/metacubex/gvisor/pkg/tcpip/header"
	"github.com/metacubex/gvisor/pkg/tcpip/link/channel"
	"github.com/metacubex/gvisor/pkg/tcpip/stack"
	"github.com/metacubex/mihomo/component/profile/cachefile"
	"github.com/metacubex/mihomo/transport/socks5"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
	tun "github.com/metacubex/sing-tun"
)

var runtimeHome string

func TestMain(m *testing.M) {
	var err error
	runtimeHome, err = os.MkdirTemp("", "roytools-packet-test")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	result := m.Run()
	if _, err := os.Stat(filepath.Join(runtimeHome, "cache.db")); err == nil {
		_ = cachefile.Cache().Close()
	}
	if err := os.RemoveAll(runtimeHome); err != nil && result == 0 {
		fmt.Fprintln(os.Stderr, err)
		result = 1
	}
	os.Exit(result)
}

const testProfile = `
mode: rule
port: 7890
external-controller: 0.0.0.0:9090
proxies:
  - name: Node A
    type: socks5
    server: 127.0.0.1
    port: 20001
  - name: Node B
    type: socks5
    server: 127.0.0.1
    port: 20002
proxy-groups:
  - name: My Group
    type: select
    proxies: [Node A, Node B]
rules:
  - DOMAIN,provider.example.com,My Group
  - MATCH,DIRECT
`

func TestNestedProxySelectionAndLatencyMembership(t *testing.T) {
	yaml := strings.Replace(testProfile, "    proxies: [Node A, Node B]", `    proxies: [Node A, Child Group]
  - name: Child Group
    type: select
    proxies: [Node A, Node B]`, 1)
	request := profileRequest{
		Mode: "default", SubscriptionYAML: yaml, SelectedGroup: "My Group", ProfileID: "example-profile",
		GroupSelections: map[string]string{"My Group": "Child Group", "Child Group": "Node B"},
	}
	data, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	if err := startEngine(runtimeHome, data, 1500); err != nil {
		t.Fatal(err)
	}
	defer stopEngine()
	request.Mode = "global"
	request.GroupSelections = nil // Preserve the manual selectors through a rule or mode reload.
	data, err = json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	if err := reloadEngine(data); err != nil {
		t.Fatal(err)
	}
	var state engineState
	if err := json.Unmarshal(engineStatus(), &state); err != nil {
		t.Fatal(err)
	}
	if state.SelectedGroup != "My Group" || state.SelectedNode != "Child Group" || len(state.Groups) != 2 ||
		state.Groups[1].Current != "Node B" || !state.Groups[1].Selectable || !reflect.DeepEqual(state.ProxyNodes, []string{"Node A", "Node B"}) {
		t.Fatalf("unexpected runtime proxy state: %+v", state)
	}
	result := make([]int32, 1)
	if count := measureProxyLatencies([]byte(`{"group":"My Group","nodes":["Node B"]}`), result); count != 1 || result[0] != -1 {
		t.Fatalf("tested a non-member: length %d, results %v", count, result)
	}
	if count := measureProxyLatencies([]byte(`{"nodes":["Child Group"]}`), result); count != 1 || result[0] != -1 {
		t.Fatalf("tested a group as a raw node: length %d, results %v", count, result)
	}
}

func TestLANProxyFixedPortSurvivesRejectedReload(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(writer, "LAN proxy")
	}))
	defer target.Close()

	reserve := func() *net.TCPListener {
		t.Helper()
		listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4zero})
		if err != nil {
			t.Fatal(err)
		}
		return listener
	}
	fixed := reserve()
	port := fixed.Addr().(*net.TCPAddr).Port
	_ = fixed.Close()

	config := func(port int) []byte {
		data, err := json.Marshal(profileRequest{Mode: "default", SubscriptionYAML: testProfile, LANPort: port})
		if err != nil {
			t.Fatal(err)
		}
		return data
	}
	if err := startEngine(runtimeHome, config(port), 1500); err != nil {
		t.Fatal(err)
	}
	defer stopEngine()

	proxyURL, err := url.Parse(fmt.Sprintf("http://127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	transport := &http.Transport{Proxy: http.ProxyURL(proxyURL), DisableKeepAlives: true}
	client := &http.Client{Transport: transport, Timeout: time.Second * 3}
	checkForwarding := func(client *http.Client) {
		t.Helper()
		response, err := client.Get(target.URL)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		content, err := io.ReadAll(response.Body)
		if err != nil || response.StatusCode != http.StatusOK || string(content) != "LAN proxy" {
			t.Fatalf("unexpected response: status %d, body %q, error %v", response.StatusCode, content, err)
		}
	}
	checkForwarding(client)
	socksURL := *proxyURL
	socksURL.Scheme = "socks5"
	checkForwarding(&http.Client{
		Transport: &http.Transport{Proxy: http.ProxyURL(&socksURL), DisableKeepAlives: true},
		Timeout:   time.Second * 3,
	})
	occupied := reserve()
	defer occupied.Close()
	if err := reloadEngine(config(occupied.Addr().(*net.TCPAddr).Port)); err == nil {
		t.Fatal("reload accepted an occupied LAN port")
	}
	checkForwarding(client)
	if err := reloadEngine(config(0)); err != nil {
		t.Fatal(err)
	}
	if response, err := client.Get(target.URL); err == nil {
		response.Body.Close()
		t.Fatal("LAN port still accepted a connection after it was disabled")
	}
}

func TestGlobalAndDirectUseMihomoModes(t *testing.T) {
	request := profileRequest{
		Mode: "global", SubscriptionYAML: testProfile, SelectedGroup: "My Group", SelectedNode: "Node B",
	}
	encode := func(request profileRequest) []byte {
		t.Helper()
		data, err := json.Marshal(request)
		if err != nil {
			t.Fatal(err)
		}
		return data
	}
	if err := startEngine(runtimeHome, encode(request), 1500); err != nil {
		t.Fatal(err)
	}
	defer stopEngine()

	checkGlobal := func() {
		t.Helper()
		if tunnel.Mode() != tunnel.Global {
			t.Fatalf("expected global mode, got %v", tunnel.Mode())
		}
		global := proxyGroup("GLOBAL")
		group := proxyGroup("My Group")
		if global == nil || global.Now() != "DIRECT" || tunnel.Proxies()["GLOBAL"] == tunnel.Proxies()["My Group"] {
			t.Fatal("app global mode changed the subscription GLOBAL group")
		}
		if group == nil || group.Now() != "Node B" {
			t.Fatal("selected group did not retain Node B")
		}
	}
	checkGlobal()

	request.Mode = "direct"
	request.SelectedNode = ""
	if err := reloadEngine(encode(request)); err != nil {
		t.Fatal(err)
	}
	if tunnel.Mode() != tunnel.Direct {
		t.Fatalf("expected direct mode, got %v", tunnel.Mode())
	}
	request.Mode = "global"
	if err := reloadEngine(encode(request)); err != nil {
		t.Fatal(err)
	}
	checkGlobal()
}

func TestAppGlobalUsesProxyWhenSubscriptionGlobalIsDirect(t *testing.T) {
	origin := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(writer, "direct")
	}))
	defer origin.Close()
	relayPort, relayResult := startTestSOCKSRelay(t, "relayed", origin.Listener.Addr().String())
	request := profileRequest{
		Mode: "default", ProfileID: "global-direct", SelectedGroup: "Preferred",
		SubscriptionYAML: fmt.Sprintf(`
mode: global
proxies:
  - {name: Local relay, type: socks5, server: 127.0.0.1, port: %d}
proxy-groups:
  - {name: GLOBAL, type: select, proxies: [DIRECT, Preferred]}
  - {name: Preferred, type: select, proxies: [DIRECT, Local relay]}
rules:
  - MATCH,DIRECT
`, relayPort),
	}
	encode := func() []byte {
		t.Helper()
		data, err := json.Marshal(request)
		if err != nil {
			t.Fatal(err)
		}
		return data
	}
	if err := startEngine(runtimeHome, encode(), 1500); err != nil {
		t.Fatal(err)
	}
	defer stopEngine()
	client := testRoutingHTTPClient(t)
	requireRoutingResponse(t, client, origin.URL, "direct")
	request.Mode = "global"
	if err := reloadEngine(encode()); err != nil {
		t.Fatal(err)
	}
	requireRoutingResponse(t, client, origin.URL, "relayed")
	if err := <-relayResult; err != nil {
		t.Fatal(err)
	}
	request.Mode = "default"
	if err := reloadEngine(encode()); err != nil {
		t.Fatal(err)
	}
	requireRoutingResponse(t, client, origin.URL, "direct")
}

func TestRepositoryModesKeepSubscriptionRulesAndGroups(t *testing.T) {
	type rulesSection struct {
		Rules     []string          `json:"rules"`
		Providers map[string]string `json:"providers"`
	}
	var repository struct {
		Version string       `json:"version"`
		AI      rulesSection `json:"ai"`
		Rule    rulesSection `json:"rule"`
	}
	data, err := os.ReadFile("../../routing-rules.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &repository); err != nil {
		t.Fatal(err)
	}
	subscription := testProfile + `
sub-rules:
  provider-sub-rules:
    - DOMAIN,custom.example.com,My Group
rule-providers:
  subscription-domains:
    type: file
    behavior: domain
    path: ./provider-domains.yaml
`
	home := t.TempDir()
	for _, mode := range []string{"default", "rule", "ai", "music"} {
		t.Run(mode, func(t *testing.T) {
			request := profileRequest{Mode: mode, SubscriptionYAML: subscription, SelectedGroup: "My Group",
				SelectedNode: "Node B", RulesVersion: repository.Version,
				MusicDomains: []string{"roylyl.github.io"},
			}
			switch mode {
			case "ai":
				request.Rules, request.Providers = repository.AI.Rules, repository.AI.Providers
			case "rule":
				request.Rules, request.Providers = repository.Rule.Rules, repository.Rule.Providers
			}
			config, selected, err := composeProfile(home, request)
			if err != nil {
				t.Fatal(err)
			}
			if selected.target != "My Group" {
				t.Fatalf("selected target: %q", selected.target)
			}
			if config.Port != 0 || config.ExternalController != "" {
				t.Fatal("subscription configured an external port")
			}
			if !reflect.DeepEqual(config.Rule, []string{"DOMAIN,provider.example.com,My Group", "MATCH,DIRECT"}) {
				t.Fatalf("subscription rules changed: %v", config.Rule)
			}
			if !reflect.DeepEqual(config.SubRules["provider-sub-rules"], []string{"DOMAIN,custom.example.com,My Group"}) {
				t.Fatalf("subscription sub-rules changed: %v", config.SubRules)
			}
			if config.RuleProvider["subscription-domains"]["path"] != "./provider-domains.yaml" {
				t.Fatalf("subscription rule provider changed: %v", config.RuleProvider)
			}
			switch mode {
			case "default":
				if len(config.RoyToolsProxyRules) != 0 || config.RoyToolsFallback != "" {
					t.Fatal("default mode unexpectedly applied a RoyTools preset")
				}
			case "music":
				if !reflect.DeepEqual(config.RoyToolsProxyRules, []string{
					"AND,((IN-NAME,roytools-music),(DOMAIN-SUFFIX,roylyl.github.io)),GLOBAL",
					"MATCH,DIRECT",
				}) {
					t.Fatal("music routing was not scoped")
				}
			case "ai", "rule":
				if len(config.RoyToolsProxyRules) != len(request.Rules) || len(config.RuleProvider) != len(request.Providers)+1 {
					t.Fatal("repository rules or providers were discarded or replaced subscription providers")
				}
				for name, provider := range config.RuleProvider {
					if name == "subscription-domains" {
						continue
					}
					path, ok := provider["path"].(string)
					if !ok {
						t.Fatalf("rule provider %q is missing a path", name)
					}
					if _, err := os.Stat(filepath.Join(home, path)); err != nil {
						t.Fatal(err)
					}
				}
				wantFallback := "MATCH,GLOBAL"
				if mode == "ai" {
					wantFallback = "MATCH,DIRECT"
				}
				if config.RoyToolsProxyRules[len(config.RoyToolsProxyRules)-1] != wantFallback {
					t.Fatalf("wrong preset fallback, expected %s", wantFallback)
				}
			}
		})
	}
}

func TestPresetRoutingUsesSubscriptionGroups(t *testing.T) {
	const audio = "audio.preset.example.test"
	const video = "video.preset.example.test"
	portA, resultA := startTestSOCKSRelay(t, "relay A", video+":80", audio+":80")
	portB, resultB := startTestSOCKSRelay(t, "relay B", audio+":80", video+":80")
	request := profileRequest{
		Mode: "ai", ProfileID: "multi-group", RulesVersion: "1.0.0",
		GroupSelections: map[string]string{"Audio Group": "Relay B", "Video Group": "Relay A"},
		Rules:           []string{"DOMAIN-SUFFIX,preset.example.test,PROXY", "MATCH,DIRECT"},
		SubscriptionYAML: fmt.Sprintf(`
mode: rule
dns:
  enable: true
  nameserver: ["127.0.0.1:9"]
proxies:
  - {name: Relay A, type: socks5, server: 127.0.0.1, port: %d}
  - {name: Relay B, type: socks5, server: 127.0.0.1, port: %d}
proxy-groups:
  - {name: Audio Group, type: select, proxies: [Relay A, Relay B]}
  - {name: Video Group, type: select, proxies: [Relay B, Relay A]}
sub-rules:
  services:
    - DOMAIN,%s,Audio Group
    - DOMAIN,%s,Video Group
rules:
  - SUB-RULE,(DOMAIN-SUFFIX,preset.example.test),services
  - IP-CIDR,127.0.0.1/32,Audio Group,no-resolve
  - MATCH,DIRECT
`, portA, portB, audio, video),
	}
	encode := func() []byte {
		t.Helper()
		data, err := json.Marshal(request)
		if err != nil {
			t.Fatal(err)
		}
		return data
	}
	if err := startEngine(runtimeHome, encode(), 1500); err != nil {
		t.Fatal(err)
	}
	defer stopEngine()

	client := testRoutingHTTPClient(t)
	requireRoutingResponse(t, client, "http://"+audio+"/", "relay B")
	requireRoutingResponse(t, client, "http://"+video+"/", "relay A")

	direct := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(writer, "direct")
	}))
	defer direct.Close()
	requireRoutingResponse(t, client, direct.URL, "direct")

	request.GroupSelections = map[string]string{}
	if err := reloadEngine(encode()); err != nil {
		t.Fatal(err)
	}
	requireRoutingResponse(t, client, "http://"+audio+"/", "relay A")
	requireRoutingResponse(t, client, "http://"+video+"/", "relay B")
	for _, result := range []<-chan error{resultA, resultB} {
		if err := <-result; err != nil {
			t.Fatal(err)
		}
	}
}

func testRoutingHTTPClient(t *testing.T) *http.Client {
	t.Helper()
	ingress, err := makeMusicListener()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ingress.listener.Close() })
	endpoint := ingress.endpoint
	proxyURL := &url.URL{
		Scheme: "http", Host: net.JoinHostPort(endpoint.Host, fmt.Sprint(endpoint.Port)),
		User: url.UserPassword(endpoint.Username, endpoint.Password),
	}
	return &http.Client{
		Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL), DisableKeepAlives: true},
		Timeout:   3 * time.Second,
	}
}

func requireRoutingResponse(t *testing.T, client *http.Client, address, want string) {
	t.Helper()
	response, err := client.Get(address)
	if err != nil {
		t.Fatalf("request to %s: %v", address, err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil || response.StatusCode != http.StatusOK || string(body) != want {
		t.Fatalf("request to %s: status %d, body %q, error %v, want %q", address, response.StatusCode, body, err, want)
	}
}

func TestPacketDeviceBlockingReadUnblocksOnStop(t *testing.T) {
	device := newPacketDevice(1500)
	defer device.Close()
	result := make(chan error, 1)
	go func() {
		_, err := device.Read(make([]byte, 1500))
		result <- err
	}()
	select {
	case err := <-result:
		t.Fatalf("idle packet read returned before stop: %v", err)
	case <-time.After(20 * time.Millisecond):
	}
	device.cancel()
	select {
	case err := <-result:
		if !errors.Is(err, io.EOF) {
			t.Fatalf("stopped packet read returned %v, want EOF", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("stopping the packet device did not wake the reader")
	}
}

func TestTunnelCarriesTCPAndReloadDoesNotReplacePacketDevice(t *testing.T) {
	payload, _ := json.Marshal(profileRequest{
		Mode: "default", SubscriptionYAML: testProfile, SelectedGroup: "My Group", SelectedNode: "Node B",
	})
	if err := startEngine(runtimeHome, payload, 1500); err != nil {
		t.Fatal(err)
	}
	defer stopEngine()
	device := machine.device.Load()
	updated, _ := json.Marshal(profileRequest{
		Mode: "ai", SubscriptionYAML: testProfile, RulesVersion: "3.1.5",
		Rules: []string{"DOMAIN,ai.example.com,PROXY", "MATCH,DIRECT"}, SelectedGroup: "My Group",
	})
	if err := reloadEngine(updated); err != nil {
		t.Fatal(err)
	}
	if device == nil || machine.device.Load() != device {
		t.Fatal("hot reload replaced the active packet device")
	}
	var selected engineState
	if err := json.Unmarshal(engineStatus(), &selected); err != nil || selected.SelectedNode != "Node B" {
		t.Fatalf("hot reload lost the selected node: %q; %v", selected.SelectedNode, err)
	}

	host := localTestAddress(t)
	listener, err := net.Listen("tcp4", net.JoinHostPort(host.String(), "0"))
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		if conn, err := listener.Accept(); err == nil {
			defer conn.Close()
			_, _ = io.WriteString(conn, "roytools-packet-test")
		}
	}()
	port := listener.Addr().(*net.TCPAddr).Port
	link := channel.New(32, 1500, "")
	client, err := tun.NewGVisorStack(link)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	defer link.Close()
	clientAddress := tcpip.AddrFrom4([4]byte{198, 18, 0, 1})
	if err := client.AddProtocolAddress(tun.DefaultNIC, tcpip.ProtocolAddress{
		Protocol:          header.IPv4ProtocolNumber,
		AddressWithPrefix: tcpip.AddressWithPrefix{Address: clientAddress, PrefixLen: 30},
	}, stack.AddressProperties{}); err != nil {
		t.Fatal(err.String())
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	go func() {
		for {
			pkt := link.ReadContext(ctx)
			if pkt == nil {
				return
			}
			body := pkt.ToView()
			_, _ = device.Write(append([]byte(nil), body.AsSlice()...))
			body.Release()
			pkt.DecRef()
		}
	}()
	go func() {
		out := make([]byte, 1600)
		for {
			n, err := device.read(ctx, out)
			if err != nil {
				return
			}
			pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
				Payload: buffer.MakeWithData(append([]byte(nil), out[:n]...)),
			})
			link.InjectInbound(header.IPv4ProtocolNumber, pkt)
			pkt.DecRef()
		}
	}()
	conn, err := gonet.DialContextTCP(ctx, client, tcpip.FullAddress{
		NIC: tun.DefaultNIC, Addr: tcpip.AddrFromSlice(host.To4()), Port: uint16(port),
	}, header.IPv4ProtocolNumber)
	if err != nil {
		t.Fatal(fmt.Errorf("dial through gVisor/Mihomo: %w", err))
	}
	defer conn.Close()
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	result, err := io.ReadAll(conn)
	if err != nil || string(result) != "roytools-packet-test" {
		t.Fatalf("packet flow result=%q error=%v", result, err)
	}
}

func localTestAddress(t *testing.T) net.IP {
	t.Helper()
	addresses, err := net.InterfaceAddrs()
	if err != nil {
		t.Fatal(err)
	}
	for _, address := range addresses {
		ipNet, ok := address.(*net.IPNet)
		if ok && ipNet.IP.To4() != nil && ipNet.IP.IsPrivate() {
			return ipNet.IP
		}
	}
	t.Skip("a non-loopback IPv4 host address is required")
	return nil
}

func TestAuthenticatedMusicCONNECTReachesSelectedProxy(t *testing.T) {
	port, relayResult := startTestSOCKSRelay(t, "roytools-music-test", "music.example.test:80")
	profile := fmt.Sprintf(`
proxies:
  - name: Local relay
    type: socks5
    server: 127.0.0.1
    port: %d
proxy-groups:
  - name: Preferred
    type: select
    proxies: [Local relay]
`, port)
	request, _ := json.Marshal(profileRequest{
		Mode: "music", SubscriptionYAML: profile, SelectedGroup: "Preferred", SelectedNode: "Local relay",
		MusicDomains: []string{"music.example.test"},
	})
	if err := startEngine(runtimeHome, request, 1500); err != nil {
		t.Fatal(err)
	}
	defer stopEngine()
	var status engineState
	if err := json.Unmarshal(engineStatus(), &status); err != nil || status.MusicProxy == nil {
		t.Fatalf("no music listener: %v", err)
	}
	ingress := status.MusicProxy
	unauthenticated, err := musicCONNECT(ingress, false)
	if err != nil {
		t.Fatal(err)
	}
	unauthenticated.Close()
	up, down := statistic.ProxyPayloadTotal()
	connection, err := musicCONNECT(ingress, true)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if _, err := io.WriteString(connection, "GET / HTTP/1.1\r\nHost: music.example.test\r\nConnection: close\r\n\r\n"); err != nil {
		t.Fatal(err)
	}
	response, err := http.ReadResponse(bufio.NewReader(connection), nil)
	if err != nil {
		t.Fatal(err)
	}
	body, err := io.ReadAll(response.Body)
	response.Body.Close()
	if err != nil || string(body) != "roytools-music-test" {
		t.Fatalf("music relay body %q: %v", body, err)
	}
	if err := <-relayResult; err != nil {
		t.Fatal(err)
	}
	nowUp, nowDown := statistic.ProxyPayloadTotal()
	if nowUp <= up || nowDown <= down {
		t.Fatalf("proxy payload counters did not increase: (%d,%d) -> (%d,%d)", up, down, nowUp, nowDown)
	}
}

func musicCONNECT(ingress *musicEndpoint, authenticated bool) (net.Conn, error) {
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(ingress.Host, fmt.Sprint(ingress.Port)), 2*time.Second)
	if err != nil {
		return nil, err
	}
	deadline := time.Now().Add(4 * time.Second)
	_ = conn.SetDeadline(deadline)
	host := "music.example.test:80"
	request := &http.Request{
		Method: http.MethodConnect,
		URL:    &url.URL{Host: host},
		Host:   host,
		Header: make(http.Header),
	}
	if authenticated {
		request.SetBasicAuth(ingress.Username, ingress.Password)
		request.Header["Proxy-Authorization"] = request.Header["Authorization"]
		request.Header.Del("Authorization")
	}
	if err := request.Write(conn); err != nil {
		conn.Close()
		return nil, err
	}
	response, err := http.ReadResponse(bufio.NewReader(conn), request)
	if err != nil {
		conn.Close()
		return nil, err
	}
	want := http.StatusProxyAuthRequired
	if authenticated {
		want = http.StatusOK
	}
	if response.StatusCode != want {
		conn.Close()
		return nil, fmt.Errorf("music listener replied %d, want %d", response.StatusCode, want)
	}
	return conn, nil
}

func startTestSOCKSRelay(t *testing.T, body string, addresses ...string) (int, <-chan error) {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	result := make(chan error, 1)
	go func() {
		for _, address := range addresses {
			conn, err := listener.Accept()
			if err != nil {
				result <- err
				return
			}
			_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
			err = serveTestSOCKSRelay(conn, address, body)
			_ = conn.Close()
			if err != nil {
				result <- err
				return
			}
		}
		result <- nil
	}()
	return listener.Addr().(*net.TCPAddr).Port, result
}

func serveTestSOCKSRelay(conn net.Conn, address, body string) error {
	target, command, _, err := socks5.ServerHandshake(conn, nil)
	if err != nil {
		return err
	}
	if command != socks5.CmdConnect || target.String() != address {
		return fmt.Errorf("SOCKS command %d for %q, want CONNECT to %q", command, target.String(), address)
	}
	req, err := http.ReadRequest(bufio.NewReader(conn))
	if err != nil {
		return err
	}
	req.Body.Close()
	if host, _, _ := net.SplitHostPort(address); !strings.EqualFold((&url.URL{Host: req.Host}).Hostname(), host) {
		return fmt.Errorf("HTTP host %q, want %q", req.Host, host)
	}
	_, err = fmt.Fprintf(conn, "HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", len(body), body)
	return err
}
