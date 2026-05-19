package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

// pingInterval matches the gomuks recommendation. The server kills connections
// silent for >60s, so 15s leaves us 3 missed pings of headroom.
const pingInterval = 15 * time.Second

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

	// mu guards conn, pending, and lastReceivedID.
	mu sync.Mutex
	// writeMu serializes WriteMessage calls. Gorilla websocket requires only one
	// concurrent writer; the ping goroutine and Call() can both fire writes.
	writeMu        sync.Mutex
	conn           *websocket.Conn
	nextReqID      int64
	pending        map[int64]chan envelope
	lastReceivedID int64

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

	dialCtx, dialCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer dialCancel()
	dialer := *websocket.DefaultDialer
	conn, resp, err := dialer.DialContext(dialCtx, g.cfg.GomuksURL, header)
	if err != nil {
		if resp != nil {
			body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
			_ = resp.Body.Close()
			return fmt.Errorf("dial: %w (status=%s body=%q)", err, resp.Status, string(body))
		}
		return fmt.Errorf("dial: %w", err)
	}
	g.mu.Lock()
	g.conn = conn
	g.lastReceivedID = 0
	g.mu.Unlock()
	g.connected.Store(true)

	// Ping goroutine lives only for the duration of this connection. We signal
	// it to stop via pingDone when the read loop exits.
	pingDone := make(chan struct{})
	go g.pingLoop(conn, pingDone)

	defer func() {
		close(pingDone)
		g.connected.Store(false)
		_ = conn.Close()
		g.mu.Lock()
		g.conn = nil
		g.mu.Unlock()
	}()

	log.Printf("gomuks: connected to %s", g.cfg.GomuksURL)

	// Read loop: parse only the envelope; route responses; drop everything else
	// after tracking its request_id as the high-water mark for ping acks.
	for {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return fmt.Errorf("read: %w", err)
		}
		var env envelope
		if err := json.Unmarshal(msg, &env); err != nil {
			continue
		}
		// Track the highest server-pushed request_id we've seen so pings can
		// acknowledge it. Server pushes (sync_complete etc.) use negative IDs
		// in gomuks; we don't care about the sign, only the magnitude relative
		// to what we've already seen.
		if env.RequestID != 0 {
			g.mu.Lock()
			if env.RequestID > g.lastReceivedID {
				g.lastReceivedID = env.RequestID
			}
			g.mu.Unlock()
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

// pingLoop sends a ping every pingInterval until the connection dies. The
// server kills idle connections after 60s. The pong reply (command="pong")
// arrives on the read loop and is dropped — we don't await it; a failed write
// here is the signal that the connection is dead.
func (g *GomuksClient) pingLoop(conn *websocket.Conn, done <-chan struct{}) {
	ticker := time.NewTicker(pingInterval)
	defer ticker.Stop()
	for {
		select {
		case <-done:
			return
		case <-ticker.C:
			if err := g.sendPing(conn); err != nil {
				// The read loop will surface the underlying error and trigger
				// reconnect; we just stop ticking.
				return
			}
		}
	}
}

func (g *GomuksClient) sendPing(conn *websocket.Conn) error {
	reqID := atomic.AddInt64(&g.nextReqID, 1)
	g.mu.Lock()
	lastReceived := g.lastReceivedID
	g.mu.Unlock()
	frame := envelope{
		Command:   "ping",
		RequestID: reqID,
		Data:      json.RawMessage(fmt.Sprintf(`{"last_received_id":%d}`, lastReceived)),
	}
	frameBytes, _ := json.Marshal(frame)
	g.writeMu.Lock()
	defer g.writeMu.Unlock()
	_ = conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return conn.WriteMessage(websocket.TextMessage, frameBytes)
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
	g.writeMu.Lock()
	_ = conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	err = conn.WriteMessage(websocket.TextMessage, frameBytes)
	g.writeMu.Unlock()
	if err != nil {
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
