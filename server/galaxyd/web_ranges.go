package main

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type rangeSource struct {
	Size int64
	Seen int64
}
type rangeRead struct {
	ID, Device, Clip, Direction string
	Offset                      int64
	Length                      int
	Result                      chan []byte
}

func rangeKey(device, clip, direction string) string { return device + ":" + clip + ":" + direction }
func (w *webService) rangeDevice(q *http.Request) string {
	id := q.Header.Get("X-Galaxy-Device")
	v := w.state.Vehicles[id]
	if v == nil || !equal(v.SecretHash, digest(strings.TrimPrefix(q.Header.Get("Authorization"), "Bearer "))) {
		return ""
	}
	return id
}
func (w *webService) rangeReady(id string, in object) (int, any) {
	cid, _ := in["clipId"].(string)
	direction, _ := in["direction"].(string)
	size, _ := in["size"].(float64)
	if _, ok := w.state.Vehicles[id].Clips[cid]; !ok || !inSet(direction, "front", "rear", "left", "right") || size < 12 || size > 256<<20 {
		return 422, webError("invalid", "媒体描述无效")
	}
	if w.rangeSources == nil {
		w.rangeSources = map[string]rangeSource{}
	}
	now := w.now().UnixMilli()
	for k, v := range w.rangeSources {
		if now-v.Seen > 120000 {
			delete(w.rangeSources, k)
		}
	}
	if len(w.rangeSources) >= 256 {
		return 429, webError("media_limit", "打开的媒体过多")
	}
	w.rangeSources[rangeKey(id, cid, direction)] = rangeSource{Size: int64(size), Seen: now}
	return 200, object{"accepted": true}
}
func (w *webService) rangeQueue(id string) chan *rangeRead {
	if w.rangeQueues == nil {
		w.rangeQueues = map[string]chan *rangeRead{}
	}
	if w.rangeQueues[id] == nil {
		w.rangeQueues[id] = make(chan *rangeRead, 32)
	}
	return w.rangeQueues[id]
}
func (w *webService) rangePoll(out http.ResponseWriter, q *http.Request) {
	if q.Method != "POST" {
		webFail(out, 405, "invalid", "仅支持POST")
		return
	}
	w.mu.Lock()
	id := w.rangeDevice(q)
	var queue chan *rangeRead
	if id != "" {
		queue = w.rangeQueue(id)
	}
	w.mu.Unlock()
	if id == "" {
		webFail(out, 401, "unauthorized", "设备认证失败")
		return
	}
	timer := time.NewTimer(25 * time.Second)
	defer timer.Stop()
	for {
		select {
		case <-q.Context().Done():
			return
		case <-timer.C:
			reply(out, 200, object{})
			return
		case item := <-queue:
			w.mu.Lock()
			pending := w.rangePending[item.ID] != nil
			w.mu.Unlock()
			if !pending {
				continue
			}
			reply(out, 200, object{"requestId": item.ID, "clipId": item.Clip, "direction": item.Direction, "offset": item.Offset, "length": item.Length})
			return
		}
	}
}
func (w *webService) rangeResult(out http.ResponseWriter, q *http.Request) {
	if q.Method != "POST" {
		webFail(out, 405, "invalid", "仅支持POST")
		return
	}
	w.mu.Lock()
	id := w.rangeDevice(q)
	item := w.rangePending[q.URL.Query().Get("id")]
	w.mu.Unlock()
	if id == "" || item == nil || item.Device != id {
		webFail(out, 409, "range_expired", "读取请求已结束")
		return
	}
	q.Body = http.MaxBytesReader(out, q.Body, 512<<10)
	data, e := io.ReadAll(q.Body)
	if e != nil || len(data) > item.Length {
		webFail(out, 422, "invalid", "读取结果长度无效")
		return
	}
	select {
	case item.Result <- data:
	default:
	}
	reply(out, 200, object{"accepted": true})
}
func (w *webService) readRange(ctx context.Context, device, clip, direction string, offset int64, length int) ([]byte, error) {
	item := &rangeRead{ID: token()[:32], Device: device, Clip: clip, Direction: direction, Offset: offset, Length: length, Result: make(chan []byte, 1)}
	w.mu.Lock()
	if w.rangePending == nil {
		w.rangePending = map[string]*rangeRead{}
	}
	if len(w.rangePending) >= 32 {
		w.mu.Unlock()
		return nil, errors.New("media_busy")
	}
	if source, ok := w.rangeSources[rangeKey(device, clip, direction)]; ok {
		source.Seen = w.now().UnixMilli()
		w.rangeSources[rangeKey(device, clip, direction)] = source
	}
	w.rangePending[item.ID] = item
	queue := w.rangeQueue(device)
	w.mu.Unlock()
	defer func() { w.mu.Lock(); delete(w.rangePending, item.ID); w.mu.Unlock() }()
	select {
	case queue <- item:
	default:
		return nil, errors.New("media_busy")
	}
	timer := time.NewTimer(20 * time.Second)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-timer.C:
		return nil, errors.New("device_read_timeout")
	case data := <-item.Result:
		if len(data) == 0 {
			return nil, io.ErrUnexpectedEOF
		}
		return data, nil
	}
}

type remoteMediaReader struct {
	w                       *webService
	ctx                     context.Context
	device, clip, direction string
	size, position          int64
	cached                  []byte
	cachedAt                int64
}

func (r *remoteMediaReader) Read(p []byte) (int, error) {
	if r.position >= r.size {
		return 0, io.EOF
	}
	if r.cached == nil || r.position < r.cachedAt || r.position >= r.cachedAt+int64(len(r.cached)) {
		start := (r.position / (512 << 10)) * (512 << 10)
		length := int64(512 << 10)
		if length > r.size-start {
			length = r.size - start
		}
		data, e := r.w.readRange(r.ctx, r.device, r.clip, r.direction, start, int(length))
		if e != nil {
			return 0, e
		}
		r.cached = data
		r.cachedAt = start
	}
	n := copy(p, r.cached[r.position-r.cachedAt:])
	r.position += int64(n)
	return n, nil
}
func (r *remoteMediaReader) Seek(offset int64, whence int) (int64, error) {
	switch whence {
	case io.SeekStart:
		r.position = offset
	case io.SeekCurrent:
		r.position += offset
	case io.SeekEnd:
		r.position = r.size + offset
	default:
		return 0, errors.New("bad_seek")
	}
	if r.position < 0 {
		return 0, errors.New("bad_seek")
	}
	return r.position, nil
}
func (w *webService) remoteMedia(out http.ResponseWriter, q *http.Request, parts []string) bool {
	w.mu.Lock()
	source, ok := w.rangeSources[rangeKey(parts[1], parts[3], parts[5])]
	if ok {
		source.Seen = w.now().UnixMilli()
		w.rangeSources[rangeKey(parts[1], parts[3], parts[5])] = source
		w.mediaReaders[parts[1]+":"+parts[3]]++
	}
	w.mu.Unlock()
	if !ok {
		return false
	}
	defer func() { w.mu.Lock(); w.mediaReaders[parts[1]+":"+parts[3]]--; w.mu.Unlock() }()
	reader := &remoteMediaReader{w: w, ctx: q.Context(), device: parts[1], clip: parts[3], direction: parts[5], size: source.Size}
	authorized := &revocableMedia{ReadSeeker: reader, allowed: func() bool {
		w.mu.Lock()
		defer w.mu.Unlock()
		_, s, u := w.session(q)
		return s != nil && w.role(u.ID, parts[1]) != ""
	}}
	out.Header().Set("Content-Type", "video/mp4")
	out.Header().Set("Cache-Control", "no-store")
	out.Header().Set("X-Media-Transport", "device-range")
	// Known size enables standard Range/moov tail seeks; no complete-file upload gate.
	out.Header().Set("X-Media-Size", strconv.FormatInt(source.Size, 10))
	http.ServeContent(out, q, parts[5]+".mp4", time.Time{}, authorized)
	return true
}
