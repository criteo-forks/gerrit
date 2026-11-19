// Copyright 2026 The WalGerrit Authors. Licensed under the Apache License, Version 2.0.

package main

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"reflect"
	"strings"
	"testing"
	"time"
)

type roundTrip func(*http.Request) (*http.Response, error)

func (f roundTrip) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestProjectKey(t *testing.T) {
	for _, tc := range []struct{ method, path, want string }{
		{"POST", "/a/eviltest/small-252.git/git-receive-pack", "eviltest/small-252"},
		{"GET", "/eviltest/small-252/info/refs?service=git-receive-pack", "eviltest/small-252"},
		{"POST", "/a/changes/eviltest%2Fsmall-252~244/revisions/current/review", "eviltest/small-252"},
		{"PUT", "/projects/eviltest%2Fsmall-252/branches/feature", "eviltest/small-252"},
		{"POST", "/changes/244/revisions/current/review", ""},
		{"POST", "/changes/", ""},
		{"GET", "/eviltest/small-252/info/refs?service=git-upload-pack", ""},
		{"POST", "/a/eviltest/small-252/git-upload-pack", ""},
		{"GET", "/projects/eviltest%2Fsmall-252", ""},
	} {
		if got := projectKey(httptest.NewRequest(tc.method, tc.path, nil)); got != tc.want {
			t.Errorf("%s %s project = %q, want %q", tc.method, tc.path, got, tc.want)
		}
	}
}

func TestPreferredSurvivesReorderingAndUnrelatedRemoval(t *testing.T) {
	nodes := []string{"pod-a", "pod-b", "pod-c"}
	winner := preferred("team/repo", nodes)
	if winner == "" || winner != preferred("team/repo", []string{nodes[2], nodes[1], nodes[0]}) {
		t.Fatal("unstable placement")
	}
	remaining := []string{winner}
	for _, n := range nodes {
		if n != winner {
			remaining = append(remaining, n)
			break
		}
	}
	if preferred("team/repo", remaining) != winner || preferred("team/repo", remaining[1:]) == winner {
		t.Fatal("removing an endpoint selected the wrong backend")
	}
}

func TestRealHandlerPreservesRequestAndMovesToRemainingReadyPod(t *testing.T) {
	var seen []string
	r := newRouter("http", time.Minute, roundTrip(func(req *http.Request) (*http.Response, error) {
		seen = append(seen, req.URL.Host)
		if req.URL.EscapedPath() != "/a/changes/team%2Frepo~244/revisions/current/review" || req.Host != "gerrit.test" || req.Header.Get("Authorization") != "Bearer fixture" {
			t.Errorf("wrong upstream message: %s %s", req.Host, req.URL)
		}
		return &http.Response{StatusCode: 200, Header: http.Header{}, Body: io.NopCloser(strings.NewReader("ok"))}, nil
	}))
	pods := []netip.Addr{netip.MustParseAddr("192.0.2.10"), netip.MustParseAddr("192.0.2.11")}
	lookup := func(context.Context, string, string) ([]netip.Addr, error) { return pods, nil }
	push := func() int {
		req := httptest.NewRequest("POST", "https://gerrit.test/a/changes/team%2Frepo~244/revisions/current/review", strings.NewReader("{}"))
		req.Header.Set("Authorization", "Bearer fixture")
		w := httptest.NewRecorder()
		r.ServeHTTP(w, req)
		return w.Code
	}
	if push() != 503 {
		t.Fatal("served without membership")
	}
	if err := r.refresh(context.Background(), lookup, "ignored", "8080"); err != nil {
		t.Fatal(err)
	}
	if push() != 200 || push() != 200 || seen[0] != seen[1] {
		t.Fatal(seen)
	}
	if strings.HasPrefix(seen[0], pods[0].String()+":") {
		pods = pods[1:]
	} else {
		pods = pods[:1]
	}
	if err := r.refresh(context.Background(), lookup, "ignored", "8080"); err != nil {
		t.Fatal(err)
	}
	if push() != 200 || seen[2] == seen[1] {
		t.Fatal(seen)
	}
	r.refresh(context.Background(), func(context.Context, string, string) ([]netip.Addr, error) {
		return nil, &net.DNSError{IsNotFound: true}
	}, "ignored", "8080")
	if push() != 503 {
		t.Fatal("kept endpoint after NXDOMAIN")
	}
}

func TestNoReplayOnAmbiguousWrite(t *testing.T) {
	var called []string
	r := newRouter("http", time.Minute, roundTrip(func(req *http.Request) (*http.Response, error) {
		called = append(called, req.URL.Host)
		return nil, errors.New("response lost after dispatch")
	}))
	nodes := []string{"192.0.2.1:80", "192.0.2.2:80"}
	r.ready.Store(&endpoints{addresses: nodes, updated: time.Now()})
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest("POST", "/repo/git-receive-pack", strings.NewReader("pack")))
	if w.Code != 502 || !reflect.DeepEqual(called, []string{preferred("repo", nodes)}) {
		t.Fatal(w.Code, called)
	}
	r.ready.Store(&endpoints{addresses: nodes, updated: time.Now().Add(-time.Hour)})
	w = httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest("POST", "/repo/git-receive-pack", nil))
	if w.Code != 503 || len(called) != 1 {
		t.Fatal(w.Code, called)
	}
}
