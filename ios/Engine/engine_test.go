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
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
	tun "github.com/metacubex/sing-tun"
	"gopkg.in/yaml.v3"
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
		if global == nil || global.Now() != "Node B" || tunnel.Proxies()["GLOBAL"] != tunnel.Proxies()["My Group"] {
			t.Fatal("global mode did not use the selected group and node")
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

func TestRepositoryModesUseSubscriptionSelection(t *testing.T) {
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
	home := t.TempDir()
	for _, mode := range []string{"default", "rule", "ai", "music"} {
		t.Run(mode, func(t *testing.T) {
			request := profileRequest{Mode: mode, SubscriptionYAML: testProfile, SelectedGroup: "My Group",
				SelectedNode: "Node B", RulesVersion: repository.Version,
				MusicDomains: []string{"roylyl.github.io"},
			}
			switch mode {
			case "ai":
				request.Rules, request.Providers = repository.AI.Rules, repository.AI.Providers
			case "rule":
				request.Rules, request.Providers = repository.Rule.Rules, repository.Rule.Providers
			}
			yamlConfig, selected, err := composeProfile(home, request)
			if err != nil {
				t.Fatal(err)
			}
			if selected.target != "My Group" {
				t.Fatalf("selected target: %q", selected.target)
			}
			var config struct {
				Port             int      `yaml:"port"`
				ExternalControl  string   `yaml:"external-controller"`
				Rules            []string `yaml:"rules"`
				ProviderMappings map[string]struct {
					Path string `yaml:"path"`
				} `yaml:"rule-providers"`
			}
			if err = yaml.Unmarshal(yamlConfig, &config); err != nil {
				t.Fatal(err)
			}
			if config.Port != 0 || config.ExternalControl != "" {
				t.Fatal("subscription configured an external port")
			}
			switch mode {
			case "default":
				if !reflect.DeepEqual(config.Rules, []string{"DOMAIN,provider.example.com,My Group", "MATCH,DIRECT"}) {
					t.Fatal("subscription rules did not survive default mode")
				}
			case "music":
				if !reflect.DeepEqual(config.Rules, []string{
					"AND,((IN-NAME,roytools-music),(DOMAIN-SUFFIX,roylyl.github.io)),My Group",
					"MATCH,DIRECT",
				}) {
					t.Fatal("music routing was not scoped")
				}
			case "ai", "rule":
				if len(config.Rules) != len(request.Rules) || len(request.Providers) != len(config.ProviderMappings) {
					t.Fatal("repository rules or providers were discarded")
				}
				for _, provider := range config.ProviderMappings {
					if _, err := os.Stat(filepath.Join(home, provider.Path)); err != nil {
						t.Fatal(err)
					}
				}
				if mode == "ai" && config.Rules[len(config.Rules)-1] != "MATCH,DIRECT" {
					t.Fatal("AI fallback changed")
				}
			}
		})
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
	relay, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	relayResult := make(chan error, 1)
	go func() {
		conn, err := relay.Accept()
		if err != nil {
			relayResult <- err
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
		relayResult <- serveMusicTestRelay(conn)
	}()
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
`, relay.Addr().(*net.TCPAddr).Port)
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

func serveMusicTestRelay(conn net.Conn) error {
	head := make([]byte, 4)
	if _, err := io.ReadFull(conn, head[:2]); err != nil {
		return err
	}
	methods := make([]byte, int(head[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return err
	}
	if _, err := conn.Write([]byte{5, 0}); err != nil {
		return err
	}
	if _, err := io.ReadFull(conn, head); err != nil {
		return err
	}
	if head[0] != 5 || head[1] != 1 || head[3] != 3 {
		return fmt.Errorf("unexpected SOCKS CONNECT header %v", head)
	}
	if _, err := io.ReadFull(conn, head[:1]); err != nil {
		return err
	}
	target := make([]byte, int(head[0])+2)
	if _, err := io.ReadFull(conn, target); err != nil {
		return err
	}
	if string(target[:len(target)-2]) != "music.example.test" {
		return errorsForTarget(target)
	}
	if _, err := conn.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}); err != nil {
		return err
	}
	req, err := http.ReadRequest(bufio.NewReader(conn))
	if err != nil {
		return err
	}
	req.Body.Close()
	if !strings.EqualFold(req.Host, "music.example.test") {
		return fmt.Errorf("wrong music HTTP host %s", req.Host)
	}
	_, err = io.WriteString(conn, "HTTP/1.1 200 OK\r\nContent-Length: 19\r\nConnection: close\r\n\r\nroytools-music-test")
	return err
}

func errorsForTarget(target []byte) error {
	return fmt.Errorf("wrong music SOCKS target %q", target[:len(target)-2])
}
