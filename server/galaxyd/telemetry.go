package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"time"
)

var eventID = regexp.MustCompile(`^[a-f0-9-]{36}$`)
var ownApps = map[string]bool{"com.ecarx.autoservice": true, "com.ecarx.carplay": true, "com.galaxy.autorecord": true, "com.galaxy.autocluster": true}

// 诊断独立于远程Shell的短授权；只接收已私有部署的登记凭据，不接受匿名上报/查询。
func (r *Relay) telemetry(w http.ResponseWriter, q *http.Request) {
	if !equal(q.Header.Get("X-Galaxy-Enrollment"), r.enrollment) {
		fail(w, 401)
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if q.Method == "GET" {
		r.telemetryRead(w, q)
		return
	}
	q.Body = http.MaxBytesReader(w, q.Body, 4*64*1024+1024) // 四个64KiB事件之外保留JSON外壳/设备ID空间。
	var in struct {
		Device string            `json:"device"`
		Events []json.RawMessage `json:"events"`
	}
	decoder := json.NewDecoder(q.Body)
	if decoder.Decode(&in) != nil || decoder.Decode(&struct{}{}) != io.EOF || !identifier.MatchString(in.Device) || len(in.Events) == 0 || len(in.Events) > 4 {
		fail(w, 400)
		return
	}
	type event struct {
		ID, App, Kind string
		Schema        int
		At            int64
	}
	parsed := make([]event, len(in.Events))
	for i, raw := range in.Events {
		if len(raw) > 64*1024 || json.Unmarshal(raw, &parsed[i]) != nil || !eventID.MatchString(parsed[i].ID) || !ownApps[parsed[i].App] || parsed[i].Schema != 1 || parsed[i].At <= 0 {
			fail(w, 400)
			return
		}
	}
	root := filepath.Join(filepath.Dir(r.path), "diagnostics", in.Device)
	if os.MkdirAll(root, 0700) != nil {
		fail(w, 500)
		return
	}
	accepted := []string{}
	for i, v := range parsed {
		path := filepath.Join(root, v.ID+".json")
		raw := in.Events[i]
		if existing, e := os.ReadFile(path); e == nil {
			if telemetryIdentity(existing) != telemetryIdentity(raw) {
				fail(w, 409)
				return
			}
			accepted = append(accepted, v.ID)
			continue
		}
		f, e := os.OpenFile(path+".tmp", os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
		if e != nil {
			fail(w, 500)
			return
		}
		_, e = f.Write(raw)
		if e == nil {
			e = f.Sync()
		}
		ce := f.Close()
		if e == nil {
			e = ce
		}
		if e == nil {
			e = os.Rename(path+".tmp", path)
		}
		if e != nil {
			fail(w, 500)
			return
		}
		accepted = append(accepted, v.ID)
	}
	// 有界保留：每设备最多1024条/64MB/30天；记录淘汰计数，不保存逐次正常操作。
	entries, _ := os.ReadDir(root)
	type item struct {
		name string
		at   time.Time
		size int64
	}
	items := []item{}
	var size int64
	for _, entry := range entries {
		if filepath.Ext(entry.Name()) != ".json" {
			continue
		}
		info, e := entry.Info()
		if e == nil {
			items = append(items, item{entry.Name(), info.ModTime(), info.Size()})
			size += info.Size()
		}
	}
	sort.Slice(items, func(i, j int) bool { return items[i].at.Before(items[j].at) })
	dropped := 0
	for i, v := range items {
		if len(items)-i <= 1024 && size <= 64*1024*1024 && r.now().Sub(v.at) < 30*24*time.Hour {
			break
		}
		if os.Remove(filepath.Join(root, v.name)) == nil {
			size -= v.size
			dropped++
		}
	}
	if dropped > 0 {
		_ = os.WriteFile(filepath.Join(root, "retention-count"), []byte(strconv.Itoa(dropped)), 0600)
	}
	reply(w, 200, map[string]any{"accepted": accepted})
}
func (r *Relay) telemetryRead(w http.ResponseWriter, q *http.Request) {
	device := q.URL.Query().Get("device")
	if !identifier.MatchString(device) {
		fail(w, 400)
		return
	}
	root := filepath.Join(filepath.Dir(r.path), "diagnostics", device)
	entries, _ := os.ReadDir(root)
	sort.Slice(entries, func(i, j int) bool {
		a, _ := entries[i].Info()
		b, _ := entries[j].Info()
		return a != nil && b != nil && a.ModTime().After(b.ModTime())
	})
	rows := []json.RawMessage{}
	for _, entry := range entries {
		if filepath.Ext(entry.Name()) != ".json" {
			continue
		}
		raw, e := os.ReadFile(filepath.Join(root, entry.Name()))
		if e != nil {
			continue
		}
		var v struct{ App, Kind string }
		if json.Unmarshal(raw, &v) != nil {
			continue
		}
		if app := q.URL.Query().Get("app"); app != "" && app != v.App {
			continue
		}
		if kind := q.URL.Query().Get("kind"); kind != "" && kind != v.Kind {
			continue
		}
		rows = append(rows, raw)
		if len(rows) >= 100 {
			break
		}
	}
	digest := sha256.Sum256([]byte(device))
	reply(w, 200, map[string]any{"events": rows, "installation": hex.EncodeToString(digest[:8])})
}

// ACK丢失后客户端可能重投相同事件；中继接收时间/补充native上下文不同不构成事件冲突。
func telemetryIdentity(raw []byte) [32]byte {
	var value map[string]json.RawMessage
	if json.Unmarshal(raw, &value) != nil {
		return sha256.Sum256(raw)
	}
	delete(value, "receivedAt")
	delete(value, "sourceUid")
	delete(value, "nativeTrace")
	normalized, _ := json.Marshal(value)
	return sha256.Sum256(normalized)
}
