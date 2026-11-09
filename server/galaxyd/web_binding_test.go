package main

import (
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestBindingPermanentAndDeviceUnbind(t *testing.T) {
	h := newWebHarness(t)
	h.login("13800000000")
	vid := h.bind()
	h.device("/snapshot", object{"snapshot": object{"model": "L6", "basicInfo": object{"modelSource": "配置", "androidVersion": "11"}}})
	h.clock = h.clock.Add(365 * 24 * time.Hour)
	// 绑定不随临时码、网页会话或诊断窗口过期；换新会话仍是原车主。
	if h.w.state.Vehicles[vid].Owner == "" {
		t.Fatal("binding expired")
	}
	reopened, err := openWeb(h.w.path)
	if err != nil || reopened.state.Vehicles[vid].Owner == "" {
		t.Fatal("binding not persisted across restart")
	}
	h.device("/binding-state", object{})
	h.device("/unbind", object{})
	if h.w.state.Vehicles[vid].Owner != "" {
		t.Fatal("device unbind failed")
	}
	h.device("/binding-codes", object{})
}

func TestBindingSameDeviceIdentityAndConfirmationRequired(t *testing.T) {
	h := newWebHarness(t)
	h.login("13800000000")
	h.w.deviceAuth = func(id, secret string) bool { return true }
	h.device("/register", object{})
	b := h.device("/binding-codes", object{})
	pending := h.expect(202, "POST", "/vehicles/bindings", object{"code": b["code"]}, "new-binding-test")
	if h.w.state.Vehicles[strings.Repeat("a", 32)].Owner != "" {
		t.Fatal("owner before device confirmation")
	}
	h.device("/binding-confirmations", object{"bindingId": pending["bindingId"], "accepted": false})
	h.expect(409, "POST", "/vehicles/bindings", object{"code": b["code"]}, "used-binding-test")
}

func TestWebDeviceRegisterWithoutDiagnosticWindow(t *testing.T) {
	h := newWebHarness(t)
	h.w.enrollmentAuth = func(value string) bool { return value == "private-test-enrollment" }
	request := func(enrollment string) int {
		q := httptest.NewRequest("POST", "https://example.test/device/v1/register", strings.NewReader("{}"))
		q.Header.Set("X-Galaxy-Device", strings.Repeat("a", 32))
		q.Header.Set("Authorization", "Bearer "+strings.Repeat("b", 64))
		q.Header.Set("X-Galaxy-Enrollment", enrollment)
		out := httptest.NewRecorder()
		h.w.device(out, q)
		return out.Code
	}
	if request("wrong") != 401 {
		t.Fatal("unregistered device accepted")
	}
	if request("private-test-enrollment") != 200 {
		t.Fatal("web registration depends on diagnostic window")
	}
	if len(h.w.state.Vehicles) != 1 {
		t.Fatal("duplicate device identity")
	}
	if request("") != 200 {
		t.Fatal("registered identity did not persist")
	}
}

func TestLiveRefreshReplacesOwnLeasesWithoutEvictingOtherSessions(t *testing.T) {
	h := newWebHarness(t)
	h.login("13800000000")
	vid := h.bind()
	p := filepath.Join(t.TempDir(), "turn-secret")
	os.WriteFile(p, []byte(strings.Repeat("x", 64)), 0600)
	t.Setenv("GALAXY_TURN_SECRET_FILE", p)
	t.Setenv("GALAXY_TURN_URLS", "turn:example.test:3478?transport=tcp")
	h.device("/snapshot", object{"snapshot": object{"presence": object{"state": "online"}, "capabilities": object{"liveView": true}}})
	session := h.w.state.Sessions[digest(h.cookie)]
	for _, id := range []string{"stale-a", "stale-b"} {
		h.w.state.Live[id] = &webLive{ID: id, Vehicle: vid, User: session.User, Session: digest(h.cookie), Until: h.clock.Add(time.Minute).UnixMilli()}
	}
	result := h.expect(201, "POST", "/vehicles/"+vid+"/live-sessions", nil, "replace-own-live")
	if len(h.w.state.Live) != 1 {
		t.Fatal("stale own sessions still consume slots")
	}
	if h.w.state.Live[result["sessionId"].(string)] == nil {
		t.Fatal("new session missing")
	}
	for _, id := range []string{"other-a", "other-b"} {
		h.w.state.Sessions[id] = &webSession{ID: id, User: session.User, Until: h.clock.Add(time.Minute).UnixMilli()}
		h.w.state.Live[id] = &webLive{ID: id, Vehicle: vid, User: session.User, Session: id, Until: h.clock.Add(time.Minute).UnixMilli()}
	}
	h.expect(409, "POST", "/vehicles/"+vid+"/live-sessions", nil, "do-not-evict-others")
	if h.w.state.Live["other-a"] == nil || h.w.state.Live["other-b"] == nil {
		t.Fatal("unrelated sessions evicted")
	}
}
