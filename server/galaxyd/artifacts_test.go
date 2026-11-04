package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestArtifactQuotaEvictsOrphansAndUploadRetryIsIdempotent(t *testing.T) {
	r := testRelay(t)
	device, _, client := enrolled(t, r)
	data := []byte("apk")
	sum := sha256.Sum256(data)
	digest := hex.EncodeToString(sum[:])
	upload := func(n int) int {
		return call(t, r, "PUT", fmt.Sprintf("/bridge/artifact?device=%s&id=%032x&sha256=%s", device, n, digest), client, data, false).Code
	}
	for n := 1; n <= 4; n++ {
		if status := upload(n); status != 200 {
			t.Fatal(status)
		}
	}
	if status := upload(1); status != 200 {
		t.Fatalf("same upload retry consumed quota: %d", status)
	}
	if status := upload(5); status != 200 {
		t.Fatalf("abandoned artifacts permanently blocked installation: %d", status)
	}
	count := 0
	for n := 1; n <= 5; n++ {
		if _, err := os.Stat(r.artifactPath(device, fmt.Sprintf("%032x", n))); err == nil {
			count++
		}
	}
	if count != 4 {
		t.Fatalf("quota must remain bounded: %d", count)
	}
}

func TestArtifactQuotaNeverEvictsPendingInstallAndDeleteRejectsIt(t *testing.T) {
	r := testRelay(t)
	device, _, client := enrolled(t, r)
	data := []byte("apk")
	sum := sha256.Sum256(data)
	digest := hex.EncodeToString(sum[:])
	for n := 1; n <= 4; n++ {
		id := fmt.Sprintf("%032x", n)
		path := "/bridge/artifact?device=" + device + "&id=" + id + "&sha256=" + digest
		if w := call(t, r, "PUT", path, client, data, false); w.Code != 200 {
			t.Fatal(w.Code)
		}
		r.devices[device].Jobs[id] = &Job{ID: id, Kind: "install", Artifact: id}
	}
	if w := call(t, r, "PUT", "/bridge/artifact?device="+device+"&id="+strings.Repeat("e", 32)+"&sha256="+digest, client, data, false); w.Code != 429 || w.Header().Get("Retry-After") == "" {
		t.Fatal("active install quota not protected")
	}
	if w := call(t, r, "DELETE", "/bridge/artifact?device="+device+"&id="+fmt.Sprintf("%032x", 1), client, nil, false); w.Code != 409 {
		t.Fatal("pending install deleted")
	}
}

func TestStalePartCleanupAndAutoRecordInstallAllowlist(t *testing.T) {
	r := testRelay(t)
	device, _, client := enrolled(t, r)
	data := []byte("apk")
	sum := sha256.Sum256(data)
	digest := hex.EncodeToString(sum[:])
	for n := 1; n <= 4; n++ {
		path := r.artifactPath(device, fmt.Sprintf("%032x", n)) + ".part"
		if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte("partial"), 0600); err != nil {
			t.Fatal(err)
		}
		old := r.now() // 进程重启遗留的 .part 无在途 owner，即使刚写过也应清理。
		os.Chtimes(path, old, old)
	}
	id := strings.Repeat("e", 32)
	if w := call(t, r, "PUT", "/bridge/artifact?device="+device+"&id="+id+"&sha256="+digest, client, data, false); w.Code != 200 {
		t.Fatal(w.Code)
	}
	job := Job{ID: strings.Repeat("f", 32), Kind: "install", Timeout: 1000, Artifact: id, Package: "com.galaxy.autorecord", SHA256: digest}
	if w := call(t, r, "POST", "/bridge/command?device="+device, client, job, false); w.Code != 200 {
		t.Fatal("record package missing from installation allowlist", w.Code)
	}
}

func TestOfflineCommandQuotaRecoversWithoutReexecutingOldJobs(t *testing.T) {
	r := testRelay(t)
	device, _, client := enrolled(t, r)
	clock := r.now()
	r.now = func() time.Time { return clock }
	for n := 1; n <= 32; n++ {
		j := Job{ID: fmt.Sprintf("%032x", n), Kind: "shell", Transport: "ROOT", Command: "id -u", Timeout: 1000}
		if w := call(t, r, "POST", "/bridge/command?device="+device, client, j, false); w.Code != 200 {
			t.Fatal(w.Code)
		}
	}
	clock = clock.Add(121 * time.Second)
	j := Job{ID: strings.Repeat("f", 32), Kind: "shell", Transport: "ROOT", Command: "id -u", Timeout: 1000}
	if w := call(t, r, "POST", "/bridge/command?device="+device, client, j, false); w.Code != 200 {
		t.Fatalf("offline queue stuck: %d", w.Code)
	}
	if len(r.devices[device].Jobs) != 1 {
		t.Fatal("expired commands kept for later execution")
	}
}
