package main

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

func TestTelemetryAuthenticatedDurableDeduplicatedWithoutControlGrant(t *testing.T) {
	r := testRelay(t)
	device := strings.Repeat("a", 64)
	event := map[string]any{"id": "12345678-1234-1234-1234-123456789abc", "schema": 1, "app": "com.galaxy.autorecord", "kind": "recovered_exit", "at": int64(10), "extra": map[string]any{"reason": 5, "trace": "not_available"}}
	payload := map[string]any{"device": device, "events": []any{event}}
	if w := call(t, r, "POST", "/telemetry/events", "", payload, false); w.Code != 401 {
		t.Fatal(w.Code)
	}
	for i := 0; i < 2; i++ {
		if w := call(t, r, "POST", "/telemetry/events", "", payload, true); w.Code != 200 {
			t.Fatal(w.Code, w.Body.String())
		}
	}
	reopened, e := openRelay(r.path)
	if e != nil {
		t.Fatal(e)
	}
	reopened.enrollment = r.enrollment
	w := call(t, reopened, "GET", "/telemetry/events?device="+device, "", nil, true)
	var out struct{ Events []json.RawMessage }
	if json.Unmarshal(w.Body.Bytes(), &out) != nil || len(out.Events) != 1 {
		t.Fatal(w.Body.String())
	}
	if w := call(t, r, "GET", "/telemetry/events?device="+device, "", nil, false); w.Code != 401 {
		t.Fatal(w.Code)
	}
	event["at"] = 11
	if w := call(t, r, "POST", "/telemetry/events", "", payload, true); w.Code != 409 {
		t.Fatal(w.Code)
	}
}
func TestTelemetryRejectsSpoofedOrOversizedAndMalformedRecords(t *testing.T) {
	r := testRelay(t)
	for _, event := range []map[string]any{
		{"id": "../../bad", "schema": 1, "app": "com.galaxy.autorecord", "at": 10},
		{"id": "12345678-1234-1234-1234-123456789abc", "schema": 1, "app": "other.app", "at": 10},
		{"id": "12345678-1234-1234-1234-123456789abc", "schema": 1, "app": "com.galaxy.autorecord", "at": 10, "error": strings.Repeat("x", 65*1024)},
	} {
		w := call(t, r, "POST", "/telemetry/events", "", map[string]any{"device": strings.Repeat("a", 64), "events": []any{event}}, true)
		if w.Code != 400 {
			t.Fatal(w.Code)
		}
	}
}

func TestTelemetryRelayAckLossDoesNotConflictWithReceivingMetadata(t *testing.T) {
	r := testRelay(t)
	device := strings.Repeat("a", 64)
	event := map[string]any{"id": "12345678-1234-1234-1234-123456789abc", "schema": 1, "app": "com.galaxy.autorecord", "kind": "error", "at": 10, "receivedAt": 11, "sourceUid": 10100}
	payload := map[string]any{"device": device, "events": []any{event}}
	if w := call(t, r, "POST", "/telemetry/events", "", payload, true); w.Code != 200 {
		t.Fatal(w.Code)
	}
	event["receivedAt"] = 20
	event["nativeTrace"] = map[string]any{"available": false}
	if w := call(t, r, "POST", "/telemetry/events", "", payload, true); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	event["at"] = 30
	if w := call(t, r, "POST", "/telemetry/events", "", payload, true); w.Code != 409 {
		t.Fatal(w.Code)
	}
}

func TestTelemetryFourMaximumEventsFitEnvelopeBudget(t *testing.T) {
	r := testRelay(t)
	events := []json.RawMessage{}
	for i := 0; i < 4; i++ {
		v := map[string]any{"id": fmt.Sprintf("12345678-1234-1234-1234-%012d", i), "schema": 1, "app": "com.galaxy.autorecord", "kind": "error", "at": 10, "padding": ""}
		raw, _ := json.Marshal(v)
		v["padding"] = strings.Repeat("x", 64*1024-len(raw))
		raw, _ = json.Marshal(v)
		if len(raw) != 64*1024 {
			t.Fatal(len(raw))
		}
		events = append(events, raw)
	}
	w := call(t, r, "POST", "/telemetry/events", "", map[string]any{"device": strings.Repeat("a", 64), "events": events}, true)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
}
