package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"gopkg.in/yaml.v3"
)

const musicInbound = "roytools-music"

type profileRequest struct {
	Mode             string            `json:"mode"`
	SubscriptionYAML string            `json:"subscriptionYAML"`
	SelectedGroup    string            `json:"selectedGroup"`
	SelectedNode     string            `json:"selectedNode"`
	ProfileID        string            `json:"profileID"`
	GroupSelections  map[string]string `json:"groupSelections"`
	RulesVersion     string            `json:"rulesVersion"`
	Rules            []string          `json:"rules"`
	Providers        map[string]string `json:"providers"`
	MusicDomains     []string          `json:"musicDomains"`
	LANPort          int               `json:"lanPort"`
}

type routeSelection struct {
	target string
	groups []string
	nodes  []string
}

var (
	versionPattern  = regexp.MustCompile(`^(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})$`)
	providerPattern = regexp.MustCompile(`^direct-[0-9]{2}\.txt$`)
	domainPattern   = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$`)
)

func decodeProfile(input []byte) (profileRequest, error) {
	var request profileRequest
	if len(input) == 0 || len(input) > 32<<20 || !json.Valid(input) {
		return request, errors.New("invalid proxy configuration data")
	}
	if err := json.Unmarshal(input, &request); err != nil || request.SubscriptionYAML == "" {
		return request, errors.New("missing or invalid subscription YAML")
	}
	if request.LANPort < 0 || request.LANPort > 65535 {
		return request, errors.New("invalid LAN proxy port")
	}
	return request, nil
}

func profileNames(value any) []string {
	items, _ := value.([]any)
	result := make([]string, 0, len(items))
	for _, item := range items {
		entry, _ := item.(map[string]any)
		if name, ok := entry["name"].(string); ok && usableTarget(name) {
			result = append(result, name)
		}
	}
	return result
}

func usableTarget(target string) bool {
	if strings.TrimSpace(target) == "" || strings.ContainsAny(target, ",\r\n") {
		return false
	}
	switch strings.ToUpper(target) {
	case "GLOBAL", "DIRECT", "REJECT", "REJECT-DROP", "PASS", "PASS-RULE", "COMPATIBLE", "DNS":
		return false
	default:
		return true
	}
}

func chooseProxy(cfg map[string]any, preferred string) routeSelection {
	selection := routeSelection{groups: profileNames(cfg["proxy-groups"]), nodes: profileNames(cfg["proxies"])}
	names := append(append([]string(nil), selection.groups...), selection.nodes...)
	for _, name := range names {
		if name == preferred {
			selection.target = name
			return selection
		}
	}
	if len(names) > 0 {
		selection.target = names[0]
	}
	return selection
}

func composeProfile(home string, request profileRequest) ([]byte, routeSelection, error) {
	var cfg map[string]any
	if err := yaml.Unmarshal([]byte(request.SubscriptionYAML), &cfg); err != nil || cfg == nil {
		return nil, routeSelection{}, errors.New("subscription is not a Clash/Mihomo YAML mapping")
	}
	// Only RoyTools can enable its own explicit LAN or music listener.
	// Imported subscriptions cannot open local ports or API controllers.
	for _, key := range []string{
		"port", "socks-port", "redir-port", "tproxy-port", "mixed-port",
		"tuic-server", "ss-config", "vmess-config", "listeners", "tunnels", "iptables",
		"external-controller", "external-controller-tls", "external-controller-unix",
		"external-controller-pipe", "external-ui", "external-doh-server",
		"tun", "authentication", "interface-name", "routing-mark", "profile", "auto-update",
	} {
		delete(cfg, key)
	}
	cfg["allow-lan"] = false
	cfg["find-process-mode"] = "off"
	cfg["log-level"] = "silent"
	if _, ok := cfg["ipv6"]; !ok {
		cfg["ipv6"] = true
	}
	dns, _ := cfg["dns"].(map[string]any)
	if enabled, _ := dns["enable"].(bool); !enabled {
		dns = map[string]any{
			"enable": true, "ipv6": cfg["ipv6"], "enhanced-mode": "fake-ip",
			"fake-ip-range": "198.19.0.1/16",
			"nameserver":    []string{"1.1.1.1", "9.9.9.9"},
		}
	}
	delete(dns, "listen")
	cfg["dns"] = dns
	selection := chooseProxy(cfg, request.SelectedGroup)
	if request.Mode != "default" && request.Mode != "direct" && selection.target == "" {
		return nil, selection, errors.New("subscription has no usable proxy group or node")
	}
	switch request.Mode {
	case "default":
		// Preserve the provider's rules, subrules, providers and mode.
	case "global", "direct":
		cfg["mode"] = request.Mode
	case "rule", "ai":
		rules, err := overlayRules(home, cfg, request, selection.target)
		if err != nil {
			return nil, selection, err
		}
		cfg["rules"] = rules
		cfg["mode"] = "rule"
	case "music":
		if len(request.MusicDomains) == 0 || len(request.MusicDomains) > 32 {
			return nil, selection, errors.New("music routing policy has no valid domains")
		}
		rules := make([]string, 0, len(request.MusicDomains)+1)
		for _, domain := range request.MusicDomains {
			if len(domain) > 253 || !domainPattern.MatchString(domain) {
				return nil, selection, errors.New("music routing policy contains an invalid domain")
			}
			rules = append(rules, "AND,((IN-NAME,"+musicInbound+"),(DOMAIN-SUFFIX,"+domain+")),"+selection.target)
		}
		cfg["rules"] = append(rules, "MATCH,DIRECT")
		cfg["mode"] = "rule"
	default:
		return nil, selection, errors.New("unknown proxy mode")
	}
	result, err := yaml.Marshal(cfg)
	return result, selection, err
}

func overlayRules(home string, cfg map[string]any, request profileRequest, target string) ([]string, error) {
	if len(request.Rules) == 0 || len(request.Rules) > 10000 || !versionPattern.MatchString(request.RulesVersion) {
		return nil, errors.New("repository rules are missing or invalid")
	}
	last := request.Rules[len(request.Rules)-1]
	if !strings.HasPrefix(last, "MATCH,") || (request.Mode == "ai" && last != "MATCH,DIRECT") {
		return nil, errors.New("repository policy is missing its required fallback")
	}
	ruleProviders, _ := cfg["rule-providers"].(map[string]any)
	if ruleProviders == nil {
		ruleProviders = make(map[string]any)
	}
	prefix := "roytools-" + request.Mode + "-"
	result := make([]string, 0, len(request.Rules))
	for _, rule := range request.Rules {
		fields := strings.Split(rule, ",")
		if len(fields) < 2 || strings.ContainsAny(rule, "\r\n") {
			return nil, errors.New("invalid repository rule")
		}
		if fields[0] == "RULE-SET" {
			if len(fields) != 3 || !strings.HasPrefix(fields[1], prefix) {
				return nil, errors.New("invalid repository rule provider")
			}
			filename := strings.TrimPrefix(fields[1], prefix) + ".txt"
			text, found := request.Providers[filename]
			if !found || !providerPattern.MatchString(filename) || len(text) == 0 || len(text) > 8<<20 {
				return nil, errors.New("missing repository rule provider")
			}
			// Separate immutable versions keep active readers independent of a reload.
			relative := filepath.Join("providers", "roytools", request.RulesVersion, request.Mode, filename)
			if err := writeProvider(filepath.Join(home, relative), []byte(text)); err != nil {
				return nil, fmt.Errorf("write repository rule provider: %w", err)
			}
			fields[1] += "-v" + strings.ReplaceAll(request.RulesVersion, ".", "-")
			if _, collision := ruleProviders[fields[1]]; collision {
				return nil, errors.New("repository rule provider name conflicts with subscription")
			}
			ruleProviders[fields[1]] = map[string]any{
				"type": "file", "behavior": "domain", "format": "text", "path": filepath.ToSlash(relative),
			}
		}
		policy := len(fields) - 1
		if fields[policy] == "no-resolve" && policy > 0 {
			policy--
		}
		switch fields[policy] {
		case "DIRECT", "REJECT", "REJECT-DROP", "PASS", "PASS-RULE":
		default:
			fields[policy] = target
		}
		result = append(result, strings.Join(fields, ","))
	}
	cfg["rule-providers"] = ruleProviders
	return result, nil
}

func writeProvider(path string, contents []byte) error {
	existing, err := os.ReadFile(path)
	if err == nil {
		if bytes.Equal(existing, contents) {
			return nil
		}
		return errors.New("repository rules changed without a version update")
	}
	if !os.IsNotExist(err) {
		return err
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	file, err := os.CreateTemp(dir, ".rules-*")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	_, err = file.Write(contents)
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	return os.Rename(file.Name(), path)
}
