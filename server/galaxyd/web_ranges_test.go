package main

import (
	"bytes"
	"io"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestDeviceRangeReadsTailAndSmallRequestWithoutUploadingWholeFile(t *testing.T) {
	h := newWebHarness(t)
	h.login("13800000000")
	vid := h.bind()
	payload := bytes.Repeat([]byte("range-test-"), 100000)
	h.device("/index", object{"clips": []webClip{{ClipID: "clip", SessionID: "session", Start: 1, End: 2, Source: "local", Availability: "ok", Channels: []webChannel{{Direction: "front", Status: "ok", Duration: 1}}}}})
	h.device("/media-ready", object{"clipId": "clip", "direction": "front", "size": len(payload)})
	h.w.mu.Lock()
	h.w.rangeQueue(vid)
	h.w.mu.Unlock()
	done := make(chan struct{})
	defer close(done)
	workerDone := make(chan struct{})
	go func() {
		defer close(workerDone)
		for {
			select {
			case <-done:
				return
			case request := <-h.w.rangeQueues[vid]:
				out := httptest.NewRecorder()
				q := httptest.NewRequest("POST", "https://example.test/device/v1/range-result?id="+request.ID, bytes.NewReader(payload[request.Offset:request.Offset+int64(request.Length)]))
				q.Header.Set("X-Galaxy-Device", vid)
				q.Header.Set("Authorization", "Bearer "+strings.Repeat("b", 64))
				h.w.rangeResult(out, q)
			}
		}
	}()
	// Allocate queue before concurrent worker accesses it.
	h.w.mu.Lock()
	h.w.rangeQueue(vid)
	h.w.mu.Unlock()
	get := func(header string) (int, []byte) {
		q := httptest.NewRequest("GET", "https://example.test/web/v1/vehicles/"+vid+"/clips/clip/media/front", nil)
		q.Header.Set("Cookie", "ar_session="+h.cookie)
		q.Header.Set("Range", header)
		out := httptest.NewRecorder()
		h.w.ServeHTTP(out, q)
		return out.Code, out.Body.Bytes()
	}
	head := httptest.NewRequest("HEAD", "https://example.test/web/v1/vehicles/"+vid+"/clips/clip/media/front", nil)
	head.Header.Set("Cookie", "ar_session="+h.cookie)
	headOut := httptest.NewRecorder()
	h.w.ServeHTTP(headOut, head)
	if headOut.Code != 200 || headOut.Body.Len() != 0 {
		t.Fatal("HEAD media metadata failed")
	}
	code, data := get("bytes=0-1")
	if code != 206 || !bytes.Equal(data, payload[:2]) {
		t.Fatalf("first-byte Range %d len=%d", code, len(data))
	}
	code, data = get("bytes=-128")
	if code != 206 || !bytes.Equal(data, payload[len(payload)-128:]) {
		t.Fatal("MP4 tail seek failed")
	}
	if len(h.w.rangePending) != 0 {
		t.Fatal("pending read leaked")
	}
	if _, e := io.ReadAll(bytes.NewReader(data)); e != nil {
		t.Fatal(e)
	}
	// Revocation is checked before every request; a previously prepared FD grants no browser authority.
	h.w.state.Vehicles[vid].Owner = ""
	code, _ = get("bytes=0-1")
	if code != 403 {
		t.Fatalf("revoked reader accepted: %d", code)
	}

}
