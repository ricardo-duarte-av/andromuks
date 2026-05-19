package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

// envelope is the gomuks RPC frame: { command, request_id, data }.
type envelope struct {
	Command   string          `json:"command"`
	RequestID int64           `json:"request_id"`
	Data      json.RawMessage `json:"data,omitempty"`
}

// GomuksClient maintains a long-lived WebSocket to the gomuks backend, dispatches
// commands by request_id, and discards every inbound frame that isn't a matching
// response/error. The sync_complete stream is read off the socket (so the server
// doesn't block on backpressure) and immediately dropped.
type GomuksClient struct {
	cfg *Config

	mu        sync.Mutex
	conn      *websocket.Conn
	nextReqID int64
	pending   map[int64]chan envelope

	connected atomic.Bool
	stopCh    chan struct{}
}

func NewGomuksClient(cfg *Config) *GomuksClient {
	return &GomuksClient{
		cfg:     cfg,
		pending: make(map[int64]chan envelope),
		stopCh:  make(chan struct{}),
	}
}

// Run blocks; reconnects with backoff on failure. Stop by closing stopCh.
func (g *GomuksClient) Run() {
	backoff := time.Second
	for {
		select {
		case <-g.stopCh:
			return
		default:
		}
		if err := g.connectAndServe(); err != nil {
			log.Printf("gomuks: connection lost: %v", err)
		}
		g.failPending(errors.New("gomuks: connection lost"))
		time.Sleep(backoff)
		if backoff < 30*time.Second {
			backoff *= 2
		}
		// Successful run resets backoff via connectAndServe's first ping.
		if g.connected.Load() {
			backoff = time.Second
		}
	}
}

func (g *GomuksClient) connectAndServe() error {
	cookie, _ := MintCookie(g.cfg)
	header := http.Header{}
	header.Set("Cookie", cookieName+"="+cookie)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	dialer := *websocket.DefaultDialer
	conn, _, err := dialer.DialContext(ctx, g.cfg.GomuksURL, header)
	if err != nil {
		return fmt.Errorf("dial: %w", err)
	}
	g.mu.Lock()
	g.conn = conn
	g.mu.Unlock()
	g.connected.Store(true)
	defer func() {
		g.connected.Store(false)
		_ = conn.Close()
		g.mu.Lock()
		g.conn = nil
		g.mu.Unlock()
	}()

	log.Printf("gomuks: connected to %s", g.cfg.GomuksURL)

	// Read loop: parse only the envelope; route responses; drop everything else.
	for {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return fmt.Errorf("read: %w", err)
		}
		var env envelope
		if err := json.Unmarshal(msg, &env); err != nil {
			continue
		}
		if env.Command != "response" && env.Command != "error" {
			continue
		}
		g.mu.Lock()
		ch, ok := g.pending[env.RequestID]
		if ok {
			delete(g.pending, env.RequestID)
		}
		g.mu.Unlock()
		if ok {
			ch <- env
		}
	}
}

func (g *GomuksClient) failPending(err error) {
	g.mu.Lock()
	pending := g.pending
	g.pending = make(map[int64]chan envelope)
	g.mu.Unlock()
	for _, ch := range pending {
		close(ch)
	}
}

// Call sends a command and waits for the matching response. Returns the raw
// data payload of a "response" frame, or an error for "error" frames, transport
// failures, or context timeout.
func (g *GomuksClient) Call(ctx context.Context, command string, data any) (json.RawMessage, error) {
	if !g.connected.Load() {
		return nil, errors.New("gomuks: not connected")
	}
	body, err := json.Marshal(data)
	if err != nil {
		return nil, fmt.Errorf("marshal data: %w", err)
	}
	reqID := atomic.AddInt64(&g.nextReqID, 1)
	ch := make(chan envelope, 1)

	g.mu.Lock()
	conn := g.conn
	if conn == nil {
		g.mu.Unlock()
		return nil, errors.New("gomuks: not connected")
	}
	g.pending[reqID] = ch
	g.mu.Unlock()

	frame := envelope{Command: command, RequestID: reqID, Data: body}
	frameBytes, _ := json.Marshal(frame)
	if err := conn.WriteMessage(websocket.TextMessage, frameBytes); err != nil {
		g.mu.Lock()
		delete(g.pending, reqID)
		g.mu.Unlock()
		return nil, fmt.Errorf("write: %w", err)
	}

	select {
	case env, ok := <-ch:
		if !ok {
			return nil, errors.New("gomuks: connection lost during call")
		}
		if env.Command == "error" {
			var msg string
			_ = json.Unmarshal(env.Data, &msg)
			return nil, fmt.Errorf("gomuks: %s", msg)
		}
		return env.Data, nil
	case <-ctx.Done():
		g.mu.Lock()
		delete(g.pending, reqID)
		g.mu.Unlock()
		return nil, ctx.Err()
	}
}
