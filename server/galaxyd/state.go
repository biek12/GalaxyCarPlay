package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// 个人中继：设备与电脑各一个随机凭据，没有角色/逐命令审批。状态文件只允许服务用户读取。
type Device struct {
	ID        string          `json:"id"`
	Secret    string          `json:"secret"`
	Grant     string          `json:"grant"`
	Code      string          `json:"code"`
	CodeUntil int64           `json:"codeUntil"`
	Until     int64           `json:"until"`
	Client    string          `json:"client"`
	Seen      int64           `json:"seen"`
	Jobs      map[string]*Job `json:"jobs"`
	wake      chan struct{}
}
type Job struct {
	ID        string          `json:"id"`
	Kind      string          `json:"kind"`
	Command   string          `json:"command,omitempty"`
	Transport string          `json:"transport,omitempty"`
	Timeout   int             `json:"timeoutMillis"`
	Artifact  string          `json:"artifact,omitempty"`
	Package   string          `json:"package,omitempty"`
	SHA256    string          `json:"sha256,omitempty"`
	Created   int64           `json:"created"`
	Claimed   bool            `json:"claimed"`
	Result    json.RawMessage `json:"result,omitempty"`
}
type Relay struct {
	web        *webService
	mu         sync.Mutex
	devices    map[string]*Device
	path       string
	attempts   map[string][]int64
	now        func() time.Time
	enrollment string
	uploads    map[string]bool
}

func token() string {
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return hex.EncodeToString(b)
}
func code() string {
	n, e := rand.Int(rand.Reader, big.NewInt(100000000))
	if e != nil {
		panic(e)
	}
	return fmt.Sprintf("%08d", n.Int64())
}
func equal(a, b string) bool { return a != "" && subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1 }
func openRelay(path string) (*Relay, error) {
	r := &Relay{devices: map[string]*Device{}, path: path, attempts: map[string][]int64{}, now: time.Now}
	data, e := os.ReadFile(path)
	if e != nil && !os.IsNotExist(e) {
		return nil, e
	}
	if len(data) > 0 {
		if e = json.Unmarshal(data, &r.devices); e != nil {
			return nil, e
		}
	}
	for _, d := range r.devices {
		d.wake = make(chan struct{}, 1)
		if d.Jobs == nil {
			d.Jobs = map[string]*Job{}
		}
	}
	w, err := openWeb(path + ".web")
	if err != nil {
		return nil, err
	}
	r.web = w
	w.enrollmentAuth = func(value string) bool { return value != "" && equal(value, r.enrollment) }
	w.deviceAuth = func(id, credential string) bool {
		r.mu.Lock()
		defer r.mu.Unlock()
		d := r.devices[id]
		return d != nil && equal(d.Secret, credential)
	}
	return r, nil
}
func (r *Relay) save() error {
	data, e := json.Marshal(r.devices)
	if e != nil {
		return e
	}
	if e = os.MkdirAll(filepath.Dir(r.path), 0700); e != nil {
		return e
	}
	f, e := os.OpenFile(r.path+".tmp", os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
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
	return os.Rename(r.path+".tmp", r.path)
}
func (r *Relay) valid(d *Device) bool { return d != nil && d.Until > r.now().Unix() }
func (r *Relay) expire() {
	r.mu.Lock()
	defer r.mu.Unlock()
	changed := false
	for _, d := range r.devices {
		if !r.valid(d) {
			r.clearArtifacts(d.ID)
		}
		if !r.valid(d) && (d.Client != "" || d.Code != "" || len(d.Jobs) > 0) {
			d.Client = ""
			d.Code = ""
			d.Jobs = map[string]*Job{}
			changed = true
		}
	}
	if changed {
		_ = r.save()
	}
}
func (r *Relay) pairAllowed(ip string) bool {
	now := r.now().Unix()
	old := r.attempts[ip]
	next := []int64{}
	for _, at := range old {
		if now-at < 60 {
			next = append(next, at)
		}
	}
	if len(next) >= 8 {
		return false
	}
	if len(r.attempts) > 256 {
		r.attempts = map[string][]int64{}
	}
	r.attempts[ip] = append(next, now)
	return true
}
