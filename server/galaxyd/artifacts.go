package main

import (
	"crypto/sha256"
	"encoding/hex"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"time"
)

var packages = map[string]bool{"com.ecarx.autoservice": true, "com.ecarx.carplay": true, "com.galaxy.autocluster": true, "com.ecarx.autoservice.test": true, "com.galaxy.autorecord": true}

func fileDigest(path string) string {
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	h := sha256.New()
	if _, err = io.Copy(h, f); err != nil {
		return ""
	}
	return hex.EncodeToString(h.Sum(nil))
}

// 在途安装与上传不可驱逐；无引用包按时间淘汰，避免失败/放弃安装永久占满配额。
func (r *Relay) artifactPinned(d *Device, id string) bool {
	for _, j := range d.Jobs {
		if j.Kind == "install" && j.Artifact == id && len(j.Result) == 0 {
			return true
		}
	}
	return false
}
func (r *Relay) makeArtifactRoom(d *Device) bool {
	paths, _ := filepath.Glob(filepath.Join(filepath.Dir(r.path), "artifacts", d.ID+"-*"))
	sort.Slice(paths, func(i, j int) bool {
		a, ae := os.Stat(paths[i])
		b, be := os.Stat(paths[j])
		if ae != nil {
			return true
		}
		if be != nil {
			return false
		}
		return a.ModTime().Before(b.ModTime())
	})
	count := len(paths)
	for _, path := range paths {
		info, err := os.Stat(path)
		if err != nil {
			count--
			continue
		}
		name := filepath.Base(path)
		id := name[len(d.ID)+1:]
		part := filepath.Ext(path) == ".part"
		if part {
			if r.uploads[path] {
				continue
			}
		} else {
			if len(id) < len(".apk") || filepath.Ext(path) != ".apk" {
				continue
			}
			id = id[:len(id)-len(".apk")]
			if r.artifactPinned(d, id) {
				continue
			}
			if count < 4 && r.now().Sub(info.ModTime()) < 30*time.Minute {
				continue
			}
		}
		if os.Remove(path) == nil {
			count--
		}
	}
	return count < 4
}

func (r *Relay) artifactPath(device, id string) string {
	return filepath.Join(filepath.Dir(r.path), "artifacts", device+"-"+id+".apk")
}

// 调用者持有mu；撤销、换窗口及到期都清理本设备的临时包，包括未完成上传。
func (r *Relay) clearArtifacts(device string) {
	paths, _ := filepath.Glob(filepath.Join(filepath.Dir(r.path), "artifacts", device+"-*"))
	for _, path := range paths {
		_ = os.Remove(path)
	}
}
func (r *Relay) upload(w http.ResponseWriter, q *http.Request) {
	id := q.URL.Query().Get("id")
	expected := q.URL.Query().Get("sha256")
	if !identifier.MatchString(id) || len(expected) != 64 {
		fail(w, 400)
		return
	}
	r.mu.Lock()
	d := r.auth(q, false)
	if d == nil {
		r.mu.Unlock()
		fail(w, 401)
		return
	}
	device := d.ID
	path := r.artifactPath(device, id)
	if r.uploads[path+".part"] {
		r.mu.Unlock()
		w.Header().Set("Retry-After", "2")
		fail(w, 409)
		return
	}
	if e := os.MkdirAll(filepath.Dir(path), 0700); e != nil {
		r.mu.Unlock()
		fail(w, 500)
		return
	}
	// 重试同一摘要/ID 幂等，不额外占槽；同 ID 不同内容拒绝覆盖。
	if _, err := os.Stat(path); err == nil {
		matches := fileDigest(path) == expected
		r.mu.Unlock()
		if !matches {
			fail(w, 409)
			return
		}
		reply(w, 200, map[string]string{"artifact": id, "sha256": expected})
		return
	}
	if !r.makeArtifactRoom(d) {
		r.mu.Unlock()
		w.Header().Set("Retry-After", "2")
		fail(w, 429)
		return
	}
	f, e := os.OpenFile(path+".part", os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if e == nil {
		if r.uploads == nil {
			r.uploads = map[string]bool{}
		}
		r.uploads[path+".part"] = true
	}
	grant, client := d.Grant, d.Client
	r.mu.Unlock()
	if e != nil {
		fail(w, 409)
		return
	}
	defer func() { r.mu.Lock(); delete(r.uploads, path+".part"); os.Remove(path + ".part"); r.mu.Unlock() }()
	hash := sha256.New()
	q.Body = http.MaxBytesReader(w, q.Body, 128*1024*1024)
	_, e = io.Copy(io.MultiWriter(f, hash), q.Body)
	ce := f.Close()
	if e != nil || ce != nil || hex.EncodeToString(hash.Sum(nil)) != expected {
		fail(w, 400)
		return
	}
	r.mu.Lock()
	// 撤销/换窗口不能在大包传完后复活旧会话的产物。
	valid := r.valid(d) && d.Grant == grant && d.Client == client
	if valid {
		e = os.Rename(path+".part", path)
	}
	r.mu.Unlock()
	if !valid {
		fail(w, 401)
		return
	}
	if e != nil {
		fail(w, 500)
		return
	}
	reply(w, 200, map[string]string{"artifact": id, "sha256": expected})
}

func (r *Relay) deleteArtifact(w http.ResponseWriter, q *http.Request) {
	id := q.URL.Query().Get("id")
	if !identifier.MatchString(id) {
		fail(w, 400)
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	d := r.auth(q, false)
	if d == nil {
		fail(w, 401)
		return
	}
	path := r.artifactPath(d.ID, id)
	if r.artifactPinned(d, id) || r.uploads[path+".part"] {
		fail(w, 409)
		return
	}
	os.Remove(path)
	os.Remove(path + ".part")
	w.WriteHeader(http.StatusNoContent)
}
func (r *Relay) download(w http.ResponseWriter, q *http.Request) {
	id := q.URL.Query().Get("id")
	if !identifier.MatchString(id) {
		fail(w, 400)
		return
	}
	r.mu.Lock()
	d := r.auth(q, true)
	if d == nil {
		r.mu.Unlock()
		fail(w, 401)
		return
	}
	path := r.artifactPath(d.ID, id)
	r.mu.Unlock()
	w.Header().Set("Cache-Control", "no-store")
	http.ServeFile(w, q, path)
}
