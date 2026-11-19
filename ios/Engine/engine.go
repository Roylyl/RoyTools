package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"sync/atomic"

	"github.com/metacubex/mihomo/adapter/inbound"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	coreauth "github.com/metacubex/mihomo/component/auth"
	"github.com/metacubex/mihomo/component/resolver"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub/executor"
	listenerauth "github.com/metacubex/mihomo/listener/auth"
	LC "github.com/metacubex/mihomo/listener/config"
	proxyhttp "github.com/metacubex/mihomo/listener/http"
	"github.com/metacubex/mihomo/listener/sing"
	"github.com/metacubex/mihomo/listener/sing_tun"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
	tun "github.com/metacubex/sing-tun"
)

var (
	ipv4 = netip.MustParsePrefix("198.18.0.1/30")
	ipv6 = netip.MustParsePrefix("fd00:726f:7974::1/64")
	dns4 = netip.MustParseAddrPort("198.18.0.2:53")
	dns6 = netip.MustParseAddrPort("[fd00:726f:7974::2]:53")

	machine struct {
		sync.Mutex
		home       string
		stack      tun.Stack
		device     atomic.Pointer[packetDevice]
		music      *musicListener
		groupOrder []string
		selected   string
	}
)

type musicEndpoint struct {
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Username string `json:"username"`
	Password string `json:"password"`
}

type musicListener struct {
	listener *proxyhttp.Listener
	endpoint musicEndpoint
}

type groupState struct {
	Name    string   `json:"name"`
	Nodes   []string `json:"nodes"`
	Current string   `json:"current,omitempty"`
}

type engineState struct {
	Groups        []groupState   `json:"groups"`
	SelectedGroup string         `json:"selectedGroup,omitempty"`
	SelectedNode  string         `json:"selectedNode,omitempty"`
	MusicProxy    *musicEndpoint `json:"musicProxy,omitempty"`
}

func startEngine(home string, input []byte, mtu int) error {
	machine.Lock()
	defer machine.Unlock()
	if machine.device.Load() != nil {
		return errors.New("proxy engine is already running")
	}
	if home == "" || mtu < 1280 || mtu > 9000 {
		return errors.New("invalid tunnel path or MTU")
	}
	if err := os.MkdirAll(home, 0700); err != nil {
		return err
	}
	machine.home = home
	C.SetHomeDir(home)
	C.SetConfig(filepath.Join(home, "config.yaml"))
	resolver.AddSystemDnsBlacklist(dns4.Addr().String(), dns6.Addr().String())
	if err := applyProfile(input); err != nil {
		return err
	}
	device := newPacketDevice(uint32(mtu))
	dnsHandler, err := sing.NewListenerHandler(sing.ListenerConfig{
		Tunnel: tunnel.Tunnel, Type: C.TUN,
	})
	if err != nil {
		device.Close()
		stopCore()
		return err
	}
	handler := &sing_tun.ListenerHandler{
		ListenerHandler:       dnsHandler,
		Inet4Address:          []netip.Prefix{ipv4},
		Inet6Address:          []netip.Prefix{ipv6},
		DnsAddrPorts:          []netip.AddrPort{dns4, dns6},
		DisableICMPForwarding: true,
	}
	netstack, err := tun.NewStack("gvisor", tun.StackOptions{
		Context: device.ctx,
		Tun:     device,
		TunOptions: tun.Options{
			MTU: uint32(mtu), Inet4Address: []netip.Prefix{ipv4}, Inet6Address: []netip.Prefix{ipv6},
		},
		UDPTimeout: sing.UDPTimeout, ICMPTimeout: sing.ICMPTimeout,
		Handler: handler, Logger: log.SingLogger,
	})
	if err != nil {
		device.Close()
		stopCore()
		return err
	}
	if err = netstack.Start(); err != nil {
		netstack.Close()
		device.Close()
		stopCore()
		return err
	}
	machine.stack = netstack
	machine.device.Store(device)
	return nil
}

func reloadEngine(input []byte) error {
	machine.Lock()
	defer machine.Unlock()
	if machine.device.Load() == nil {
		return errors.New("proxy engine is not running")
	}
	return applyProfile(input)
}

func applyProfile(input []byte) error {
	request, err := decodeProfile(input)
	if err != nil {
		return err
	}
	yaml, selection, err := composeProfile(machine.home, request)
	if err != nil {
		return err
	}
	cfg, err := executor.ParseWithBytes(yaml)
	if err != nil {
		// Preserve context without returning subscription secrets, URLs or node auth.
		return errors.New("Mihomo could not load the subscription or generated rules")
	}
	for name, provider := range cfg.RuleProviders {
		if len(name) >= len("roytools-") && name[:len("roytools-")] == "roytools-" {
			if err := provider.Initial(); err != nil {
				return fmt.Errorf("cannot load repository rule provider %q", name)
			}
		}
	}
	music := machine.music
	if request.Mode == "music" && music == nil {
		music, err = makeMusicListener()
		if err != nil {
			return err
		}
	}
	// Capture selections before swapping the config; restore them if the
	// group and node still exist in the incoming subscription.
	previous := make(map[string]string)
	for _, name := range machine.groupOrder {
		if group := proxyGroup(name); group != nil {
			previous[name] = group.Now()
		}
	}
	executor.ApplyConfig(cfg, true)
	if request.SelectedNode != "" && request.SelectedGroup == selection.target {
		previous[request.SelectedGroup] = request.SelectedNode
	}
	for name, node := range previous {
		if proxy := tunnel.Proxies()[name]; proxy != nil {
			if group, ok := proxy.Adapter().(outboundgroup.SelectAble); ok {
				_ = group.Set(node)
			}
		}
	}
	if request.Mode != "music" && music != nil {
		music.listener.Close()
		music = nil
	}
	machine.music = music
	machine.groupOrder = selection.groups
	machine.selected = selection.target
	return nil
}

func makeMusicListener() (*musicListener, error) {
	var random [32]byte
	if _, err := rand.Read(random[:]); err != nil {
		return nil, err
	}
	user := hex.EncodeToString(random[:8])
	pass := hex.EncodeToString(random[8:])
	authenticator := coreauth.NewAuthenticator([]coreauth.AuthUser{{User: user, Pass: pass}})
	listener, err := proxyhttp.NewWithConfig(LC.AuthServer{
		Enable: true, Listen: "127.0.0.1:0", AuthStore: listenerauth.NewAuthStore(authenticator),
	}, inbound.NewListenConfig(), tunnel.Tunnel, inbound.WithInName(musicInbound))
	if err != nil {
		return nil, errors.New("could not open the authenticated music listener")
	}
	host, portText, err := net.SplitHostPort(listener.Address())
	if err != nil {
		listener.Close()
		return nil, err
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		listener.Close()
		return nil, err
	}
	return &musicListener{
		listener: listener, endpoint: musicEndpoint{Host: host, Port: port, Username: user, Password: pass},
	}, nil
}

func proxyGroup(name string) outboundgroup.ProxyGroup {
	if proxy := tunnel.Proxies()[name]; proxy != nil {
		group, _ := proxy.Adapter().(outboundgroup.ProxyGroup)
		return group
	}
	return nil
}

func engineStatus() []byte {
	machine.Lock()
	defer machine.Unlock()
	status := engineState{Groups: make([]groupState, 0, len(machine.groupOrder)), SelectedGroup: machine.selected}
	for _, name := range machine.groupOrder {
		if group := proxyGroup(name); group != nil {
			members := group.Proxies()
			item := groupState{Name: name, Nodes: make([]string, 0, len(members)), Current: group.Now()}
			for _, member := range members {
				item.Nodes = append(item.Nodes, member.Name())
			}
			status.Groups = append(status.Groups, item)
			if name == machine.selected {
				status.SelectedNode = item.Current
			}
		}
	}
	if machine.music != nil {
		status.MusicProxy = &machine.music.endpoint
	}
	data, _ := json.Marshal(status)
	return data
}

func stopEngine() {
	machine.Lock()
	defer machine.Unlock()
	if device := machine.device.Swap(nil); device != nil {
		device.cancel()
		machine.stack.Close()
		device.Close()
		machine.stack = nil
		stopCore()
	}
}

func stopCore() {
	if machine.music != nil {
		machine.music.listener.Close()
		machine.music = nil
	}
	statistic.DefaultManager.Range(func(track statistic.Tracker) bool {
		track.Close()
		return true
	})
	tunnel.OnSuspend()
	executor.Shutdown()
	machine.groupOrder = nil
	machine.selected = ""
}

func main() {}
