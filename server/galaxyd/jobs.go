package main

import (
	"encoding/json"
	"net/http"
	"os"
	"time"
)

func (r *Relay) command(w http.ResponseWriter, q *http.Request) {
	var in Job
	if !body(w, q, &in) {
		return
	}
	if !identifier.MatchString(in.ID) || in.Timeout < 100 || in.Timeout > 30000 {
		fail(w, 400)
		return
	}
	if in.Kind == "shell" {
		if len(in.Command) < 1 || len(in.Command) > 8192 || (in.Transport != "ROOT" && in.Transport != "ADB") {
			fail(w, 400)
			return
		}
	} else if in.Kind == "install" {
		if !identifier.MatchString(in.Artifact) || !packages[in.Package] || len(in.SHA256) != 64 {
			fail(w, 400)
			return
		}
	} else {
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
	if previous := d.Jobs[in.ID]; previous != nil {
		if previous.Kind != in.Kind || previous.Command != in.Command || previous.Artifact != in.Artifact || previous.SHA256 != in.SHA256 || previous.Package != in.Package || previous.Transport != in.Transport || previous.Timeout != in.Timeout {
			fail(w, 409)
			return
		}
		reply(w, 200, previous)
		return
	}
	if len(d.Jobs) >= 32 {
		for id, j := range d.Jobs {
			// 离线积压和结果丢失也要收敛；只丢弃过期作业，绝不重执行未知结果。
			age := r.now().Unix() - j.Created
			if len(j.Result) == 0 && ((!j.Claimed && age > 120) || (j.Claimed && age > 120+int64(j.Timeout/1000))) {
				delete(d.Jobs, id)
				continue
			}
			if len(j.Result) > 0 {
				delete(d.Jobs, id)
			}
		}
		if len(d.Jobs) >= 32 {
			fail(w, 429)
			return
		}
	}
	in.Claimed = false
	in.Result = nil
	in.Created = r.now().Unix()
	d.Jobs[in.ID] = &in
	if e := r.save(); e != nil {
		delete(d.Jobs, in.ID)
		fail(w, 500)
		return
	}
	select {
	case d.wake <- struct{}{}:
	default:
	}
	reply(w, 200, in)
}
func (r *Relay) poll(w http.ResponseWriter, q *http.Request) {
	deadline := time.NewTimer(20 * time.Second)
	defer deadline.Stop()
	for {
		r.mu.Lock()
		d := r.auth(q, true)
		if d == nil {
			r.mu.Unlock()
			fail(w, 401)
			return
		}
		d.Seen = r.now().Unix()
		var oldest *Job
		for _, j := range d.Jobs {
			if !j.Claimed && r.now().Unix()-j.Created > 120 {
				j.Claimed = true
				j.Result = json.RawMessage(`{"error":"command_expired"}`)
				if e := r.save(); e != nil {
					r.mu.Unlock()
					fail(w, 500)
					return
				}
			}
			if !j.Claimed && len(j.Result) == 0 && (oldest == nil || j.Created < oldest.Created) {
				oldest = j
			}
		}
		if oldest != nil {
			oldest.Claimed = true
			if e := r.save(); e != nil {
				oldest.Claimed = false
				r.mu.Unlock()
				fail(w, 500)
				return
			}
			out := *oldest
			r.mu.Unlock()
			reply(w, 200, out)
			return
		}
		wake := d.wake
		r.mu.Unlock()
		select {
		case <-q.Context().Done():
			return
		case <-deadline.C:
			reply(w, 200, map[string]any{})
			return
		case <-wake:
		}
	}
}
func (r *Relay) submit(w http.ResponseWriter, q *http.Request) {
	var in struct {
		ID     string
		Result json.RawMessage
	}
	if !body(w, q, &in) {
		return
	}
	if len(in.Result) == 0 || len(in.Result) > 192*1024 {
		fail(w, 400)
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	d := r.auth(q, true)
	if d == nil {
		fail(w, 401)
		return
	}
	j := d.Jobs[in.ID]
	if j == nil || !j.Claimed {
		fail(w, 409)
		return
	}
	if len(j.Result) == 0 {
		j.Result = in.Result
	}
	if e := r.save(); e != nil {
		fail(w, 500)
		return
	}
	if j.Kind == "install" && identifier.MatchString(j.Artifact) {
		_ = os.Remove(r.artifactPath(d.ID, j.Artifact))
	}
	reply(w, 200, map[string]bool{"received": true})
}
func (r *Relay) result(w http.ResponseWriter, q *http.Request) {
	r.mu.Lock()
	defer r.mu.Unlock()
	d := r.auth(q, false)
	if d == nil {
		fail(w, 401)
		return
	}
	j := d.Jobs[q.URL.Query().Get("id")]
	if j == nil {
		fail(w, 404)
		return
	}
	reply(w, 200, j)
}
