package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func testRelay(t *testing.T) *Relay {
	t.Helper()
	r, e := openRelay(filepath.Join(t.TempDir(), "state.json"))
	if e != nil {
		t.Fatal(e)
	}
	r.enrollment = strings.Repeat("b", 64)
	return r
}
func call(t *testing.T, r *Relay, method, path, bearer string, value any, bootstrap bool) *httptest.ResponseRecorder {
	t.Helper()
	var data []byte
	if v, ok := value.([]byte); ok {
		data = v
	} else if value != nil {
		data, _ = json.Marshal(value)
	}
	q := httptest.NewRequest(method, path, bytes.NewReader(data))
	if bearer != "" {
		q.Header.Set("Authorization", "Bearer "+bearer)
	}
	if bootstrap {
		q.Header.Set("X-Galaxy-Enrollment", r.enrollment)
	}
	w := httptest.NewRecorder()
	r.handler().ServeHTTP(w, q)
	return w
}
func enrolled(t *testing.T, r *Relay) (string, string, string) {
	t.Helper()
	id, secret := strings.Repeat("a", 64), strings.Repeat("c", 64)
	value := map[string]any{"id": id, "secret": secret, "grant": strings.Repeat("d", 64), "until": r.now().Unix() + 43200}
	if w := call(t, r, "POST", "/bridge/register", "", value, false); w.Code != 401 {
		t.Fatal("anonymous enrollment accepted")
	}
	w := call(t, r, "POST", "/bridge/register", "", value, true)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	var status map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &status)
	w = call(t, r, "POST", "/bridge/pair", "", map[string]string{"code": status["code"].(string)}, false)
	if w.Code != 200 {
		t.Fatal(w.Code)
	}
	var paired map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &paired)
	if w = call(t, r, "POST", "/bridge/pair", "", map[string]string{"code": status["code"].(string)}, false); w.Code != 401 {
		t.Fatal("pair code reusable")
	}
	return id, secret, paired["token"].(string)
}
func TestCommandClaimSurvivesRestartAndCannotBeReexecuted(t *testing.T) {
	r := testRelay(t)
	id, secret, client := enrolled(t, r)
	path := "?device=" + id
	job := Job{ID: strings.Repeat("e", 32), Kind: "shell", Command: "id -u", Transport: "ROOT", Timeout: 1000}
	if w := call(t, r, "POST", "/bridge/command"+path, "wrong", job, false); w.Code != 401 {
		t.Fatal(w.Code)
	}
	if w := call(t, r, "POST", "/bridge/command"+path, client, job, false); w.Code != 200 {
		t.Fatal(w.Code)
	}
	w := call(t, r, "POST", "/bridge/poll"+path, secret, map[string]any{}, false)
	if w.Code != 200 {
		t.Fatal(w.Code)
	}
	var claimed Job
	_ = json.Unmarshal(w.Body.Bytes(), &claimed)
	if !claimed.Claimed || claimed.ID != job.ID {
		t.Fatal("job not claimed")
	}
	restored, e := openRelay(r.path)
	if e != nil {
		t.Fatal(e)
	}
	if !restored.devices[id].Jobs[job.ID].Claimed {
		t.Fatal("claim not durable")
	}
	job.Command = "other"
	if w = call(t, r, "POST", "/bridge/command"+path, client, job, false); w.Code != 409 {
		t.Fatal("duplicate changed job accepted")
	}
	payload := map[string]any{"id": job.ID, "result": map[string]any{"output": "0", "exitCode": 0}}
	if w = call(t, r, "POST", "/bridge/result"+path, client, payload, false); w.Code != 401 {
		t.Fatal("client forged device result")
	}
	if w = call(t, r, "POST", "/bridge/result"+path, secret, payload, false); w.Code != 200 {
		t.Fatal(w.Code)
	}
	w = call(t, r, "GET", "/bridge/command"+path+"&id="+job.ID, client, nil, false)
	if !strings.Contains(w.Body.String(), `"output":"0"`) {
		t.Fatal(w.Body.String())
	}
}
func TestExpiryRevocationAndPersistentGrantDoNotRenewWindow(t *testing.T) {
	r := testRelay(t)
	clock := time.Now()
	r.now = func() time.Time { return clock }
	id, secret, client := enrolled(t, r)
	until := r.devices[id].Until
	clock = clock.Add(time.Hour)
	input := map[string]any{"id": id, "secret": secret, "grant": strings.Repeat("d", 64), "until": clock.Unix() + 86400}
	if w := call(t, r, "POST", "/bridge/register", "", input, true); w.Code != 200 {
		t.Fatal(w.Code)
	}
	if r.devices[id].Until != until {
		t.Fatal("background register extended authorization")
	}
	clock = clock.Add(12 * time.Hour)
	if w := call(t, r, "GET", "/bridge/command?device="+id+"&id=x", client, nil, false); w.Code != 401 {
		t.Fatal("expired client accepted")
	}
	clock = clock.Add(-12 * time.Hour)
	if w := call(t, r, "POST", "/bridge/revoke?device="+id, secret, map[string]any{}, false); w.Code != 200 {
		t.Fatal(w.Code)
	}
	if r.valid(r.devices[id]) {
		t.Fatal("revocation did not expire window")
	}
}
func TestArtifactAuthenticationDigestAndPackageAllowlist(t *testing.T) {
	r := testRelay(t)
	id, secret, client := enrolled(t, r)
	data := []byte("apk-fixture")
	sum := sha256.Sum256(data)
	hash := hex.EncodeToString(sum[:])
	artifact := strings.Repeat("f", 32)
	path := "/bridge/artifact?device=" + id + "&id=" + artifact + "&sha256=" + hash
	if w := call(t, r, "PUT", path, secret, data, false); w.Code != 401 {
		t.Fatal("device uploaded as operator")
	}
	if w := call(t, r, "PUT", path, client, []byte("wrong"), false); w.Code != 400 {
		t.Fatal("wrong digest accepted")
	}
	if w := call(t, r, "PUT", path, client, data, false); w.Code != 200 {
		t.Fatal(w.Code)
	}
	w := call(t, r, "GET", path, secret, nil, false)
	if w.Code != 200 || !bytes.Equal(w.Body.Bytes(), data) {
		t.Fatal("artifact mismatch")
	}
	job := Job{ID: strings.Repeat("e", 32), Kind: "install", Timeout: 1000, Artifact: artifact, SHA256: hash, Package: "unrelated.app"}
	if w = call(t, r, "POST", "/bridge/command?device="+id, client, job, false); w.Code != 400 {
		t.Fatal("unrelated package accepted")
	}
	if w = call(t, r, "POST", "/bridge/revoke?device="+id, secret, map[string]any{}, false); w.Code != 200 {
		t.Fatal(w.Code)
	}
	if w = call(t, r, "GET", path, secret, nil, false); w.Code != 401 {
		t.Fatal("revoked download accepted")
	}
	if _, e := os.Stat(r.artifactPath(id, artifact)); !os.IsNotExist(e) {
		t.Fatal("revoked artifact retained")
	}
}
func TestOfflineCommandsExpireBeforeDeviceReturns(t *testing.T) {
	r := testRelay(t)
	clock := time.Now()
	r.now = func() time.Time { return clock }
	id, secret, client := enrolled(t, r)
	query := "?device=" + id
	old := Job{ID: strings.Repeat("e", 32), Kind: "shell", Command: "old", Transport: "ROOT", Timeout: 1000}
	if w := call(t, r, "POST", "/bridge/command"+query, client, old, false); w.Code != 200 {
		t.Fatal(w.Code)
	}
	clock = clock.Add(121 * time.Second)
	fresh := old
	fresh.ID = strings.Repeat("f", 32)
	fresh.Command = "fresh"
	if w := call(t, r, "POST", "/bridge/command"+query, client, fresh, false); w.Code != 200 {
		t.Fatal(w.Code)
	}
	w := call(t, r, "POST", "/bridge/poll"+query, secret, map[string]any{}, false)
	var got Job
	_ = json.Unmarshal(w.Body.Bytes(), &got)
	if got.ID != fresh.ID {
		t.Fatal("expired command dispatched")
	}
	if !bytes.Contains(r.devices[id].Jobs[old.ID].Result, []byte("command_expired")) {
		t.Fatal("expiry result absent")
	}
	clock = clock.Add(13 * time.Hour)
	r.expire()
	if len(r.devices[id].Jobs) != 0 || r.devices[id].Client != "" {
		t.Fatal("expired authorization retained command bodies")
	}
}
