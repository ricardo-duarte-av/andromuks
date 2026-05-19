package main

import (
	"errors"
	"os"
	"strings"
	"testing"
	"time"
)

func baseCfg(t *testing.T) *Config {
	t.Helper()
	return &Config{
		TokenKey:      "test-key-not-a-real-secret",
		Username:      "daedric",
		TokenTTL:      7 * 24 * time.Hour,
		RefreshWindow: 24 * time.Hour,
	}
}

func TestMintAndValidateRoundTrip(t *testing.T) {
	cfg := baseCfg(t)
	cookie, expiry := MintCookie(cfg)
	if !strings.Contains(cookie, ".") {
		t.Fatalf("cookie has no '.' separator: %q", cookie)
	}
	if time.Until(expiry) <= 0 {
		t.Fatalf("expiry is not in the future: %v", expiry)
	}
	claims, refresh, err := ValidateCookie(cookie, cfg)
	if err != nil {
		t.Fatalf("validate fresh cookie: %v", err)
	}
	if claims.Username != "daedric" {
		t.Errorf("username = %q, want daedric", claims.Username)
	}
	if refresh {
		t.Errorf("fresh cookie should not be flagged for refresh")
	}
}

func TestValidateRejectsWrongKey(t *testing.T) {
	mintCfg := baseCfg(t)
	cookie, _ := MintCookie(mintCfg)

	checkCfg := *mintCfg
	checkCfg.TokenKey = "different-key"
	_, _, err := ValidateCookie(cookie, &checkCfg)
	if !errors.Is(err, ErrSignatureBad) {
		t.Fatalf("got %v, want ErrSignatureBad", err)
	}
}

func TestValidateRejectsTamperedClaims(t *testing.T) {
	cfg := baseCfg(t)
	cookie, _ := MintCookie(cfg)
	parts := strings.Split(cookie, ".")
	// Flip the first byte of the base64-encoded claims to break the HMAC.
	tampered := flipFirstChar(parts[0]) + "." + parts[1]
	_, _, err := ValidateCookie(tampered, cfg)
	if !errors.Is(err, ErrSignatureBad) && !errors.Is(err, ErrCookieMalformed) {
		t.Fatalf("got %v, want ErrSignatureBad or ErrCookieMalformed", err)
	}
}

func TestValidateRejectsExpiredToken(t *testing.T) {
	cfg := baseCfg(t)
	cfg.TokenTTL = -time.Hour
	cookie, _ := MintCookie(cfg)
	cfg.TokenTTL = 7 * 24 * time.Hour
	_, _, err := ValidateCookie(cookie, cfg)
	if !errors.Is(err, ErrExpired) {
		t.Fatalf("got %v, want ErrExpired", err)
	}
}

func TestValidateRejectsWrongUsername(t *testing.T) {
	mintCfg := baseCfg(t)
	cookie, _ := MintCookie(mintCfg)
	checkCfg := *mintCfg
	checkCfg.Username = "someone-else"
	_, _, err := ValidateCookie(cookie, &checkCfg)
	if !errors.Is(err, ErrWrongUser) {
		t.Fatalf("got %v, want ErrWrongUser", err)
	}
}

func TestRefreshWindowFlag(t *testing.T) {
	cfg := baseCfg(t)
	// TTL deliberately shorter than the refresh window: every cookie should be
	// flagged as due-for-refresh the moment it's minted.
	cfg.TokenTTL = 12 * time.Hour
	cfg.RefreshWindow = 24 * time.Hour
	cookie, _ := MintCookie(cfg)
	_, refresh, err := ValidateCookie(cookie, cfg)
	if err != nil {
		t.Fatalf("validate: %v", err)
	}
	if !refresh {
		t.Errorf("cookie with TTL < RefreshWindow should be flagged for refresh")
	}
}

func TestValidateRejectsMalformed(t *testing.T) {
	cfg := baseCfg(t)
	cases := []string{
		"",
		"no-dot-here",
		"too.many.dots",
		"!!!.!!!",
	}
	for _, c := range cases {
		_, _, err := ValidateCookie(c, cfg)
		if err == nil {
			t.Errorf("expected error for %q, got nil", c)
		}
	}
}

// TestValidateAgainstRealCookie is an opt-in test that verifies our HMAC
// assumptions against a real gomuks-issued cookie. It is skipped unless both
// env vars are set, so secrets never need to live in the repo.
//
// Usage:
//
//	SIDECAR_TEST_TOKEN_KEY=<gomuks web.token_key> \
//	SIDECAR_TEST_COOKIE=<full gomuks_auth cookie value> \
//	SIDECAR_TEST_USERNAME=<gomuks web.username> \
//	go test -run TestValidateAgainstRealCookie ./...
//
// A pass here confirms the two assumptions in the implementation:
//  1. HMAC algorithm is SHA-256.
//  2. HMAC input is the base64url-encoded claims string (not the raw JSON).
//
// A failure means one of those assumptions is wrong; adjust ValidateCookie
// and MintCookie before deploying.
func TestValidateAgainstRealCookie(t *testing.T) {
	tokenKey := os.Getenv("SIDECAR_TEST_TOKEN_KEY")
	cookie := os.Getenv("SIDECAR_TEST_COOKIE")
	username := os.Getenv("SIDECAR_TEST_USERNAME")
	if tokenKey == "" || cookie == "" || username == "" {
		t.Skip("set SIDECAR_TEST_TOKEN_KEY, SIDECAR_TEST_COOKIE, SIDECAR_TEST_USERNAME to run")
	}
	cfg := &Config{
		TokenKey:      tokenKey,
		Username:      username,
		TokenTTL:      7 * 24 * time.Hour,
		RefreshWindow: 24 * time.Hour,
	}
	claims, _, err := ValidateCookie(cookie, cfg)
	if err != nil {
		t.Fatalf("real cookie failed validation: %v\n"+
			"This means one of our HMAC assumptions is wrong. See the comment\n"+
			"above this test for what to adjust.", err)
	}
	t.Logf("real cookie validated: username=%s expiry=%s",
		claims.Username, time.Unix(claims.Expiry, 0))
}

func flipFirstChar(s string) string {
	if s == "" {
		return "A"
	}
	if s[0] == 'A' {
		return "B" + s[1:]
	}
	return "A" + s[1:]
}
