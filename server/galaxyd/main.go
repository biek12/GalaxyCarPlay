package main

import (
	"encoding/json"
	"errors"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

var identifier = regexp.MustCompile(`^[a-f0-9]{32,64}$`)

func reply(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
func fail(w http.ResponseWriter, code int) {
	reply(w, code, map[string]string{"error": http.StatusText(code)})
}
func body(w http.ResponseWriter, q *http.Request, v any) bool {
	q.Body = http.MaxBytesReader(w, q.Body, 256*1024)
	d := json.NewDecoder(q.Body)
	if e := d.Decode(v); e != nil {
		fail(w, 400)
		return false
	}
	return true
}
func (r *Relay) auth(q *http.Request, device bool) *Device {
	d := r.devices[q.URL.Query().Get("device")]
	if !r.valid(d) {
		return nil
	}
	expected := d.Client
	if device {
		expected = d.Secret
	}
	if !equal(strings.TrimPrefix(q.Header.Get("Authorization"), "Bearer "), expected) {
		return nil
	}
	return d
}
func (r *Relay) handler() http.Handler {
	m := http.NewServeMux()
	m.HandleFunc("POST /internal/v1/web-invite", r.inviteWeb)
	if dir := os.Getenv("GALAXY_WEB_DIST"); dir != "" {
		m.HandleFunc("/app/", webStatic(dir))
	}
	if r.web != nil {
		m.Handle("/web/v1/", r.web)
		m.HandleFunc("/device/v1/", r.web.device)
	}
	m.HandleFunc("GET /bridge/health", func(w http.ResponseWriter, q *http.Request) { reply(w, 200, map[string]int{"protocol": 1}) })
	m.HandleFunc("POST /bridge/register", r.register)
	m.HandleFunc("POST /bridge/pair", r.pair)
	m.HandleFunc("POST /bridge/poll", r.poll)
	m.HandleFunc("POST /bridge/command", r.command)
	m.HandleFunc("GET /bridge/command", r.result)
	m.HandleFunc("POST /bridge/result", r.submit)
	m.HandleFunc("POST /bridge/revoke", r.revoke)
	m.HandleFunc("PUT /bridge/artifact", r.upload)
	m.HandleFunc("GET /bridge/artifact", r.download)
	m.HandleFunc("DELETE /bridge/artifact", r.deleteArtifact)
	m.HandleFunc("GET /lyrics/search", lyricsSearch)
	m.HandleFunc("POST /telemetry/events", r.telemetry)
	m.HandleFunc("GET /telemetry/events", r.telemetry)
	return m
}
func (r *Relay) register(w http.ResponseWriter, q *http.Request) {
	var in struct {
		ID, Secret, Grant string
		Until             int64
	}
	if !body(w, q, &in) {
		return
	}
	now := r.now().Unix()
	if !identifier.MatchString(in.ID) || len(in.Secret) != 64 || !identifier.MatchString(in.Grant) || in.Until <= now || in.Until > now+86405 {
		fail(w, 400)
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	d := r.devices[in.ID]
	if d != nil && !equal(d.Secret, in.Secret) {
		fail(w, 401)
		return
	}
	if d == nil {
		if !equal(q.Header.Get("X-Galaxy-Enrollment"), r.enrollment) {
			fail(w, 401)
			return
		}
		for id, v := range r.devices {
			if v.Until < now-86400 {
				r.clearArtifacts(id)
				delete(r.devices, id)
			}
		}
		if len(r.devices) >= 16 {
			fail(w, 429)
			return
		}
		d = &Device{ID: in.ID, Secret: in.Secret, Jobs: map[string]*Job{}, wake: make(chan struct{}, 1)}
		r.devices[in.ID] = d
	}
	if d.Grant != in.Grant {
		r.clearArtifacts(d.ID)
		d.Grant = in.Grant
		d.Until = in.Until
		d.Code = code()
		d.CodeUntil = now + 300
		d.Client = ""
		d.Jobs = map[string]*Job{}
	}
	d.Seen = now
	if e := r.save(); e != nil {
		fail(w, 500)
		return
	}
	reply(w, 200, map[string]any{"code": d.Code, "codeUntil": d.CodeUntil, "until": d.Until, "paired": d.Client != ""})
}
func (r *Relay) pair(w http.ResponseWriter, q *http.Request) {
	var in struct{ Code string }
	if !body(w, q, &in) {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	ip, _, _ := net.SplitHostPort(q.RemoteAddr)
	if forwarded := q.Header.Get("X-Real-IP"); forwarded != "" {
		ip = forwarded
	}
	if !r.pairAllowed(ip) {
		fail(w, 429)
		return
	}
	for _, d := range r.devices {
		if r.valid(d) && d.CodeUntil >= r.now().Unix() && equal(in.Code, d.Code) {
			d.Client = token()
			d.Code = ""
			if e := r.save(); e != nil {
				fail(w, 500)
				return
			}
			reply(w, 200, map[string]any{"device": d.ID, "token": d.Client, "until": d.Until})
			return
		}
	}
	fail(w, 401)
}
func (r *Relay) revoke(w http.ResponseWriter, q *http.Request) {
	r.mu.Lock()
	defer r.mu.Unlock()
	d := r.auth(q, true)
	if d == nil {
		fail(w, 401)
		return
	}
	d.Until = 0
	r.clearArtifacts(d.ID)
	d.Code = ""
	d.Client = ""
	d.Jobs = map[string]*Job{}
	if e := r.save(); e != nil {
		fail(w, 500)
		return
	}
	reply(w, 200, map[string]bool{"revoked": true})
}
func main() {
	if len(os.Args) == 3 && os.Args[1] == "web-invite" {
		if e := inviteWebCLI(os.Args[2]); e != nil {
			log.Fatal("invitation failed")
		}
		log.Print("Web invitation saved; TOTP confirmation required")
		return
	}
	path := os.Getenv("GALAXY_STATE")
	if path == "" {
		path = "/var/lib/galaxyd/state.json"
	}
	r, e := openRelay(path)
	if e != nil {
		log.Fatal("state unavailable")
	}

	bootstrap, e := os.ReadFile(os.Getenv("GALAXY_ENROLLMENT_FILE"))
	if e != nil || len(strings.TrimSpace(string(bootstrap))) != 64 {
		log.Fatal("enrollment unavailable")
	}
	r.enrollment = strings.TrimSpace(string(bootstrap))
	lyricCache, e = newLyricCache(filepath.Join(filepath.Dir(path), "lyrics"), 512, 8*1024*1024, time.Now)
	if e != nil {
		log.Fatal("lyric cache unavailable")
	}
	go func() {
		ticker := time.NewTicker(time.Minute)
		defer ticker.Stop()
		for range ticker.C {
			r.expire()
		}
	}()
	listen := os.Getenv("GALAXY_LISTEN")
	if listen == "" {
		listen = "127.0.0.1:8781"
	}
	s := &http.Server{Addr: listen, Handler: r.handler(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 90 * time.Second, WriteTimeout: 5 * time.Minute, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16384}
	if e = s.ListenAndServe(); e != nil && !errors.Is(e, http.ErrServerClosed) {
		log.Fatal("listener stopped")
	}
}
