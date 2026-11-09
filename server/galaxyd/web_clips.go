package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

func (w *webService) clipDTO(vid, user string, c webClip) object {
	b, _ := json.Marshal(c)
	o := object{}
	_ = json.Unmarshal(b, &o)
	o["watched"] = w.state.Watched[user+":"+vid+":"+c.ClipID]
	return o
}

type clipCursor struct {
	Start     int64
	ID, Query string
}

func (w *webService) clips(q *http.Request, parts []string, in object, uid string) (int, any) {
	bad := func(status int, code, msg string) (int, any) { return status, webError(code, msg) }
	if q.Method != "GET" {
		return bad(404, "not_found", "接口不存在")
	}
	vid := parts[1]
	v := w.state.Vehicles[vid]
	if len(parts) == 3 {
		params := q.URL.Query()
		parse := func(name string, def int64) (int64, error) {
			if params.Get(name) == "" {
				return def, nil
			}
			return strconv.ParseInt(params.Get(name), 10, 64)
		}
		from, e := parse("from", 0)
		until, e2 := parse("until", w.now().UnixMilli())
		if e != nil || e2 != nil || from < 0 || until <= from {
			return bad(422, "invalid", "结束时间必须大于开始时间")
		}
		filter := params.Get("filter")
		source := params.Get("source")
		if filter == "" {
			filter = "all"
		}
		if source == "" {
			source = "all"
		}
		if !inSet(filter, "all", "important", "abnormal", "unwatched") || !inSet(source, "all", "local", "cloud") {
			return bad(422, "invalid", "筛选条件无效")
		}
		queryHash := digest(fmt.Sprintf("%s:%d:%d:%s:%s", vid, from, until, filter, source))
		cursor := clipCursor{}
		if raw := params.Get("cursor"); raw != "" {
			b, err := base64.RawURLEncoding.DecodeString(raw)
			if err != nil || json.Unmarshal(b, &cursor) != nil || cursor.Query != queryHash {
				return bad(422, "invalid_cursor", "分页游标无效或筛选条件已改变")
			}
		}
		list := []webClip{}
		for _, c := range v.Clips {
			if c.End <= from || c.Start >= until || source != "all" && c.Source != source {
				continue
			}
			if filter == "important" && !c.Locked && c.EventCount == 0 {
				continue
			}
			if filter == "abnormal" && !inSet(c.Availability, "partial", "recoverable", "missing") {
				continue
			}
			if filter == "unwatched" && (w.state.Watched[uid+":"+vid+":"+c.ClipID] || c.Availability != "ok") {
				continue
			}
			if cursor.ID != "" && (c.Start > cursor.Start || c.Start == cursor.Start && c.ClipID >= cursor.ID) {
				continue
			}
			list = append(list, c)
		}
		sort.Slice(list, func(i, j int) bool {
			if list[i].Start == list[j].Start {
				return list[i].ClipID > list[j].ClipID
			}
			return list[i].Start > list[j].Start
		})
		var next any
		count := len(list)
		if count > 12 {
			list = list[:12]
			last := list[11]
			b, _ := json.Marshal(clipCursor{Start: last.Start, ID: last.ClipID, Query: queryHash})
			next = base64.RawURLEncoding.EncodeToString(b)
		}
		items := []object{}
		for _, c := range list {
			items = append(items, w.clipDTO(vid, uid, c))
		}
		return 200, object{"items": items, "nextCursor": next, "indexReady": v.IndexReady}
	}
	if len(parts) < 4 {
		return bad(404, "not_found", "片段不存在")
	}
	c, ok := v.Clips[parts[3]]
	if !ok {
		return bad(404, "not_found", "片段不存在或已被覆盖")
	}
	if len(parts) == 4 {
		w.state.Watched[uid+":"+vid+":"+c.ClipID] = true
		if e := w.save(); e != nil {
			return bad(503, "state_unavailable", "观看状态保存失败")
		}
		o := w.clipDTO(vid, uid, c)
		channels := []object{}
		for _, ch := range c.Channels {
			b, _ := json.Marshal(ch)
			row := object{}
			_ = json.Unmarshal(b, &row)
			if _, e := os.Stat(w.objectPath(vid, c.ClipID, ch.Direction+".mp4")); e == nil || w.rangeSources[rangeKey(vid, c.ClipID, ch.Direction)].Size > 0 {
				row["mediaUrl"] = "/web/v1/vehicles/" + vid + "/clips/" + c.ClipID + "/media/" + ch.Direction
			}
			channels = append(channels, row)
		}
		o["channels"] = channels
		return 200, o
	}
	if len(parts) == 5 && parts[4] == "telemetry" {
		b, e := os.ReadFile(w.objectPath(vid, c.ClipID, "telemetry.json"))
		if e != nil {
			return bad(503, "telemetry_unavailable", "该片段车辆侧录尚未传输到服务器")
		}
		var data object
		if json.Unmarshal(b, &data) != nil {
			return bad(503, "telemetry_unavailable", "侧录数据不可用")
		}
		return 200, data
	}
	return bad(404, "not_found", "接口不存在")
}
func inSet(v string, values ...string) bool {
	for _, s := range values {
		if s == v {
			return true
		}
	}
	return false
}
func (w *webService) objectPath(vid, cid, name string) string {
	return filepath.Join(w.path+".objects", digest(vid), digest(cid), name)
}
func (w *webService) media(out http.ResponseWriter, q *http.Request) {
	parts := strings.Split(strings.Trim(strings.TrimPrefix(q.URL.Path, "/web/v1"), "/"), "/")
	if len(parts) != 6 || parts[0] != "vehicles" || parts[2] != "clips" || parts[4] != "media" || !inSet(parts[5], "front", "rear", "left", "right") {
		webFail(out, 404, "not_found", "媒体不存在")
		return
	}
	w.mu.Lock()
	_, s, u := w.session(q)
	if s == nil {
		w.mu.Unlock()
		webFail(out, 401, "unauthorized", "会话已失效")
		return
	}
	if w.role(u.ID, parts[1]) == "" {
		w.mu.Unlock()
		webFail(out, 403, "forbidden", "无权访问该车辆")
		return
	}
	v := w.state.Vehicles[parts[1]]
	c, ok := v.Clips[parts[3]]
	if !ok || inSet(c.Availability, "missing", "overwritten") {
		w.mu.Unlock()
		webFail(out, 404, "not_found", "原片不可用")
		return
	}
	f, e := os.Open(w.objectPath(parts[1], parts[3], parts[5]+".mp4"))
	key := parts[1] + ":" + parts[3]
	if e == nil {
		w.mediaReaders[key]++
	}
	w.mu.Unlock()
	if e != nil {
		if w.remoteMedia(out, q, parts) {
			return
		}
		webFail(out, 503, "media_unavailable", "车端媒体尚未准备，请重新打开片段")
		return
	}
	defer func() {
		f.Close()
		w.mu.Lock()
		w.mediaReaders[key]--
		if w.mediaReaders[key] == 0 {
			delete(w.mediaReaders, key)
		}
		w.mu.Unlock()
	}()
	stat, e := f.Stat()
	if e != nil {
		webFail(out, 503, "media_unavailable", "媒体暂不可用")
		return
	}
	// Range requests authenticate each time; transfers check revocation on every read too.
	out.Header().Set("Content-Type", "video/mp4")
	out.Header().Set("Cache-Control", "no-store")
	out.Header().Set("X-Content-Type-Options", "nosniff")
	reader := &revocableMedia{ReadSeeker: f, allowed: func() bool {
		w.mu.Lock()
		defer w.mu.Unlock()
		_, s, u := w.session(q)
		return s != nil && w.role(u.ID, parts[1]) != ""
	}}
	http.ServeContent(out, q, parts[5]+".mp4", stat.ModTime(), reader)
}

type revocableMedia struct {
	io.ReadSeeker
	allowed func() bool
}

func (r *revocableMedia) Read(b []byte) (int, error) {
	if !r.allowed() {
		return 0, io.EOF
	}
	n, err := r.ReadSeeker.Read(b)
	// 设备Range可能等待网络结果；返回数据前再次核对等待期间的解绑/撤销。
	if !r.allowed() {
		return 0, io.EOF
	}
	return n, err
}
