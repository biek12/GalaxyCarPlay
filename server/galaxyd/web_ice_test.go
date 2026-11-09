package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestIceCredentialsBoundToViewingSession(t *testing.T) {
	h := newWebHarness(t)
	p := filepath.Join(t.TempDir(), "turn-secret")
	os.WriteFile(p, []byte(strings.Repeat("s", 64)), 0600)
	t.Setenv("GALAXY_TURN_SECRET_FILE", p)
	t.Setenv("GALAXY_TURN_URLS", "turn:example.test:3478?transport=udp")
	a := h.w.iceServers("session-a", h.clock.UnixMilli()+600000)
	b := h.w.iceServers("session-b", h.clock.UnixMilli()+600000)
	if len(a) != 1 || a[0]["username"] == b[0]["username"] || a[0]["credential"] == b[0]["credential"] {
		t.Fatal("TURN credentials not session bound")
	}
	if strings.Contains(a[0]["credential"].(string), strings.Repeat("s", 64)) {
		t.Fatal("shared key exposed")
	}
}
