package main

import (
	"fmt"
	"net/url"
	"os"
	"strings"

	"gopkg.in/yaml.v3"
)

// gomuksConfig mirrors only the fields we need from the gomuks backend config.
// Unknown fields are ignored.
type gomuksConfig struct {
	Web struct {
		ListenAddress string `yaml:"listen_address"`
		Username      string `yaml:"username"`
		TokenKey      string `yaml:"token_key"`
	} `yaml:"web"`
}

// ApplyGomuksConfig fills in any sidecar Config fields that are empty using
// values pulled from a gomuks backend config file. Sidecar values explicitly
// set in its own YAML are preserved (they win over gomuks values). This lets
// the user keep `token_key` and `username` in one place — the gomuks config —
// without duplicating them in the sidecar config.
func ApplyGomuksConfig(cfg *Config, path string) error {
	raw, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("read gomuks config: %w", err)
	}
	var gc gomuksConfig
	if err := yaml.Unmarshal(raw, &gc); err != nil {
		return fmt.Errorf("parse gomuks config: %w", err)
	}
	if cfg.TokenKey == "" {
		cfg.TokenKey = gc.Web.TokenKey
	}
	if cfg.Username == "" {
		cfg.Username = gc.Web.Username
	}
	if cfg.GomuksURL == "" && gc.Web.ListenAddress != "" {
		cfg.GomuksURL = deriveGomuksURL(gc.Web.ListenAddress)
	}
	return nil
}

// deriveGomuksURL turns a gomuks web.listen_address into a usable ws:// URL.
// A wildcard bind address (0.0.0.0 or ::) is rewritten to 127.0.0.1 so the
// sidecar talks to gomuks over loopback rather than via the public interface.
func deriveGomuksURL(listenAddr string) string {
	host, port := splitHostPort(listenAddr)
	switch host {
	case "", "0.0.0.0", "::", "[::]":
		host = "127.0.0.1"
	}
	u := url.URL{
		Scheme: "ws",
		Host:   host + ":" + port,
		Path:   "/_gomuks/websocket",
	}
	return u.String()
}

func splitHostPort(s string) (string, string) {
	// Handle bracketed IPv6: [::]:29325
	if strings.HasPrefix(s, "[") {
		end := strings.Index(s, "]")
		if end > 0 {
			host := s[:end+1]
			rest := s[end+1:]
			if strings.HasPrefix(rest, ":") {
				return host, rest[1:]
			}
			return host, ""
		}
	}
	i := strings.LastIndex(s, ":")
	if i < 0 {
		return s, ""
	}
	return s[:i], s[i+1:]
}
