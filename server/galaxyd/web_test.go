package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type webHarness struct {
	t            *testing.T
	w            *webService
	cookie, csrf string
	clock        time.Time
}

func newWebHarness(t *testing.T) *webHarness {
	t.Helper()
	w, e := openWeb(filepath.Join(t.TempDir(), "web.json"))
	if e != nil {
		t.Fatal(e)
	}
	h := &webHarness{t: t, w: w, clock: time.Unix(1793862000, 0)}
	w.now = func() time.Time { return h.clock }
	w.origin = "https://example.test"
	return h
}
func (h *webHarness) req(method, path string, in any, key string) (int, object, *httptest.ResponseRecorder) {
	h.t.Helper()
	data, _ := json.Marshal(in)
	var reader io.Reader
	if in != nil {
		reader = bytes.NewReader(data)
	}
	q := httptest.NewRequest(method, "https://example.test/web/v1"+path, reader)
	q.Header.Set("Origin", h.w.origin)
	if h.cookie != "" {
		q.Header.Set("Cookie", "ar_session="+h.cookie)
	}
	q.Header.Set("X-CSRF-Token", h.csrf)
	if key != "" {
		q.Header.Set("Idempotency-Key", key)
	}
	r := httptest.NewRecorder()
	h.w.ServeHTTP(r, q)
	var result object
	_ = json.Unmarshal(r.Body.Bytes(), &result)
	return r.Code, result, r
}
func (h *webHarness) expect(status int, method, path string, in any, key string) object {
	h.t.Helper()
	s, v, r := h.req(method, path, in, key)
	if s != status {
		h.t.Fatalf("%s %s status %d want %d: %s", method, path, s, status, r.Body.String())
	}
	return v
}
func (h *webHarness) login(phone string) []string {
	h.t.Helper()
	invitation, e := h.w.invite(phone)
	if e != nil {
		h.t.Fatal(e)
	}
	ch := h.expect(200, "POST", "/auth/login", object{"phone": phone}, "")["challengeId"].(string)
	enr := h.expect(200, "POST", "/auth/challenges/"+ch+"/enroll", object{"invitationCode": invitation}, "")
	secret := enr["secret"].(string)
	s, body, r := h.req("POST", "/auth/challenges/"+ch+"/verify", object{"code": totp(secret, h.clock.Unix()/30)}, "")
	if s != 200 {
		h.t.Fatal(r.Body.String())
	}
	for _, c := range r.Result().Cookies() {
		if c.Name == "ar_session" {
			h.cookie = c.Value
		}
		if c.Name == "ar_csrf" {
			h.csrf = c.Value
		}
	}
	codes := []string{}
	for _, x := range body["recoveryCodes"].([]any) {
		codes = append(codes, x.(string))
	}
	return codes
}
func (h *webHarness) reauth() string {
	h.t.Helper()
	h.clock = h.clock.Add(30 * time.Second)
	var u *webUser
	for _, x := range h.w.state.Users {
		u = x
		break
	}
	v := h.expect(200, "POST", "/auth/reauth", object{"code": totp(h.w.unseal(u.Secret), h.clock.Unix()/30)}, "")
	return v["actionTicket"].(string)
}
func TestWebTOTPAndFactorStorage(t *testing.T) {
	if got := totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 1); got != "287082" {
		t.Fatal(got)
	}
	h := newWebHarness(t)
	codes := h.login("13800000000")
	h.expect(200, "GET", "/me", nil, "")
	b, _ := os.ReadFile(h.w.path)
	if bytes.Contains(b, []byte(codes[0])) {
		t.Fatal("plaintext recovery code persisted")
	}
	for _, u := range h.w.state.Users {
		if bytes.Contains(b, []byte(h.w.unseal(u.Secret))) {
			t.Fatal("plaintext factor persisted")
		}
	}
	h.expect(409, "POST", "/me/mfa/disable", object{}, "disable-factor-1")
	// The enrollment verification step is already consumed and cannot reauthenticate.
	for _, u := range h.w.state.Users {
		h.expect(401, "POST", "/auth/reauth", object{"code": totp(h.w.unseal(u.Secret), h.clock.Unix()/30)}, "")
	}
	ticket := h.reauth()
	v := h.expect(200, "POST", "/me/mfa/recovery-codes", object{"actionTicket": ticket}, "recover-key-1")
	h.expect(403, "POST", "/me/mfa/recovery-codes", object{"actionTicket": ticket}, "recover-key-2")
	again := h.expect(200, "POST", "/me/mfa/recovery-codes", object{"actionTicket": ticket}, "recover-key-1")
	if again["recoveryCodes"].([]any)[0] != v["recoveryCodes"].([]any)[0] {
		t.Fatal("idempotency did not replay")
	}
	b, _ = os.ReadFile(h.w.path)
	if bytes.Contains(b, []byte(v["recoveryCodes"].([]any)[0].(string))) {
		t.Fatal("idempotency leaked recovery code")
	}
}
func TestWebRecoveryReplayAndCookieSecurity(t *testing.T) {
	h := newWebHarness(t)
	codes := h.login("13800000000")
	h.expect(200, "POST", "/auth/logout", nil, "")
	h.cookie = ""
	h.csrf = ""
	challenge := func() string {
		return h.expect(200, "POST", "/auth/login", object{"phone": "13800000000"}, "")["challengeId"].(string)
	}
	ch := challenge()
	s, _, r := h.req("POST", "/auth/challenges/"+ch+"/verify", object{"recoveryCode": codes[0]}, "")
	if s != 200 {
		t.Fatal(r.Body.String())
	}
	for _, c := range r.Result().Cookies() {
		if !c.Secure || c.SameSite != http.SameSiteStrictMode {
			t.Fatal("weak cookie")
		}
		if c.Name == "ar_session" && !c.HttpOnly {
			t.Fatal("session readable")
		}
	}
	ch = challenge()
	h.expect(401, "POST", "/auth/challenges/"+ch+"/verify", object{"recoveryCode": codes[0]}, "")
	h.clock = h.clock.Add(6 * time.Minute)
	h.expect(401, "POST", "/auth/challenges/"+ch+"/verify", object{"recoveryCode": codes[1]}, "")
}
func (h *webHarness) device(path string, payload any) object {
	h.t.Helper()
	b, _ := json.Marshal(payload)
	q := httptest.NewRequest("POST", "https://example.test/device/v1"+path, bytes.NewReader(b))
	q.Header.Set("X-Galaxy-Device", strings.Repeat("a", 32))
	q.Header.Set("Authorization", "Bearer "+strings.Repeat("b", 64))
	r := httptest.NewRecorder()
	h.w.device(r, q)
	if r.Code >= 300 {
		h.t.Fatalf("device %s: %s", path, r.Body.String())
	}
	o := object{}
	_ = json.Unmarshal(r.Body.Bytes(), &o)
	return o
}
func (h *webHarness) bind() string {
	h.t.Helper()
	h.w.deviceAuth = func(id, secret string) bool {
		return id == strings.Repeat("a", 32) && secret == strings.Repeat("b", 64)
	}
	h.device("/register", object{})
	b := h.device("/binding-codes", object{})
	v := h.expect(202, "POST", "/vehicles/bindings", object{"code": b["code"]}, "binding-key-1")
	id := v["bindingId"].(string)
	h.expect(200, "GET", "/vehicles/bindings/"+id, nil, "")
	h.device("/binding-confirmations", object{"bindingId": id, "accepted": true})
	h.expect(200, "GET", "/vehicles/bindings/"+id, nil, "")
	return strings.Repeat("a", 32)
}
func TestWebBindingAuthorizationJobsAndSentry(t *testing.T) {
	h := newWebHarness(t)
	h.login("13800000000")
	vid := h.bind()
	base := "/vehicles/" + vid
	h.expect(422, "POST", "/vehicles/bindings", object{"code": "wrong-code"}, "unused-key")
	h.device("/snapshot", object{"snapshot": object{"presence": object{"state": "online"}, "sentry": object{"actual": "disarmed"}}})
	h.expect(503, "POST", base+"/wake-jobs", nil, "wake-test-key")
	h.expect(503, "POST", base+"/live-sessions", nil, "live-test-key")
	first := h.expect(202, "PUT", base+"/sentry-intent", object{"enabled": true, "revision": 1}, "sentry-key-1")
	h.expect(409, "PUT", base+"/sentry-intent", object{"enabled": true, "revision": 1}, "sentry-key-2")
	h.device("/poll", object{})
	h.device("/job-results", object{"jobId": first["jobId"], "state": "success", "actual": "armed_standby"})
	h.expect(200, "GET", "/jobs/"+first["jobId"].(string), nil, "")
	// Unrelated accounts cannot access an ID, clip or job, even if guessed.
	other := token()[:32]
	h.w.state.Users[other] = &webUser{ID: other, Secret: h.w.seal(newTOTP())}
	raw := token()
	h.w.state.Sessions[digest(raw)] = &webSession{ID: token(), User: other, CSRF: "csrf", Until: h.clock.Add(time.Hour).UnixMilli()}
	original := h.cookie
	h.cookie = raw
	h.csrf = "csrf"
	h.expect(403, "GET", base, nil, "")
	h.expect(404, "GET", "/jobs/"+first["jobId"].(string), nil, "")
	h.expect(403, "GET", base+"/clips", nil, "")
	h.w.state.Vehicles[vid].Members[other] = h.clock.UnixMilli()
	h.expect(403, "POST", base+"/clip-delete-jobs", object{"scope": "local", "clipIds": []string{"c1"}}, "viewer-delete-key")
	h.expect(403, "PUT", base+"/sentry-intent", object{"enabled": false, "revision": 2}, "viewer-sentry-key")
	h.cookie = original
}

func TestWebPaginationDeletionAndRestart(t *testing.T) {
	h := newWebHarness(t)
	h.login("13800000000")
	vid := h.bind()
	base := "/vehicles/" + vid
	clips := []webClip{}
	for i := 0; i < 25; i++ {
		clips = append(clips, webClip{ClipID: fmt.Sprintf("c%02d", i), SessionID: "s1", Start: 1000 + int64(i/3), End: 2000 + int64(i), Source: "local", Availability: "ok", Locked: i == 0, Channels: []webChannel{{Direction: "front", Status: "ok", Duration: 1000}}, Telemetry: object{"schema": "v4", "actualIntervalMs": 100, "availability": "none"}})
	}
	h.device("/index", object{"clips": clips})
	first := h.expect(200, "GET", base+"/clips?from=0&until=10000", nil, "")
	if len(first["items"].([]any)) != 12 {
		t.Fatal("page size")
	}
	// Insert at the head and among equal timestamps: stable compound ordering does not repeat.
	clips = append(clips, webClip{ClipID: "new", SessionID: "s1", Start: 3000, End: 4000, Source: "local", Availability: "ok"})
	h.device("/index", object{"clips": clips})
	second := h.expect(200, "GET", base+"/clips?from=0&until=10000&cursor="+first["nextCursor"].(string), nil, "")
	seen := map[string]bool{}
	for _, p := range []object{first, second} {
		for _, x := range p["items"].([]any) {
			id := x.(map[string]any)["clipId"].(string)
			if seen[id] {
				t.Fatal("duplicate page item", id)
			}
			seen[id] = true
		}
	}
	h.expect(422, "GET", base+"/clips?from=9&until=8", nil, "")
	h.expect(200, "GET", base+"/clips/c01", nil, "")
	ticket := h.reauth()
	j := h.expect(202, "POST", base+"/clip-delete-jobs", object{"scope": "local", "clipIds": []string{"c00", "c01", "missing"}, "actionTicket": ticket}, "delete-key-1")
	h.device("/poll", object{})
	h.device("/job-results", object{"jobId": j["jobId"], "state": "partial_success", "items": []object{{"ref": "c00", "state": "skipped", "reason": "locked"}, {"ref": "c01", "state": "success"}, {"ref": "missing", "state": "skipped", "reason": "missing"}}})
	result := h.expect(200, "GET", "/jobs/"+j["jobId"].(string), nil, "")
	if result["state"] != "partial_success" {
		t.Fatal(result)
	}
	h.expect(404, "GET", base+"/clips/c01", nil, "")
	h.expect(200, "GET", base+"/clips/c00", nil, "")
	reopened, e := openWeb(h.w.path)
	if e != nil {
		t.Fatal(e)
	}
	reopened.now = h.w.now
	reopened.origin = h.w.origin
	h.w = reopened
	h.expect(200, "GET", "/jobs/"+j["jobId"].(string), nil, "")
	h.expect(404, "GET", base+"/clips/c01", nil, "")
}
func TestWebCSRFRevocationAndMFAReplacement(t *testing.T) {
	h := newWebHarness(t)
	h.login("13800000000")
	csrf := h.csrf
	h.csrf = "wrong"
	h.expect(403, "POST", "/auth/logout", nil, "")
	h.csrf = csrf
	q := httptest.NewRequest("POST", "https://example.test/web/v1/auth/login", strings.NewReader(`{"phone":"13800000000"}`))
	q.Header.Set("Origin", "https://evil.test")
	r := httptest.NewRecorder()
	h.w.ServeHTTP(r, q)
	if r.Code != 403 {
		t.Fatal("cross origin accepted")
	}
	h.expect(403, "POST", "/me/mfa/enrollments", object{}, "enroll-key-0")
	ticket := h.reauth()
	enr := h.expect(200, "POST", "/me/mfa/enrollments", object{"actionTicket": ticket}, "enroll-key-1")
	secret := enr["secret"].(string)
	h.expect(200, "POST", "/me/mfa/enrollments/"+enr["enrollmentId"].(string)+"/confirm", object{"code": totp(secret, h.clock.Unix()/30)}, "confirm-key-1")
	me := h.expect(200, "GET", "/me", nil, "")
	if me["mfaEnabled"] != true {
		t.Fatal(me)
	}
	h.expect(200, "POST", "/auth/logout", nil, "")
	h.expect(401, "GET", "/me", nil, "")
}

func TestDeviceHTTPBoundaryMediaRangeAndCloudDeletion(t *testing.T) {
	h := newWebHarness(t)
	h.login("13800000000")
	vid := h.bind()
	base := "/vehicles/" + vid
	h.device("/index", object{"clips": []webClip{{ClipID: "clip", SessionID: "session", Start: 1, End: 1000, Source: "cloud", Availability: "ok", Channels: []webChannel{{Direction: "front", Status: "ok", Duration: 999}}}}})
	data := append([]byte{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}, bytes.Repeat([]byte{3}, 100)...)
	q := httptest.NewRequest("PUT", "https://example.test/device/v1/media/clip/front", bytes.NewReader(data))
	q.Header.Set("X-Galaxy-Device", vid)
	q.Header.Set("Authorization", "Bearer "+strings.Repeat("b", 64))
	r := httptest.NewRecorder()
	h.w.device(r, q)
	if r.Code != 201 {
		t.Fatal(r.Body.String())
	}
	q = httptest.NewRequest("GET", "https://example.test/web/v1"+base+"/clips/clip/media/front", nil)
	q.Header.Set("Cookie", "ar_session="+h.cookie)
	q.Header.Set("Range", "bytes=12-19")
	r = httptest.NewRecorder()
	h.w.ServeHTTP(r, q)
	if r.Code != 206 || r.Body.Len() != 8 {
		t.Fatalf("range status=%d bytes=%d", r.Code, r.Body.Len())
	}
	q.Header.Del("Cookie")
	r = httptest.NewRecorder()
	h.w.ServeHTTP(r, q)
	if r.Code != 401 {
		t.Fatal("unauthenticated media access")
	}
	// Cloud deletion operates on server objects and never dispatches a local-file deletion.
	ticket := h.reauth()
	job := h.expect(202, "POST", base+"/clip-delete-jobs", object{"scope": "cloud", "clipIds": []string{"clip"}, "actionTicket": ticket}, "cloud-delete-key")
	got := h.expect(200, "GET", "/jobs/"+job["jobId"].(string), nil, "")
	if got["state"] != "success" {
		t.Fatal(got)
	}
	if _, e := os.Stat(h.w.objectPath(vid, "clip", "front.mp4")); !os.IsNotExist(e) {
		t.Fatal("cloud file survived deletion")
	}
}
func TestDeviceLateJobRejectedAndBindingExpired(t *testing.T) {
	h := newWebHarness(t)
	h.login("13800000000")
	vid := h.bind()
	base := "/vehicles/" + vid
	first := h.expect(202, "PUT", base+"/sentry-intent", object{"enabled": true, "revision": 1}, "intent-first-key")
	h.expect(202, "PUT", base+"/sentry-intent", object{"enabled": false, "revision": 2}, "intent-second-key")
	b, _ := json.Marshal(object{"jobId": first["jobId"], "state": "success", "actual": "armed_standby"})
	q := httptest.NewRequest("POST", "https://example.test/device/v1/job-results", bytes.NewReader(b))
	q.Header.Set("X-Galaxy-Device", vid)
	q.Header.Set("Authorization", "Bearer "+strings.Repeat("b", 64))
	r := httptest.NewRecorder()
	h.w.device(r, q)
	if r.Code != 409 {
		t.Fatal("cancelled intent applied", r.Body.String())
	}
	h.clock = h.clock.Add(6 * time.Minute)
	h.w.gc()
	job := h.expect(200, "GET", "/jobs/"+first["jobId"].(string), nil, "")
	if job["state"] != "cancelled" {
		t.Fatal(job)
	}
}
func TestWebStaticHistoryFallbackAndAssets(t *testing.T) {
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "index.html"), []byte("<html>app</html>"), 0600)
	_ = os.Mkdir(filepath.Join(dir, "assets"), 0700)
	_ = os.WriteFile(filepath.Join(dir, "assets", "main.js"), []byte("const a=1"), 0600)
	for path, want := range map[string]int{"/app/": 200, "/app/vehicles/example": 200, "/app/assets/main.js": 200, "/app/assets/missing.js": 404} {
		r := httptest.NewRecorder()
		webStatic(dir)(r, httptest.NewRequest("GET", path, nil))
		if r.Code != want {
			t.Fatalf("%s=%d", path, r.Code)
		}
	}
}

func TestInvitationRequiredBeforeSecretAndConsumed(t *testing.T) {
	h := newWebHarness(t)
	invitation, e := h.w.invite("13800000000")
	if e != nil {
		t.Fatal(e)
	}
	ch := h.expect(200, "POST", "/auth/login", object{"phone": "13800000000"}, "")["challengeId"].(string)
	h.expect(403, "POST", "/auth/challenges/"+ch+"/enroll", object{}, "")
	enr := h.expect(200, "POST", "/auth/challenges/"+ch+"/enroll", object{"invitationCode": invitation}, "")
	h.expect(409, "POST", "/auth/challenges/"+ch+"/enroll", object{"invitationCode": invitation}, "")
	secret := enr["secret"].(string)
	h.expect(200, "POST", "/auth/challenges/"+ch+"/verify", object{"code": totp(secret, h.clock.Unix()/30)}, "")
	for _, u := range h.w.state.Users {
		if u.EnrollmentHash != "" {
			t.Fatal("invitation survived enrollment")
		}
	}
	if _, e = h.w.invite("13800000000"); e == nil {
		t.Fatal("invite reset existing factor")
	}
}
