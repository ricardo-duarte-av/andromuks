package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
)

const cookieName = "gomuks_auth"

// Claims must match the on-the-wire JSON shape gomuks uses. Field order is
// preserved by encoding/json (Go writes struct fields in declaration order),
// which matters because the HMAC is computed over the exact JSON bytes.
type Claims struct {
	Username string `json:"username"`
	Expiry   int64  `json:"expiry"`
}

var (
	ErrCookieMissing   = errors.New("auth: cookie missing")
	ErrCookieMalformed = errors.New("auth: cookie malformed")
	ErrSignatureBad    = errors.New("auth: signature invalid")
	ErrExpired         = errors.New("auth: token expired")
	ErrWrongUser       = errors.New("auth: username mismatch")
)

// ValidateCookie verifies the signature against tokenKey. Returns the parsed
// claims and a bool indicating whether the token is within the refresh window
// and should be re-issued in the response. An expired token returns ErrExpired
// regardless of signature validity.
func ValidateCookie(raw string, cfg *Config) (*Claims, bool, error) {
	parts := strings.Split(raw, ".")
	if len(parts) != 2 {
		return nil, false, ErrCookieMalformed
	}
	claimsB64, sigB64 := parts[0], parts[1]

	claimsBytes, err := base64.RawURLEncoding.DecodeString(claimsB64)
	if err != nil {
		return nil, false, ErrCookieMalformed
	}
	gotSig, err := base64.RawURLEncoding.DecodeString(sigB64)
	if err != nil {
		return nil, false, ErrCookieMalformed
	}

	mac := hmac.New(sha256.New, []byte(cfg.TokenKey))
	mac.Write(claimsBytes)
	wantSig := mac.Sum(nil)
	if !hmac.Equal(gotSig, wantSig) {
		return nil, false, ErrSignatureBad
	}

	var c Claims
	if err := json.Unmarshal(claimsBytes, &c); err != nil {
		return nil, false, ErrCookieMalformed
	}
	if c.Username != cfg.Username {
		return nil, false, ErrWrongUser
	}
	now := time.Now().Unix()
	if now >= c.Expiry {
		return nil, false, ErrExpired
	}
	refreshDue := time.Until(time.Unix(c.Expiry, 0)) < cfg.RefreshWindow
	return &c, refreshDue, nil
}

// MintCookie produces a fresh gomuks_auth value for the configured username.
// HMAC is computed over the base64url-encoded claims string, matching gomuks.
func MintCookie(cfg *Config) (string, time.Time) {
	expiry := time.Now().Add(cfg.TokenTTL)
	claims := Claims{Username: cfg.Username, Expiry: expiry.Unix()}
	body, _ := json.Marshal(claims)
	mac := hmac.New(sha256.New, []byte(cfg.TokenKey))
	mac.Write(body)
	claimsB64 := base64.RawURLEncoding.EncodeToString(body)
	sigB64 := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	return claimsB64 + "." + sigB64, expiry
}

// ExtractCookie pulls gomuks_auth from the request. Returns ErrCookieMissing
// if no such cookie is present.
func ExtractCookie(r *http.Request) (string, error) {
	c, err := r.Cookie(cookieName)
	if err != nil {
		return "", ErrCookieMissing
	}
	return c.Value, nil
}

// WriteRefreshedCookie sets the gomuks_auth cookie on the response. Attributes
// mirror gomuks: HttpOnly, SameSite=Lax, and Secure if configured.
func WriteRefreshedCookie(w http.ResponseWriter, cfg *Config, value string, expiry time.Time) {
	http.SetCookie(w, &http.Cookie{
		Name:     cookieName,
		Value:    value,
		Path:     "/",
		Domain:   cfg.CookieDomain,
		Expires:  expiry,
		HttpOnly: true,
		Secure:   cfg.CookieSecure,
		SameSite: http.SameSiteLaxMode,
	})
}
