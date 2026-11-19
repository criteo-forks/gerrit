// Copyright 2026 The WalGerrit Authors. Licensed under the Apache License, Version 2.0.

// Command affinity routes Gerrit HTTP writes to a preferred, ready endpoint.
// Every backend remains independently writable. No endpoint owns a repository.
package main

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/netip"
	"net/url"
	"os"
	"os/signal"
	"sort"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

// projectKey shares a placement across authenticated and anonymous smart Git
// paths, Git discovery, and project-qualified REST writes. Some REST requests
// have no project in their URL; they can run on any pod.
func projectKey(r *http.Request) string {
	path := strings.TrimPrefix(r.URL.EscapedPath(), "/")
	path = strings.TrimPrefix(path, "a/")
	var encoded string
	switch {
	case r.Method == http.MethodPost && strings.HasSuffix(path, "/git-receive-pack"):
		encoded = strings.TrimSuffix(path, "/git-receive-pack")
	case r.Method == http.MethodGet && strings.HasSuffix(path, "/info/refs") && r.URL.Query().Get("service") == "git-receive-pack":
		encoded = strings.TrimSuffix(path, "/info/refs")
	case r.Method != http.MethodGet && r.Method != http.MethodHead && (strings.HasPrefix(path, "projects/") || strings.HasPrefix(path, "changes/")):
		resource, rest, _ := strings.Cut(path, "/")
		id, _, _ := strings.Cut(rest, "/")
		value, err := url.PathUnescape(id)
		if err != nil {
			return ""
		}
		if resource == "changes" {
			project, _, qualified := strings.Cut(value, "~")
			if !qualified {
				return ""
			}
			return project
		}
		return value
	default:
		return ""
	}
	value, err := url.PathUnescape(encoded)
	if err != nil {
		return ""
	}
	return strings.TrimRight(strings.TrimSuffix(strings.TrimRight(value, "/"), ".git"), "/")
}

type endpoints struct {
	addresses []string
	updated   time.Time
}

type router struct {
	ready  atomic.Pointer[endpoints]
	round  atomic.Uint64
	maxAge time.Duration
	scheme string
	proxy  *httputil.ReverseProxy
}

type targetKey struct{}

func newRouter(scheme string, maxAge time.Duration, transport http.RoundTripper) *router {
	r := &router{scheme: scheme, maxAge: maxAge}
	r.proxy = &httputil.ReverseProxy{
		Transport: transport,
		Rewrite: func(pr *httputil.ProxyRequest) {
			// Only endpoint membership can supply a destination. Preserve the
			// external Host and raw Git/REST URL, including encoded slashes.
			pr.Out.URL.Scheme = r.scheme
			pr.Out.URL.Host = pr.In.Context().Value(targetKey{}).(string)
			pr.Out.Host = pr.In.Host
			pr.SetXForwarded()
		},
		ErrorHandler: func(w http.ResponseWriter, _ *http.Request, _ error) {
			http.Error(w, "Gerrit endpoint unavailable", http.StatusBadGateway)
		},
	}
	return r
}

func (r *router) ServeHTTP(w http.ResponseWriter, req *http.Request) {
	snapshot := r.ready.Load()
	if snapshot == nil || len(snapshot.addresses) == 0 || time.Since(snapshot.updated) > r.maxAge {
		http.Error(w, "No ready Gerrit endpoints", http.StatusServiceUnavailable)
		return
	}
	backend := ""
	if project := projectKey(req); project != "" {
		backend = preferred(project, snapshot.addresses)
	} else {
		i := r.round.Add(1) - 1
		backend = snapshot.addresses[i%uint64(len(snapshot.addresses))]
	}
	r.proxy.ServeHTTP(w, req.WithContext(context.WithValue(req.Context(), targetKey{}, backend)))
}

func preferred(project string, addresses []string) string {
	var winner string
	var highest uint64
	for _, address := range addresses {
		digest := sha256.Sum256([]byte(project + "\x00" + address))
		score := binary.BigEndian.Uint64(digest[:8])
		if winner == "" || score > highest || (score == highest && address < winner) {
			highest, winner = score, address
		}
	}
	return winner
}

type lookupIP func(context.Context, string, string) ([]netip.Addr, error)

func (r *router) refresh(ctx context.Context, lookup lookupIP, service, port string) error {
	ips, err := lookup(ctx, "ip", service)
	if err != nil {
		// Kubernetes can return NXDOMAIN when the last ready pod leaves.
		// On transient DNS errors the last observation expires after maxAge.
		var dnsErr *net.DNSError
		if errors.As(err, &dnsErr) && dnsErr.IsNotFound {
			r.ready.Store(&endpoints{updated: time.Now()})
		}
		return err
	}
	seen := make(map[string]bool)
	var addresses []string
	for _, ip := range ips {
		address := net.JoinHostPort(ip.Unmap().String(), port)
		if !seen[address] {
			seen[address] = true
			addresses = append(addresses, address)
		}
	}
	sort.Strings(addresses)
	r.ready.Store(&endpoints{addresses: addresses, updated: time.Now()})
	return nil
}

func main() {
	service := flag.String("service", "", "ready-only headless Kubernetes Service DNS name")
	port := flag.Int("port", 8080, "Gerrit upstream port")
	listen := flag.String("listen", ":8080", "HTTP listen address behind the ingress")
	scheme := flag.String("scheme", "http", "upstream http or https")
	refresh := flag.Duration("refresh", 5*time.Second, "endpoint DNS refresh interval")
	flag.Parse()
	if *service == "" || (*scheme != "http" && *scheme != "https") || *port <= 0 || *port > 65535 || *refresh <= 0 {
		flag.Usage()
		os.Exit(2)
	}

	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.TLSClientConfig = &tls.Config{ServerName: *service, MinVersion: tls.VersionTLS12}
	membershipLifetime := 3 * (*refresh)
	r := newRouter(*scheme, membershipLifetime, transport)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		ticker := time.NewTicker(*refresh)
		defer ticker.Stop()
		for {
			lookupCtx, cancel := context.WithTimeout(ctx, *refresh)
			err := r.refresh(lookupCtx, net.DefaultResolver.LookupNetIP, *service, strconv.Itoa(*port))
			cancel()
			if err != nil && ctx.Err() == nil {
				log.Printf("discovering Gerrit endpoints: %v", err)
			}
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
		}
	}()
	server := &http.Server{Addr: *listen, Handler: r, ReadHeaderTimeout: 15 * time.Second, IdleTimeout: 2 * time.Minute}
	drained := make(chan struct{})
	go func() {
		defer close(drained)
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownCtx); err != nil {
			log.Print(err)
		}
	}()
	fmt.Fprintln(os.Stderr, "Serving Gerrit HTTP affinity on", *listen)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
	<-drained
}
