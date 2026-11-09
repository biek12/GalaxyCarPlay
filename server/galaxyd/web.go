package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func webError(code, message string) object {
	return object{"code": code, "message": message, "requestId": token()[:16]}
}
func webFail(out http.ResponseWriter, status int, code, message string) {
	reply(out, status, webError(code, message))
}
func (w *webService) ServeHTTP(out http.ResponseWriter, q *http.Request) {
	path := strings.TrimPrefix(q.URL.Path, "/web/v1")
	out.Header().Set("X-Content-Type-Options", "nosniff")
	if q.Method == "GET" && path == "/events" {
		w.events(out, q)
		return
	}
	if (q.Method == "GET" || q.Method == "HEAD") && strings.Contains(path, "/media/") {
		w.media(out, q)
		return
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	w.gc()
	sid, s, u := w.session(q)
	mutable := q.Method != "GET" && q.Method != "HEAD"
	if mutable {
		if q.Header.Get("Origin") != w.origin {
			webFail(out, 403, "origin_rejected", "请求来源无效")
			return
		}
		if s != nil && !equal(q.Header.Get("X-CSRF-Token"), s.CSRF) {
			webFail(out, 403, "csrf_rejected", "请求校验失败，请刷新页面")
			return
		}
	}
	public := q.Method == "POST" && (path == "/auth/login" || strings.HasPrefix(path, "/auth/challenges/"))
	if !public && u == nil {
		webFail(out, 401, "unauthorized", "未登录或会话已失效")
		return
	}
	in := object{}
	if mutable && q.ContentLength != 0 {
		q.Body = http.MaxBytesReader(out, q.Body, 1<<20)
		if e := readJSON(q.Body, &in); e != nil {
			webFail(out, 422, "invalid", "请求参数无效")
			return
		}
	}
	idem := ""
	finger := ""
	if mutable && !public && !strings.HasPrefix(path, "/auth/") {
		key := q.Header.Get("Idempotency-Key")
		if key == "" {
			key = q.Header.Get("X-Idempotency-Key")
		}
		if len(key) < 8 || len(key) > 128 {
			webFail(out, 422, "idempotency_required", "修改操作需要幂等请求标识")
			return
		}
		b, _ := json.Marshal(in)
		finger = digest(q.Method + " " + q.URL.RequestURI() + string(b))
		idem = sid + ":" + key
		p := strings.Split(strings.Trim(path, "/"), "/")
		if len(p) >= 2 && p[0] == "vehicles" && p[1] != "bindings" && w.role(u.ID, p[1]) == "" {
			webFail(out, 403, "forbidden", "无权访问该车辆")
			return
		}
		if cached, ok := w.state.Idempotency[idem]; ok {
			if cached.Fingerprint != finger {
				webFail(out, 409, "idempotency_conflict", "同一请求标识不能用于不同操作")
				return
			}
			var result any
			if json.Unmarshal([]byte(w.unseal(cached.Body)), &result) != nil {
				webFail(out, 500, "state_unavailable", "服务状态暂不可用")
				return
			}
			reply(out, cached.Status, result)
			return
		}
	}
	if mutable && !public && (len(w.state.Idempotency) >= 4096 || w.limited("write:"+u.ID, 120)) {
		webFail(out, 429, "throttled", "修改请求过多，请稍后重试")
		return
	}
	before, _ := json.Marshal(w.state)
	status, result, handled := w.authRoute(out, q, path, in)
	if !handled && u != nil {
		status, result = w.business(q, path, in, sid, s, u)
	}
	if status == 0 {
		status = 404
		result = webError("not_found", "接口不存在")
	}
	// Failed auth attempts and consumed recovery codes are durable too.
	if mutable && idem != "" && status < 500 {
		b, _ := json.Marshal(result)
		w.state.Idempotency[idem] = webIdem{Fingerprint: finger, Status: status, Body: w.seal(string(b)), Until: w.now().UnixMilli() + 300000}
	}
	if mutable || path == "/me" {
		if e := w.save(); e != nil {
			_ = json.Unmarshal(before, &w.state)
			out.Header().Del("Set-Cookie")
			webFail(out, 503, "state_unavailable", "状态保存失败，请重试")
			return
		}
	}
	if status == 429 {
		out.Header().Set("Retry-After", "60")
		if e, ok := result.(object); ok {
			e["retryAfterMs"] = 60000
		}
	}
	reply(out, status, result)
}
func (w *webService) vehicleDTO(id, user string) object {
	v := w.state.Vehicles[id]
	o := clone(v.Snapshot)
	o["vehicleId"] = id
	o["role"] = w.role(user, id)
	if o["name"] == nil {
		o["name"] = "AutoRecord"
	}
	if o["model"] == nil {
		o["model"] = ""
	}
	if o["plateMasked"] == nil {
		o["plateMasked"] = ""
	}
	presence, _ := o["presence"].(map[string]any)
	if presence == nil {
		presence = object{"state": "unknown"}
	}
	presence["lastSeenAt"] = v.Seen
	if w.now().UnixMilli()-v.Seen > 45000 {
		presence["state"] = "offline"
		o["recording"] = object{"state": "unknown", "observedAt": v.Seen}
	}
	o["presence"] = presence
	caps, _ := o["capabilities"].(map[string]any)
	if caps == nil {
		caps = object{}
	}
	if caps["remoteWake"] == nil {
		caps["remoteWake"] = object{"supported": false, "reason": "车端尚未完成远程电源唤醒适配"}
	}
	if caps["flatView"] == nil {
		caps["flatView"] = object{"front": false, "rear": false, "left": false, "right": false}
	}
	o["capabilities"] = caps
	if o["storage"] == nil {
		o["storage"] = object{"volume": "未知", "usedBytes": 0, "quotaBytes": 0, "freeBytes": 0}
	}
	if o["recording"] == nil {
		o["recording"] = object{"state": "unknown", "observedAt": v.Seen}
	}
	sentry, _ := o["sentry"].(map[string]any)
	if sentry == nil {
		sentry = object{"actual": "unknown"}
	}
	sentry["desired"] = v.Desired
	sentry["revision"] = v.Revision
	o["sentry"] = sentry
	return o
}
func (w *webService) newJob(kind, vid, uid string, payload object) *webJobRecord {
	now := w.now().UnixMilli()
	id := token()[:32]
	j := &webJobRecord{Job: webJob{ID: id, Type: kind, State: "queued", Created: now, Updated: now, Until: now + 300000}, Vehicle: vid, User: uid, Payload: clone(payload)}
	w.state.Jobs[id] = j
	w.emit("job", vid, id, object{"state": "queued"})
	return j
}
func (w *webService) business(q *http.Request, path string, in object, sid string, s *webSession, u *webUser) (int, any) {
	parts := strings.Split(strings.Trim(path, "/"), "/")
	method := q.Method
	bad := func(status int, code, msg string) (int, any) { return status, webError(code, msg) }
	str := func(k string) string { v, _ := in[k].(string); return v }
	if method == "POST" && path == "/vehicles/bindings" {
		if w.limited("bind:"+u.ID, 10) {
			return bad(429, "throttled", "绑定尝试过多")
		}
		hash := digest(strings.TrimSpace(str("code")))
		for _, b := range w.state.Bindings {
			if !equal(hash, b.CodeHash) {
				continue
			}
			if b.Until <= w.now().UnixMilli() {
				return bad(422, "binding_expired", "绑定码已过期，请在车端重新生成")
			}
			v := w.state.Vehicles[b.Vehicle]
			if b.Status != "pending" || v == nil || v.Owner != "" {
				return bad(409, "binding_used", "绑定码已使用或车辆已有车主")
			}
			b.User = u.ID
			b.Status = "pending_device_confirmation"
			return 202, w.bindingDTO(b)
		}
		return bad(422, "invalid", "绑定码无效或已过期")
	}
	if method == "GET" && len(parts) == 3 && parts[0] == "vehicles" && parts[1] == "bindings" {
		b := w.state.Bindings[parts[2]]
		if b == nil || b.User != u.ID {
			return bad(404, "not_found", "绑定请求不存在")
		}
		return 200, w.bindingDTO(b)
	}
	if method == "GET" && path == "/vehicles" {
		list := []object{}
		for id := range w.state.Vehicles {
			if w.role(u.ID, id) != "" {
				list = append(list, w.vehicleDTO(id, u.ID))
			}
		}
		return 200, list
	}
	if len(parts) == 2 && parts[0] == "jobs" && method == "GET" {
		j := w.state.Jobs[parts[1]]
		if j == nil || j.User != u.ID || w.role(u.ID, j.Vehicle) == "" {
			return bad(404, "not_found", "作业不存在")
		}
		return 200, j.Job
	}
	if len(parts) < 2 || parts[0] != "vehicles" {
		return bad(404, "not_found", "接口不存在")
	}
	vid := parts[1]
	role := w.role(u.ID, vid)
	if role == "" {
		return bad(403, "forbidden", "无权访问该车辆")
	}
	v := w.state.Vehicles[vid]
	if len(parts) == 2 && method == "GET" {
		return 200, w.vehicleDTO(vid, u.ID)
	}
	if len(parts) < 3 {
		return bad(404, "not_found", "接口不存在")
	}
	owner := func() bool { return role == "owner" }
	online := w.now().UnixMilli()-v.Seen <= 45000
	if parts[2] == "live-sample" && len(parts) == 3 && method == "GET" {
		if !online {
			return 200, nil
		}
		return 200, v.Snapshot["liveSample"]
	}
	if parts[2] == "access" {
		if len(parts) == 3 && method == "GET" {
			name := "车主"
			if x := w.state.Users[v.Owner]; x != nil {
				name = x.Name
			}
			members := []object{}
			if owner() {
				for id, at := range v.Members {
					if m := w.state.Users[id]; m != nil {
						members = append(members, object{"userId": id, "displayName": m.Name, "role": "viewer", "grantedAt": at})
					}
				}
			}
			return 200, object{"ownerId": v.Owner, "ownerName": name, "role": role, "members": members}
		}
		if len(parts) == 5 && parts[3] == "members" && method == "DELETE" {
			if !owner() {
				return bad(403, "forbidden", "仅车主可管理成员")
			}
			if !w.ticket(sid, str("actionTicket")) {
				return bad(403, "reauth_required", "请重新认证")
			}
			delete(v.Members, parts[4])
			w.revokeVehicleStreams(vid, parts[4])
			return 200, object{}
		}
		if len(parts) == 4 && parts[3] == "members" && method == "POST" {
			if !owner() {
				return bad(403, "forbidden", "仅车主可管理成员")
			}
			target := str("userId")
			member := w.state.Users[target]
			if member == nil || member.Secret == "" || target == v.Owner {
				return bad(422, "invalid", "需要已启用验证器的有效账号")
			}
			if !w.ticket(sid, str("actionTicket")) {
				return bad(403, "reauth_required", "请重新认证")
			}
			v.Members[target] = w.now().UnixMilli()
			return 200, object{}
		}
	}
	if parts[2] == "unbind" && len(parts) == 3 && method == "POST" {
		if !owner() {
			return bad(403, "forbidden", "仅车主可解绑")
		}
		if !w.ticket(sid, str("actionTicket")) {
			return bad(403, "reauth_required", "请重新认证")
		}
		w.revokeVehicleStreams(vid, "")
		for _, j := range w.state.Jobs {
			if j.Vehicle == vid && !terminal(j.Job.State) {
				j.Job.State = "cancelled"
				j.Job.Updated = w.now().UnixMilli()
			}
		}
		v.Owner = ""
		v.Members = map[string]int64{}
		for _, b := range w.state.Bindings {
			if b.Vehicle == vid && b.Status == "pending_device_confirmation" {
				b.Status = "rejected"
			}
		}
		return 200, object{}
	}
	if parts[2] == "wake-jobs" && len(parts) == 3 && method == "POST" {
		for _, j := range w.state.Jobs {
			if j.Vehicle == vid && j.User == u.ID && j.Job.Type == "wake" && !terminal(j.Job.State) {
				return 202, object{"jobId": j.Job.ID}
			}
		}

		if !owner() {
			return bad(403, "forbidden", "仅车主可唤醒车辆")
		}
		dto := w.vehicleDTO(vid, u.ID)
		caps := dto["capabilities"].(map[string]any)
		wake, _ := caps["remoteWake"].(map[string]any)
		if wake == nil || wake["supported"] != true {
			return bad(503, "capability_unavailable", "车端尚未完成远程电源唤醒适配")
		}
		if online {
			return bad(409, "conflict", "车辆已在线")
		}
		j := w.newJob("wake", vid, u.ID, object{})
		return 202, object{"jobId": j.Job.ID}
	}
	if len(w.state.Jobs) >= 2048 && method != "GET" {
		return bad(429, "job_limit", "作业数量达到上限")
	}
	if parts[2] == "sentry-intent" && len(parts) == 3 && method == "PUT" {
		if !owner() {
			return bad(403, "forbidden", "观看者无权更改哨兵")
		}
		enabled, ok := in["enabled"].(bool)
		revision, valid := in["revision"].(float64)
		if !ok || !valid || revision != float64(v.Revision) {
			return bad(409, "revision_conflict", "哨兵状态已更新，请刷新后重试")
		}
		v.Desired = enabled
		v.Revision++
		// A newer intent supersedes pending work, including work already delivered to the device.
		for _, j := range w.state.Jobs {
			if j.Vehicle == vid && j.Job.Type == "sentry" && !terminal(j.Job.State) {
				j.Job.State = "cancelled"
				j.Job.Updated = w.now().UnixMilli()
			}
		}
		j := w.newJob("sentry", vid, u.ID, object{"enabled": enabled, "revision": v.Revision})
		w.emit("sentry", vid, "", object{"desired": enabled, "revision": v.Revision})
		return 202, object{"jobId": j.Job.ID, "revision": v.Revision}
	}
	if parts[2] == "clips" && len(parts) == 5 && parts[4] == "prepare" && method == "POST" {
		c, ok := v.Clips[parts[3]]
		if !ok {
			return bad(404, "not_found", "片段不存在或已覆盖")
		}
		if !online {
			return bad(503, "device_unavailable", "车端离线，暂无法传输原片")
		}
		for _, j := range w.state.Jobs {
			if j.Vehicle == vid && j.User == u.ID && j.Job.Type == "clip-prepare" && j.Payload["clipId"] == c.ClipID && !terminal(j.Job.State) {
				return 202, object{"jobId": j.Job.ID}
			}
		}
		j := w.newJob("clip-prepare", vid, u.ID, object{"clipId": c.ClipID})
		return 202, object{"jobId": j.Job.ID}
	}
	if parts[2] == "clips" {
		return w.clips(q, parts, in, u.ID)
	}
	if parts[2] == "clip-delete-jobs" && len(parts) == 3 && method == "POST" {
		if !owner() {
			return bad(403, "forbidden", "观看者无权删除录像")
		}
		scope := str("scope")
		ids, ok := in["clipIds"].([]any)
		if !ok || len(ids) == 0 || len(ids) > 100 || (scope != "local" && scope != "cloud") {
			return bad(422, "invalid", "请选择1至100段录像及删除范围")
		}
		unique := map[string]bool{}
		for _, id := range ids {
			s, ok := id.(string)
			if !ok || !webID.MatchString(s) || unique[s] {
				return bad(422, "invalid", "片段标识无效或重复")
			}
			unique[s] = true
		}
		if !w.ticket(sid, str("actionTicket")) {
			return bad(403, "reauth_required", "请重新认证")
		}
		j := w.newJob("clip-delete", vid, u.ID, object{"scope": scope, "clipIds": ids})
		if scope == "cloud" {
			items := []object{}
			failed := false
			for _, raw := range ids {
				cid := raw.(string)
				c, exists := v.Clips[cid]
				item := object{"ref": cid, "state": "success"}
				switch {
				case !exists:
					item["state"] = "skipped"
					item["reason"] = "片段不存在"
				case c.Source != "cloud":
					item["state"] = "skipped"
					item["reason"] = "该片段只有车端原片"
				case c.Locked:
					item["state"] = "skipped"
					item["reason"] = "片段已锁定"
				case w.mediaReaders[vid+":"+cid] > 0:
					item["state"] = "failed"
					item["reason"] = "片段正在播放"
				default:
					if err := os.RemoveAll(filepath.Dir(w.objectPath(vid, cid, "telemetry.json"))); err != nil {
						item["state"] = "failed"
						item["reason"] = "云端删除失败"
					} else {
						delete(v.Clips, cid)
					}
				}
				if item["state"] != "success" {
					failed = true
				}
				items = append(items, item)
			}
			j.Job.Items = items
			j.Job.Progress = object{"done": len(items), "total": len(items)}
			j.Job.State = "success"
			if failed {
				j.Job.State = "partial_success"
			}
			w.emit("job", vid, j.Job.ID, object{"state": j.Job.State})
		}

		return 202, object{"jobId": j.Job.ID}
	}
	if parts[2] == "live-sessions" {
		if len(parts) == 3 && method == "POST" {
			if !online {
				return bad(503, "device_unavailable", "车辆不在线")
			}
			caps, _ := v.Snapshot["capabilities"].(map[string]any)
			if caps == nil || caps["liveView"] != true {
				return bad(503, "capability_unavailable", "车端直播编码与信令尚未就绪")
			}
			// 浏览器刷新/网络断开可能来不及DELETE；同一登录会话只保留当前一次观看。
			// 不能回收其他登录会话，两个浏览器仍受并发限制。
			for oldID, previous := range w.state.Live {
				if previous.Vehicle == vid && previous.User == u.ID && previous.Session == sid {
					delete(w.state.Live, oldID)
				}
			}
			n := 0
			for _, s := range w.state.Live {
				if s.Vehicle == vid {
					n++
				}
			}
			if n >= 2 {
				return bad(409, "stream_limit", "观看会话已达上限")
			}
			id := token()[:32]
			channels := []object{}
			for _, d := range []string{"front", "rear", "left", "right"} {
				channels = append(channels, object{"direction": d, "status": "unavailable"})
			}
			l := &webLive{ID: id, Vehicle: vid, User: u.ID, Session: sid, Until: w.now().UnixMilli() + 600000, Channels: channels}
			ice := w.iceServers(id, l.Until)
			if len(ice) == 0 {
				return bad(503, "relay_unavailable", "自有实时视频中继尚未配置")
			}
			w.state.Live[id] = l
			return 201, object{"iceServers": ice, "sessionId": id, "expiresAt": l.Until, "channels": channels, "signalingPath": "/vehicles/" + vid + "/live-sessions/" + id + "/signals"}
		}
		if len(parts) >= 4 {
			l := w.state.Live[parts[3]]
			if l == nil || l.Vehicle != vid || l.User != u.ID || l.Session != sid {
				return bad(404, "not_found", "观看会话不存在")
			}
			if len(parts) == 5 && parts[4] == "view" && method == "PUT" {
				flat, ok := in["flat"].(map[string]any)
				if !ok || len(flat) > 4 {
					return bad(422, "invalid", "画面模式无效")
				}
				for d, x := range flat {
					if !inSet(d, "front", "rear", "left", "right") {
						return bad(422, "invalid", "方向无效")
					}
					if _, ok := x.(bool); !ok {
						return bad(422, "invalid", "模式无效")
					}
				}
				l.Flat = clone(flat)
				return 200, object{"accepted": true}
			}
			if len(parts) == 4 && method == "DELETE" {
				delete(w.state.Live, l.ID)
				return 200, object{}
			}
			if len(parts) == 5 && parts[4] == "signals" {
				if method == "GET" {
					signals := []object{}
					for _, signal := range l.Signals {
						if signal["from"] == "device" {
							signals = append(signals, signal)
						}
					}
					return 200, object{"signals": signals, "channels": l.Channels, "ready": l.Ready}
				}
				if method == "POST" {
					kind := str("type")
					if kind != "offer" && kind != "ice" {
						return bad(422, "invalid", "信令类型无效")
					}
					if len(l.Signals) >= 128 {
						return bad(429, "signal_limit", "信令数量达到上限")
					}
					data := str("data")
					if len(data) > 65536 {
						return bad(422, "invalid", "信令过长")
					}
					l.Signals = append(l.Signals, object{"id": token()[:16], "from": "browser", "type": kind, "data": data})
					return 202, object{}
				}
			}
		}
	}
	return bad(404, "not_found", "接口不存在")
}
func (w *webService) bindingDTO(b *webBinding) object {
	o := object{"bindingId": b.ID, "status": b.Status}
	if b.Status == "confirmed" {
		o["vehicleId"] = b.Vehicle
	}
	if b.Reason != "" {
		o["reason"] = b.Reason
	}
	return o
}
func (w *webService) revokeVehicleStreams(vid, user string) {
	for id, l := range w.state.Live {
		if l.Vehicle == vid && (user == "" || l.User == user) {
			delete(w.state.Live, id)
		}
	}
}
func (w *webService) events(out http.ResponseWriter, q *http.Request) {
	w.mu.Lock()
	_, s, u := w.session(q)
	if s == nil {
		w.mu.Unlock()
		webFail(out, 401, "unauthorized", "会话已失效")
		return
	}
	uid := u.ID
	w.mu.Unlock()
	f, ok := out.(http.Flusher)
	if !ok {
		webFail(out, 503, "stream_unavailable", "事件流不可用")
		return
	}
	out.Header().Set("Content-Type", "text/event-stream")
	out.Header().Set("Cache-Control", "no-store")
	out.Header().Set("X-Accel-Buffering", "no")
	cursor, _ := strconv.ParseInt(q.Header.Get("Last-Event-ID"), 10, 64)
	if v := q.URL.Query().Get("after"); v != "" {
		cursor, _ = strconv.ParseInt(v, 10, 64)
	}
	timer := time.NewTicker(time.Second)
	defer timer.Stop()
	deadline := time.NewTimer(4 * time.Minute)
	defer deadline.Stop()
	fmt.Fprint(out, ": connected\n\n")
	f.Flush()
	for {
		w.mu.Lock()
		_, current, _ := w.session(q)
		if current == nil {
			w.mu.Unlock()
			return
		}
		batch := []webEvent{}
		for _, e := range w.state.Events {
			if e.ID <= cursor {
				continue
			}
			cursor = e.ID
			if w.role(uid, e.Vehicle) == "" {
				continue
			}
			if e.Job != "" {
				j := w.state.Jobs[e.Job]
				if j == nil || j.User != uid {
					continue
				}
			}
			batch = append(batch, e)
		}
		w.mu.Unlock()
		for _, e := range batch {
			b, _ := json.Marshal(e)
			if _, err := fmt.Fprintf(out, "id: %d\ndata: %s\n\n", e.ID, b); err != nil {
				return
			}
		}
		fmt.Fprint(out, ": heartbeat\n\n")
		f.Flush()
		select {
		case <-q.Context().Done():
			return
		case <-deadline.C:
			return
		case <-timer.C:
		}
	}
}
