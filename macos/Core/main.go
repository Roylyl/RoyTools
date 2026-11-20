package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"

	"github.com/metacubex/mihomo/adapter/inbound"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/listener/auth"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/listener/mixed"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
	"github.com/sirupsen/logrus"
)

const maxConfigBytes = 32 << 20

type command struct {
	ID      uint64          `json:"id"`
	Kind    string          `json:"kind"`
	Payload json.RawMessage `json:"payload"`
}

type response struct {
	ID    uint64 `json:"id"`
	Reply reply  `json:"reply"`
}

type reply struct {
	OK                 bool               `json:"ok"`
	Message            string             `json:"message,omitempty"`
	Mode               string             `json:"mode,omitempty"`
	RulesVersion       string             `json:"rulesVersion,omitempty"`
	Uploaded           *int64             `json:"uploaded,omitempty"`
	Downloaded         *int64             `json:"downloaded,omitempty"`
	MusicProxyPort     int                `json:"musicProxyPort,omitempty"`
	MusicProxyUsername string             `json:"musicProxyUsername,omitempty"`
	MusicProxyPassword string             `json:"musicProxyPassword,omitempty"`
	LANProxyPort       int                `json:"lanProxyPort,omitempty"`
	SelectedGroup      string             `json:"selectedGroup,omitempty"`
	SelectedNode       string             `json:"selectedNode,omitempty"`
	Groups             *[]proxyGroupReply `json:"groups,omitempty"`
	ProxyNodes         []string           `json:"proxyNodes,omitempty"`
	GlobalChoices      []string           `json:"globalChoices,omitempty"`
	ProxyLatencies     []latency          `json:"proxyLatencies,omitempty"`
}

type proxyGroupReply struct {
	Name       string   `json:"name"`
	Nodes      []string `json:"nodes"`
	Selected   string   `json:"selected,omitempty"`
	Selectable bool     `json:"selectable"`
}

type latency struct {
	Node         string `json:"node"`
	Milliseconds *int32 `json:"milliseconds,omitempty"`
}

func main() {
	options := flag.NewFlagSet("RoyToolsMacCore", flag.ContinueOnError)
	options.SetOutput(io.Discard)
	configFile := options.String("config", "", "path to the proxy configuration")
	dataDir := options.String("data-dir", "", "Mihomo data directory")
	port := options.Int("port", 0, "loopback listener port, or zero for an assigned port")
	parentPipe := options.Bool("parent-pipe", false, "stop when the parent closes stdin")
	if err := options.Parse(os.Args[1:]); err != nil || options.NArg() != 0 || *configFile == "" || *dataDir == "" || *port < 0 || *port > 65535 {
		fmt.Fprintln(os.Stderr, "RoyToolsMacCore: invalid startup options")
		os.Exit(2)
	}

	if err := run(*configFile, *dataDir, *port, *parentPipe); err != nil {
		fmt.Fprintln(os.Stderr, "RoyToolsMacCore:", err)
		os.Exit(1)
	}
}

func run(configFile, dataDir string, port int, parentPipe bool) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	home, err := filepath.Abs(dataDir)
	if err != nil || os.MkdirAll(home, 0700) != nil {
		return errors.New("could not prepare the core data directory")
	}
	machine.home = home
	C.SetHomeDir(home)
	C.SetConfig(filepath.Join(home, "config.yaml"))
	logrus.SetOutput(os.Stderr)
	log.SetLevel(log.SILENT)

	startup, err := readProfile(configFile)
	if err != nil {
		return err
	}

	active, err := reloadProfile(startup)
	if err != nil {
		return err
	}
	defer stopCore()
	if ctx.Err() != nil {
		return nil
	}

	listen := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	proxy, err := mixed.NewWithConfig(LC.AuthServer{
		Enable:    true,
		Listen:    listen,
		AuthStore: auth.Nil,
	}, inbound.NewListenConfig(), tunnel.Tunnel)
	if err != nil {
		return errors.New("could not open the loopback proxy listener")
	}
	defer proxy.Close()

	_, boundPort, err := net.SplitHostPort(proxy.Address())
	if err != nil {
		return errors.New("could not determine the proxy listener port")
	}
	if _, err = fmt.Printf("ROYTOOLS_PROXY_PORT=%s\n", boundPort); err != nil {
		return errors.New("could not report the proxy listener port")
	}
	if parentPipe {
		return serve(ctx, os.Stdin, os.Stdout, active)
	}
	<-ctx.Done()
	return nil
}

func readProfile(path string) ([]byte, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, errors.New("could not open the proxy configuration")
	}
	defer file.Close()

	data, err := io.ReadAll(io.LimitReader(file, maxConfigBytes+1))
	if err != nil || len(data) == 0 || len(data) > maxConfigBytes {
		return nil, errors.New("could not read the proxy configuration or its size is invalid")
	}
	var format struct {
		SubscriptionYAML *string `json:"subscriptionYAML"`
	}
	if json.Unmarshal(data, &format) == nil && format.SubscriptionYAML != nil {
		return data, nil
	}
	// The command-line entry point also accepts the original YAML format.
	return json.Marshal(profileRequest{Mode: "default", SubscriptionYAML: string(data)})
}

func restrictMacProfile(raw *config.RawConfig) {
	// The shared profile removes subscription-owned listeners and API controllers.
	// An unsandboxed Mac helper must also retain these host-specific restrictions.
	raw.BindAddress = "127.0.0.1"
	raw.NTP.WriteToSystem = false
	raw.TLS = config.RawTLS{}
	raw.ExternalUIURL = ""
	raw.GeoAutoUpdate = false
}

func reloadProfile(input []byte) (profileRequest, error) {
	active, err := decodeProfile(input)
	if err != nil {
		return profileRequest{}, err
	}
	machine.Lock()
	defer machine.Unlock()
	if err := applyProfile(input); err != nil {
		return profileRequest{}, err
	}
	return active, nil
}

func serve(ctx context.Context, input io.Reader, output io.Writer, active profileRequest) error {
	lines := make(chan string)
	readError := make(chan error, 1)
	go func() {
		defer close(lines)
		scanner := bufio.NewScanner(input)
		scanner.Buffer(make([]byte, 64<<10), maxConfigBytes+4096)
		for scanner.Scan() {
			select {
			case lines <- scanner.Text():
			case <-ctx.Done():
				return
			}
		}
		readError <- scanner.Err()
	}()
	encoder := json.NewEncoder(output)
	for {
		select {
		case <-ctx.Done():
			return nil
		case line, open := <-lines:
			if !open {
				if ctx.Err() == nil && <-readError != nil {
					return errors.New("could not read the parent command pipe")
				}
				return nil
			}
			var request command
			if json.Unmarshal([]byte(line), &request) != nil {
				return errors.New("invalid parent command")
			}
			result := runCommand(ctx, request, &active)
			if err := encoder.Encode(response{ID: request.ID, Reply: result}); err != nil {
				return errors.New("could not write the parent reply")
			}
		}
	}
}

func runCommand(ctx context.Context, request command, active *profileRequest) reply {
	switch request.Kind {
	case "status":
		return currentStatus(*active, true)
	case "counters":
		return currentStatus(*active, false)
	case "reload":
		profile, err := reloadProfile(request.Payload)
		if err != nil {
			return reply{Message: "Mihomo无法加载代理配置，请检查规则、节点或端口。"}
		}
		*active = profile
		return currentStatus(*active, true)
	case "latencies":
		return latencyReply(ctx, request.Payload)
	default:
		return reply{Message: "无法识别代理核心指令。"}
	}
}

func currentStatus(active profileRequest, details bool) reply {
	uploaded, downloaded := statistic.ProxyPayloadTotal()
	result := reply{OK: true, Mode: active.Mode, RulesVersion: active.RulesVersion, Uploaded: &uploaded, Downloaded: &downloaded}
	if !details {
		return result
	}
	var state engineState
	if json.Unmarshal(engineStatus(), &state) != nil {
		return reply{Message: "无法读取代理核心状态。"}
	}
	groups := make([]proxyGroupReply, 0, len(state.Groups))
	for _, group := range state.Groups {
		groups = append(groups, proxyGroupReply{Name: group.Name, Nodes: group.Nodes, Selected: group.Current, Selectable: group.Selectable})
	}
	result.Groups = &groups
	result.SelectedGroup = state.SelectedGroup
	result.SelectedNode = state.SelectedNode
	result.ProxyNodes = state.ProxyNodes
	result.GlobalChoices = state.GlobalChoices
	if state.MusicProxy != nil {
		result.MusicProxyPort = state.MusicProxy.Port
		result.MusicProxyUsername = state.MusicProxy.Username
		result.MusicProxyPassword = state.MusicProxy.Password
	}
	if state.LANProxy != nil {
		result.LANProxyPort = state.LANProxy.Port
	}
	return result
}

func latencyReply(ctx context.Context, payload json.RawMessage) reply {
	var request proxyLatencyRequest
	if len(payload) > 64<<10 || json.Unmarshal(payload, &request) != nil ||
		len(request.Nodes) == 0 || len(request.Nodes) > latencyBatchSize {
		return reply{Message: "测速请求无效。"}
	}
	results := make([]int32, len(request.Nodes))
	if measureProxyLatencies(ctx, payload, results) != len(results) {
		return reply{Message: "无法测试所选代理节点。"}
	}
	latencies := make([]latency, len(results))
	for index, delay := range results {
		latencies[index].Node = request.Nodes[index]
		if delay >= 0 {
			latencies[index].Milliseconds = &results[index]
		}
	}
	return reply{OK: true, ProxyLatencies: latencies}
}
