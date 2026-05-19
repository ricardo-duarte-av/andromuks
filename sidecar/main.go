package main

import (
	"context"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	configPath := flag.String("config", "config.yaml", "path to sidecar YAML config")
	gomuksConfigPath := flag.String("gomuks-config", "", "optional path to gomuks backend config.yaml; sidecar inherits token_key, username, and gomuks_url from web.* unless explicitly set in sidecar config")
	flag.Parse()

	cfg, err := LoadConfig(*configPath)
	if err != nil {
		log.Fatalf("config: %v", err)
	}
	if *gomuksConfigPath != "" {
		if err := ApplyGomuksConfig(cfg, *gomuksConfigPath); err != nil {
			log.Fatalf("gomuks config: %v", err)
		}
	}
	if err := cfg.Validate(); err != nil {
		log.Fatalf("config: %v", err)
	}

	gc := NewGomuksClient(cfg)
	go gc.Run()

	srv := &http.Server{
		Addr:              cfg.ListenAddress,
		Handler:           NewServer(cfg, gc).Routes(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	go func() {
		log.Printf("sidecar listening on %s", cfg.ListenAddress)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("http: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)
}
