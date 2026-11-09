package main

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// Authenticated device outbox. No command, shell, path or vendor credential fields.
func (w *webService) device(out http.ResponseWriter, q *http.Request) {
	if q.URL.Path == "/device/v1/range-poll" {
		w.rangePoll(out, q)
		return
	}
	if q.URL.Path == "/device/v1/range-result" {
		w.rangeResult(out, q)
		return
	}
	if strings.Contains(q.URL.Path, "/media/") && q.Method == "PUT" {
		w.uploadMedia(out, q)
		return
	}
	if q.Method != "POST" {
		webFail(out, 405, "method_not_allowed", "仅支持POST")
		return
	}
	q.Body = http.MaxBytesReader(out, q.Body, 4<<20)
	in := object{}
	if readJSON(q.Body, &in) != nil {
		webFail(out, 422, "invalid", "请求参数无效")
		return
	}
	id := q.Header.Get("X-Galaxy-Device")
	credential := strings.TrimPrefix(q.Header.Get("Authorization"), "Bearer ")
	if !identifier.MatchString(id) || len(credential) != 64 {
		webFail(out, 401, "unauthorized", "设备认证失败")
		return
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	w.gc()
	before, _ := json.Marshal(w.state)
	v := w.state.Vehicles[id]
	path := strings.TrimPrefix(q.URL.Path, "/device/v1")
	if v == nil {
		if path != "/register" || !((w.deviceAuth != nil && w.deviceAuth(id, credential)) || (w.enrollmentAuth != nil && w.enrollmentAuth(q.Header.Get("X-Galaxy-Enrollment")))) {
			webFail(out, 401, "unauthorized", "设备认证失败")
			return
		}
		if len(w.state.Vehicles) >= 100 {
			webFail(out, 429, "device_limit", "设备数量达到上限")
			return
		}
		v = &webVehicle{Device: id, SecretHash: digest(credential), Members: map[string]int64{}, Clips: map[string]webClip{}, Snapshot: object{}, Revision: 1}
		w.state.Vehicles[id] = v
	} else if !equal(v.SecretHash, digest(credential)) {
		webFail(out, 401, "unauthorized", "设备认证失败")
		return
	}
	status, result := w.deviceRoute(id, path, in)
	if e := w.save(); e != nil {
		_ = json.Unmarshal(before, &w.state)
		webFail(out, 503, "state_unavailable", "状态保存失败")
		return
	}
	reply(out, status, result)
}
func (w *webService) deviceRoute(id, path string, in object) (int, any) {
	v := w.state.Vehicles[id]
	now := w.now().UnixMilli()
	bad := func(status int, code, msg string) (int, any) { return status, webError(code, msg) }
	switch path {
	case "/media-ready":
		return w.rangeReady(id, in)
	case "/register":
		return 200, object{"vehicleId": id, "protocol": 1}
	case "/binding-state":
		owner := object{}
		if u := w.state.Users[v.Owner]; u != nil {
			owner = object{"displayName": u.Name, "phoneMasked": maskedPhone(u.Phone)}
		}
		return 200, object{"vehicleId": id, "bound": v.Owner != "", "owner": owner, "basicInfo": v.Snapshot["basicInfo"]}
	case "/unbind":
		w.revokeVehicleStreams(id, "")
		for _, j := range w.state.Jobs {
			if j.Vehicle == id && !terminal(j.Job.State) {
				j.Job.State = "cancelled"
				j.Job.Updated = now
			}
		}
		for _, b := range w.state.Bindings {
			if b.Vehicle == id && inSet(b.Status, "pending", "pending_device_confirmation") {
				b.Status = "expired"
			}
		}
		v.Owner = ""
		v.Members = map[string]int64{}
		return 200, object{"bound": false}

	case "/snapshot":
		snapshot, ok := in["snapshot"].(map[string]any)
		if !ok {
			return bad(422, "invalid", "缺少车辆状态")
		}
		// Allowlist only browser DTOs. Raw vehicle payloads never enter this index or diagnostics.
		filtered := object{}
		for _, k := range []string{"name", "model", "plateMasked", "presence", "capabilities", "recording", "storage", "sentry", "liveSample", "basicInfo"} {
			if x, ok := snapshot[k]; ok {
				filtered[k] = x
			}
		}
		if p, ok := filtered["presence"].(map[string]any); ok {
			state, _ := p["state"].(string)
			if !inSet(state, "online", "sleeping", "waking", "offline", "unknown", "fault") {
				return bad(422, "invalid", "车辆在线状态无效")
			}
		}
		if raw, ok := filtered["basicInfo"].(map[string]any); ok {
			basic := object{}
			for _, key := range []string{"model", "modelSource", "manufacturer", "deviceModel", "androidVersion", "serviceVersion"} {
				if value, ok := raw[key].(string); ok && len(value) <= 128 {
					basic[key] = value
				}
			}
			if sdk, ok := raw["sdk"].(float64); ok && sdk >= 1 && sdk <= 1000 {
				basic["sdk"] = sdk
			}
			filtered["basicInfo"] = basic
		}
		v.Snapshot = filtered
		v.Seen = now
		for _, kind := range []string{"presence", "recording", "sentry"} {
			if payload, ok := filtered[kind].(map[string]any); ok {
				w.emit(kind, id, "", payload)
			}
		}
		return 200, object{"accepted": true, "observedAt": now}
	case "/binding-codes":
		if v.Owner != "" {
			return bad(409, "already_owned", "车辆已有车主")
		}
		for _, b := range w.state.Bindings {
			if b.Vehicle == id && inSet(b.Status, "pending", "pending_device_confirmation") {
				b.Status = "expired"
			}
		}
		raw := token()[:16]
		bid := token()[:32]
		b := &webBinding{ID: bid, Vehicle: id, CodeHash: digest(raw), Status: "pending", Until: now + 300000}
		w.state.Bindings[bid] = b
		return 201, object{"bindingId": bid, "code": raw, "expiresAt": b.Until}
	case "/binding-confirmations":
		bid, _ := in["bindingId"].(string)
		accept, valid := in["accepted"].(bool)
		b := w.state.Bindings[bid]
		if !valid || b == nil || b.Vehicle != id || b.Status != "pending_device_confirmation" || b.Until <= now {
			return bad(409, "binding_invalid", "绑定请求不存在、已处理或已过期")
		}
		if !accept {
			b.Status = "rejected"
			b.Reason = "车端拒绝绑定"
			return 200, w.bindingDTO(b)
		}
		if v.Owner != "" || w.state.Users[b.User] == nil {
			return bad(409, "already_owned", "车辆归属已改变")
		}
		v.Owner = b.User
		b.Status = "confirmed"
		return 200, w.bindingDTO(b)
	case "/index":
		b, _ := json.Marshal(in["clips"])
		var clips []webClip
		if json.Unmarshal(b, &clips) != nil || len(clips) > 4096 {
			return bad(422, "invalid", "录像索引无效或超量")
		}
		next := map[string]webClip{}
		for _, c := range clips {
			if !webID.MatchString(c.ClipID) || !webID.MatchString(c.SessionID) || c.Start < 0 || c.End <= c.Start || !inSet(c.Source, "local", "cloud") || !inSet(c.Availability, "ok", "partial", "recoverable", "missing", "overwritten") || len(c.Channels) > 4 || c.Bytes < 0 {
				return bad(422, "invalid", "录像清单无效")
			}
			seen := map[string]bool{}
			for _, ch := range c.Channels {
				if !inSet(ch.Direction, "front", "rear", "left", "right") || seen[ch.Direction] || !inSet(ch.Status, "ok", "missing", "partial") || ch.Duration < 0 {
					return bad(422, "invalid", "镜头清单无效")
				}
				seen[ch.Direction] = true
			}
			if _, ok := next[c.ClipID]; ok {
				return bad(422, "invalid", "片段标识重复")
			}
			next[c.ClipID] = c
		}
		// Cloud objects survive a full replacement of the local device catalogue.
		for cid, c := range v.Clips {
			if c.Source == "cloud" {
				if _, ok := next[cid]; !ok {
					next[cid] = c
				}
			}
		}
		v.Clips = next
		v.IndexReady = true
		return 200, object{"accepted": len(clips)}
	case "/telemetry":
		cid, _ := in["clipId"].(string)
		if _, ok := v.Clips[cid]; !ok {
			return bad(404, "not_found", "片段不存在")
		}
		data, ok := in["telemetry"].(map[string]any)
		if !ok || data["schema"] == nil || data["samples"] == nil || data["events"] == nil {
			return bad(422, "invalid", "侧录格式无效")
		}
		payload, _ := json.Marshal(data)
		if e := writeWebObject(w.objectPath(id, cid, "telemetry.json"), payload); e != nil {
			return bad(503, "storage_unavailable", "侧录保存失败")
		}
		return 200, object{"accepted": true}
	case "/poll":
		bindings := []object{}
		for _, b := range w.state.Bindings {
			if b.Vehicle == id && b.Status == "pending_device_confirmation" {
				u := w.state.Users[b.User]
				if u != nil {
					bindings = append(bindings, object{"bindingId": b.ID, "displayName": u.Name, "phoneMasked": maskedPhone(u.Phone), "expiresAt": b.Until})
				}
			}
		}
		jobs := []object{}
		for _, j := range w.state.Jobs {
			if j.Vehicle != id || terminal(j.Job.State) || (w.role(j.User, id) != "owner" && !(j.Job.Type == "clip-prepare" && w.role(j.User, id) == "viewer")) {
				continue
			}
			if j.Job.State == "queued" {
				j.Job.State = "dispatched"
				j.Job.Updated = now
				w.emit("job", id, j.Job.ID, object{"state": "dispatched"})
			}
			jobs = append(jobs, object{"jobId": j.Job.ID, "type": j.Job.Type, "expiresAt": j.Job.Until, "payload": j.Payload})
		}
		live := []object{}
		for _, l := range w.state.Live {
			if l.Vehicle == id && w.role(l.User, id) != "" {
				signals := []object{}
				for _, s := range l.Signals {
					if s["from"] == "browser" {
						signals = append(signals, s)
					}
				}
				live = append(live, object{"sessionId": l.ID, "expiresAt": l.Until, "signals": signals, "flat": l.Flat, "iceServers": w.iceServers(l.ID, l.Until)})
			}
		}
		activeMedia := []string{}
		for key, source := range w.rangeSources {
			parts := strings.Split(key, ":")
			if len(parts) == 3 && parts[0] == id && now-source.Seen < 30000 {
				activeMedia = append(activeMedia, parts[1])
			}
		}
		return 200, object{"activeMedia": activeMedia, "bound": v.Owner != "", "bindings": bindings, "jobs": jobs, "liveSessions": live, "sentryRevision": v.Revision, "sentryDesired": v.Desired}
	case "/job-results":
		jid, _ := in["jobId"].(string)
		state, _ := in["state"].(string)
		j := w.state.Jobs[jid]
		if j == nil || j.Vehicle != id || j.Job.Until <= now || inSet(j.Job.State, "expired", "cancelled") {
			return bad(409, "job_expired", "作业已过期或取消，不得执行")
		}
		if terminal(j.Job.State) {
			return 200, object{"accepted": true, "state": j.Job.State}
		}
		if !inSet(state, "running", "waking", "device_online", "ready", "success", "partial_success", "failed") {
			return bad(422, "invalid", "作业状态无效")
		}
		if j.Job.Type != "wake" && inSet(state, "waking", "device_online", "ready") {
			return bad(422, "invalid", "作业状态与类型不匹配")
		}
		if j.Job.Type == "sentry" {
			rev, _ := j.Payload["revision"].(float64)
			if rev != float64(v.Revision) {
				return bad(409, "revision_conflict", "哨兵意图已经改变")
			}
		}
		if j.Job.Type == "sentry" && state == "success" {
			rev, _ := j.Payload["revision"].(float64)
			if rev != float64(v.Revision) {
				return bad(409, "revision_conflict", "较新的哨兵意图已经生效")
			}
			actual, _ := in["actual"].(string)
			if !inSet(actual, "disarmed", "armed_standby", "armed_recording", "restricted", "failed") {
				return bad(422, "invalid", "需要真实哨兵回读状态")
			}
			enabled, _ := j.Payload["enabled"].(bool)
			if enabled && !inSet(actual, "armed_standby", "armed_recording") || !enabled && actual != "disarmed" {
				return bad(409, "not_applied", "实际哨兵状态尚未符合意图")
			}
			v.Snapshot["sentry"] = object{"actual": actual}
			w.emit("sentry", id, "", object{"desired": v.Desired, "actual": actual, "revision": v.Revision})
		}
		if j.Job.Type == "clip-delete" && inSet(state, "success", "partial_success") {
			b, _ := json.Marshal(in["items"])
			var items []object
			if json.Unmarshal(b, &items) != nil {
				return bad(422, "invalid", "缺少逐段删除结果")
			}
			requested := map[string]bool{}
			ids, _ := j.Payload["clipIds"].([]any)
			for _, x := range ids {
				if s, ok := x.(string); ok {
					requested[s] = true
				}
			}
			seen := map[string]bool{}
			hasFailure := false
			for _, item := range items {
				cid, _ := item["ref"].(string)
				st, _ := item["state"].(string)
				if !requested[cid] || seen[cid] || !inSet(st, "success", "skipped", "failed") {
					return bad(422, "invalid", "删除回执与请求不匹配")
				}
				seen[cid] = true
				if st != "success" {
					hasFailure = true
				}
				if c, ok := v.Clips[cid]; ok && st == "success" {
					if c.Locked || c.Source != j.Payload["scope"] {
						return bad(409, "delete_conflict", "锁定片段或删除范围不匹配")
					}
				}
			}
			if len(seen) != len(requested) {
				return bad(422, "invalid", "删除回执不完整")
			}
			for _, item := range items {
				cid := item["ref"].(string)
				if item["state"] == "success" {
					delete(v.Clips, cid)
				}
			}
			if hasFailure {
				state = "partial_success"
			}
			j.Job.Items = items
			j.Job.Progress = object{"done": len(items), "total": len(items)}
		}
		reason, _ := in["reason"].(string)
		if len(reason) > 256 {
			reason = reason[:256]
		}
		j.Job.State = state
		j.Job.Reason = reason
		j.Job.Updated = now
		w.emit("job", id, jid, object{"state": state})
		return 200, object{"accepted": true}
	case "/live-signals":
		lid, _ := in["sessionId"].(string)
		l := w.state.Live[lid]
		if l == nil || l.Vehicle != id || l.Until <= now {
			return bad(404, "not_found", "观看会话不存在")
		}
		kind, _ := in["type"].(string)
		data, _ := in["data"].(string)
		if !inSet(kind, "answer", "ice") || len(data) > 65536 || len(l.Signals) >= 128 {
			return bad(422, "invalid", "信令无效或超量")
		}
		l.Signals = append(l.Signals, object{"id": token()[:16], "from": "device", "type": kind, "data": data})
		return 200, object{}
	case "/live-status":
		lid, _ := in["sessionId"].(string)
		l := w.state.Live[lid]
		if l == nil || l.Vehicle != id {
			return bad(404, "not_found", "观看会话不存在")
		}
		b, _ := json.Marshal(in["channels"])
		var channels []object
		if json.Unmarshal(b, &channels) != nil || len(channels) != 4 {
			return bad(422, "invalid", "需要四路独立状态")
		}
		seen := map[string]bool{}
		ready := false
		for _, ch := range channels {
			d, _ := ch["direction"].(string)
			st, _ := ch["status"].(string)
			if !inSet(d, "front", "rear", "left", "right") || seen[d] || !inSet(st, "ok", "occupied", "unavailable") {
				return bad(422, "invalid", "镜头状态无效")
			}
			seen[d] = true
			ready = ready || st == "ok"
		}
		l.Channels = channels
		l.Ready = ready
		return 200, object{}
	}
	return bad(404, "not_found", "设备接口不存在")
}
func writeWebObject(path string, data []byte) error {
	if e := os.MkdirAll(filepath.Dir(path), 0700); e != nil {
		return e
	}
	f, e := os.OpenFile(path+".tmp", os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if e != nil {
		return e
	}
	if _, e = f.Write(data); e == nil {
		e = f.Sync()
	}
	ce := f.Close()
	if e == nil {
		e = ce
	}
	if e != nil {
		return e
	}
	return os.Rename(path+".tmp", path)
}
func (w *webService) uploadMedia(out http.ResponseWriter, q *http.Request) {
	parts := strings.Split(strings.TrimPrefix(q.URL.Path, "/device/v1/media/"), "/")
	if len(parts) != 2 || !webID.MatchString(parts[0]) || !inSet(parts[1], "front", "rear", "left", "right") {
		webFail(out, 422, "invalid", "媒体标识无效")
		return
	}
	id := q.Header.Get("X-Galaxy-Device")
	credential := strings.TrimPrefix(q.Header.Get("Authorization"), "Bearer ")
	w.mu.Lock()
	v := w.state.Vehicles[id]
	if v == nil || !equal(v.SecretHash, digest(credential)) {
		w.mu.Unlock()
		webFail(out, 401, "unauthorized", "设备认证失败")
		return
	}
	_, ok := v.Clips[parts[0]]
	w.mu.Unlock()
	if !ok {
		webFail(out, 404, "not_found", "片段不存在")
		return
	}
	w.mu.Lock()
	if w.uploading {
		w.mu.Unlock()
		webFail(out, 429, "upload_busy", "媒体上传繁忙")
		return
	}
	w.uploading = true
	w.mu.Unlock()
	defer func() { w.mu.Lock(); w.uploading = false; w.mu.Unlock() }()
	var stored int64
	_ = filepath.WalkDir(w.path+".objects", func(path string, entry os.DirEntry, err error) error {
		if err == nil && !entry.IsDir() {
			if info, e := entry.Info(); e == nil {
				stored += info.Size()
			}
		}
		return nil
	})
	if stored+q.ContentLength > 8<<30 {
		webFail(out, 507, "quota_exceeded", "服务器媒体配额8GiB已满")
		return
	}
	// Explicit uploads only; no automatic upload task is created by browsing a clip.
	if q.ContentLength <= 0 || q.ContentLength > 256<<20 {
		webFail(out, 413, "media_size", "单路媒体须在256MiB以内")
		return
	}
	path := w.objectPath(id, parts[0], parts[1]+".mp4")
	if e := os.MkdirAll(filepath.Dir(path), 0700); e != nil {
		webFail(out, 503, "storage_unavailable", "存储不可用")
		return
	}
	f, e := os.CreateTemp(filepath.Dir(path), ".upload-*")
	if e != nil {
		webFail(out, 503, "storage_unavailable", "存储不可用")
		return
	}
	defer os.Remove(f.Name())
	_ = f.Chmod(0600)
	header := make([]byte, 12)
	_, e = io.ReadFull(q.Body, header)
	if e != nil || string(header[4:8]) != "ftyp" {
		f.Close()
		webFail(out, 422, "invalid_media", "媒体不是MP4")
		return
	}
	_, e = f.Write(header)
	var n int64
	if e == nil {
		n, e = io.Copy(f, io.LimitReader(q.Body, q.ContentLength-12+1))
	}
	if e == nil && n != q.ContentLength-12 {
		e = io.ErrUnexpectedEOF
	}
	if e == nil {
		e = f.Sync()
	}
	ce := f.Close()
	if e == nil {
		e = ce
	}
	if e == nil {
		e = os.Rename(f.Name(), path)
	}
	if e != nil {
		webFail(out, 503, "storage_unavailable", "媒体保存失败")
		return
	}
	reply(out, 201, object{"stored": true, "bytes": q.ContentLength})
}
