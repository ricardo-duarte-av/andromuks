package main

import (
	"fmt"
	"os"
	"time"

	"gopkg.in/yaml.v3"
)

type Config struct {
	// HTTP server bound for the sidecar. Bind to localhost; let your fronting
	// proxy (openresty/nginx/Caddy) route /_gomuks/sidecar/* to this address.
	ListenAddress string `yaml:"listen_address"`

	// Direct gomuks WebSocket URL. Prefer ws://127.0.0.1:<port>/_gomuks/websocket
	// to avoid going through the TLS-terminating proxy for our own RPC session.
	GomuksURL string `yaml:"gomuks_url"`

	// Shared with gomuks. Used to validate cookies from clients AND to mint our
	// own session cookie for the persistent RPC connection.
	TokenKey string `yaml:"token_key"`

	// The single username gomuks accepts (gomuks web.username). Any cookie whose
	// claims contain a different username is rejected.
	Username string `yaml:"username"`

	// Lifetime of newly minted tokens. Matches gomuks default of 7 days.
	TokenTTL time.Duration `yaml:"token_ttl"`

	// If an inbound cookie has less than this remaining, the response refreshes
	// it via Set-Cookie. Cookies past expiry are rejected outright.
	RefreshWindow time.Duration `yaml:"refresh_window"`

	// Cookie attributes applied when we refresh the client cookie. Domain may be
	// empty; Secure should be true behind a TLS-terminating proxy.
	CookieDomain string `yaml:"cookie_domain"`
	CookieSecure bool   `yaml:"cookie_secure"`
}

func LoadConfig(path string) (*Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read config: %w", err)
	}
	c := &Config{
		ListenAddress: "127.0.0.1:29326",
		TokenTTL:      7 * 24 * time.Hour,
		RefreshWindow: 24 * time.Hour,
		CookieSecure:  true,
	}
	if err := yaml.Unmarshal(raw, c); err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}
	return c, nil
}

// Validate must be called after any fallback layers (e.g. ApplyGomuksConfig)
// have filled in inherited fields.
func (c *Config) Validate() error {
	if c.TokenKey == "" {
		return fmt.Errorf("token_key is required (set in sidecar config or via -gomuks-config)")
	}
	if c.Username == "" {
		return fmt.Errorf("username is required (set in sidecar config or via -gomuks-config)")
	}
	if c.GomuksURL == "" {
		return fmt.Errorf("gomuks_url is required (set in sidecar config or via -gomuks-config)")
	}
	return nil
}
