package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"time"
)

const (
	maxBodyBytes = 64 * 1024
	callTimeout  = 15 * time.Second
)

type Server struct {
	cfg *Config
	g   *GomuksClient
}

func NewServer(cfg *Config, g *GomuksClient) *Server {
	return &Server{cfg: cfg, g: g}
}

func (s *Server) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/_gomuks/sidecar/send_msg", s.handleSendMsg)
	mux.HandleFunc("/_gomuks/sidecar/mark_read", s.handleMarkRead)
	mux.HandleFunc("/_gomuks/sidecar/healthz", s.handleHealth)
	return mux
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	if !s.g.connected.Load() {
		http.Error(w, "gomuks disconnected", http.StatusServiceUnavailable)
		return
	}
	w.WriteHeader(http.StatusOK)
}

// authGate validates the gomuks_auth cookie. On success it refreshes the cookie
// (if within the refresh window) and returns true. On failure it has already
// written the response and the caller should return.
func (s *Server) authGate(w http.ResponseWriter, r *http.Request) bool {
	raw, err := ExtractCookie(r)
	if err != nil {
		http.Error(w, "missing auth", http.StatusUnauthorized)
		return false
	}
	_, refreshDue, err := ValidateCookie(raw, s.cfg)
	if err != nil {
		switch {
		case errors.Is(err, ErrExpired):
			http.Error(w, "token expired", http.StatusUnauthorized)
		case errors.Is(err, ErrSignatureBad), errors.Is(err, ErrCookieMalformed), errors.Is(err, ErrWrongUser):
			http.Error(w, "invalid auth", http.StatusUnauthorized)
		default:
			http.Error(w, "auth error", http.StatusUnauthorized)
		}
		return false
	}
	if refreshDue {
		fresh, expiry := MintCookie(s.cfg)
		WriteRefreshedCookie(w, s.cfg, fresh, expiry)
	}
	return true
}

type sendMsgRequest struct {
	RoomID      string          `json:"room_id"`
	Text        string          `json:"text,omitempty"`
	BaseContent json.RawMessage `json:"base_content,omitempty"`
	RelatesTo   json.RawMessage `json:"relates_to,omitempty"`
	Mentions    json.RawMessage `json:"mentions,omitempty"`
}

func (s *Server) handleSendMsg(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !s.authGate(w, r) {
		return
	}
	var req sendMsgRequest
	if err := decodeJSON(r, &req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if req.RoomID == "" {
		http.Error(w, "room_id required", http.StatusBadRequest)
		return
	}
	if req.Text == "" && len(req.BaseContent) == 0 {
		http.Error(w, "text or base_content required", http.StatusBadRequest)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), callTimeout)
	defer cancel()
	data, err := s.g.Call(ctx, "send_message", req)
	if err != nil {
		log.Printf("send_message failed: %v", err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write(data)
}

type markReadRequest struct {
	RoomID      string `json:"room_id"`
	EventID     string `json:"event_id"`
	ReceiptType string `json:"receipt_type"`
}

func (s *Server) handleMarkRead(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !s.authGate(w, r) {
		return
	}
	var req markReadRequest
	if err := decodeJSON(r, &req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if req.RoomID == "" || req.EventID == "" {
		http.Error(w, "room_id and event_id required", http.StatusBadRequest)
		return
	}
	if req.ReceiptType == "" {
		req.ReceiptType = "m.read"
	}

	ctx, cancel := context.WithTimeout(r.Context(), callTimeout)
	defer cancel()
	if _, err := s.g.Call(ctx, "mark_read", req); err != nil {
		log.Printf("mark_read failed: %v", err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func decodeJSON(r *http.Request, dst any) error {
	r.Body = http.MaxBytesReader(nil, r.Body, maxBodyBytes)
	defer r.Body.Close()
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		return err
	}
	if dec.More() {
		return io.ErrUnexpectedEOF
	}
	return nil
}
