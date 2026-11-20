package main

import (
	"context"
	"errors"
	"net"
	"strconv"
	"sync"

	"github.com/metacubex/mihomo/adapter/inbound"
	C "github.com/metacubex/mihomo/constant"
	listenerauth "github.com/metacubex/mihomo/listener/auth"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/listener/mixed"
	"github.com/metacubex/mihomo/tunnel"
)

type lanEndpoint struct {
	Port int `json:"port"`
}

type lanProxy struct {
	listener *mixed.Listener
	endpoint lanEndpoint
}

func makeLANProxy(port int) (*lanProxy, error) {
	address := net.JoinHostPort("0.0.0.0", strconv.Itoa(port))
	listener, err := mixed.NewWithConfig(
		LC.AuthServer{Enable: true, Listen: address, AuthStore: listenerauth.Nil},
		lanListenConfig{inbound.NewListenConfig()}, tunnel.Tunnel, inbound.WithInName("roytools-lan"),
	)
	if err != nil {
		return nil, errors.New("could not listen on the selected LAN proxy port")
	}
	return &lanProxy{listener: listener, endpoint: lanEndpoint{Port: port}}, nil
}

type lanListenConfig struct{ C.InboundListenConfig }

func (config lanListenConfig) Listen(ctx context.Context, _, address string) (net.Listener, error) {
	listener, err := config.InboundListenConfig.Listen(ctx, "tcp4", address)
	if err != nil {
		return nil, err
	}
	return &lanListener{Listener: listener, clients: make(map[*lanClient]struct{})}, nil
}

type lanListener struct {
	net.Listener
	mu      sync.Mutex
	closed  bool
	clients map[*lanClient]struct{}
}

func (listener *lanListener) Accept() (net.Conn, error) {
	for {
		conn, err := listener.Listener.Accept()
		if err != nil {
			return nil, err
		}
		if !directLANPeer(conn) {
			_ = conn.Close()
			continue
		}
		listener.mu.Lock()
		if listener.closed {
			listener.mu.Unlock()
			_ = conn.Close()
			return nil, net.ErrClosed
		}
		client := &lanClient{Conn: conn, listener: listener}
		listener.clients[client] = struct{}{}
		listener.mu.Unlock()
		return client, nil
	}
}

func (listener *lanListener) Close() error {
	listener.mu.Lock()
	listener.closed = true
	clients := listener.clients
	listener.clients = nil
	listener.mu.Unlock()

	err := listener.Listener.Close()
	for client := range clients {
		_ = client.Conn.Close()
	}
	return err
}

type lanClient struct {
	net.Conn
	listener *lanListener
}

func (client *lanClient) Close() error {
	client.listener.mu.Lock()
	delete(client.listener.clients, client)
	client.listener.mu.Unlock()
	return client.Conn.Close()
}

func directLANPeer(conn net.Conn) bool {
	local, localOK := conn.LocalAddr().(*net.TCPAddr)
	remote, remoteOK := conn.RemoteAddr().(*net.TCPAddr)
	if !localOK || !remoteOK {
		return false
	}
	if local.IP.IsLoopback() {
		return remote.IP.IsLoopback()
	}
	interfaces, err := net.Interfaces()
	if err != nil {
		return false
	}
	for _, iface := range interfaces {
		if iface.Flags&(net.FlagUp|net.FlagBroadcast) != net.FlagUp|net.FlagBroadcast ||
			iface.Flags&(net.FlagLoopback|net.FlagPointToPoint) != 0 {
			continue
		}
		addresses, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, address := range addresses {
			subnet, ok := address.(*net.IPNet)
			if ok && subnet.IP.Equal(local.IP) && subnet.Contains(remote.IP) {
				return true
			}
		}
	}
	return false
}
