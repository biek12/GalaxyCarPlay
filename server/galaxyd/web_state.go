package main

// Web accounts, grants and device work are independent of the diagnostic relay.
import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"time"
)

type object = map[string]any

type webUser struct {
	ID              string   `json:"id"`
	Phone           string   `json:"phone"`
	Name            string   `json:"name"`
	EnrollmentHash  string   `json:"enrollmentHash,omitempty"`
	EnrollmentUntil int64    `json:"enrollmentUntil,omitempty"`
	Secret          string   `json:"secret,omitempty"`   // AES-GCM sealed; never a DTO.
	Recovery        []string `json:"recovery,omitempty"` // SHA256 hashes only.
	UsedSteps       []int64  `json:"usedSteps,omitempty"`
	Created         int64    `json:"created"`
}
type webSession struct {
	ID, User, CSRF, Device, IP string
	Created, Active, Until     int64
}
type webChallenge struct {
	User, Secret, Kind, Session string
	Until                       int64
	Failures                    int
}
type webTicket struct {
	Session string
	Until   int64
}
type webVehicle struct {
	Device     string             `json:"device"`
	SecretHash string             `json:"secretHash"`
	Owner      string             `json:"owner"`
	Members    map[string]int64   `json:"members"`
	Snapshot   object             `json:"snapshot"`
	Seen       int64              `json:"seen"`
	Clips      map[string]webClip `json:"clips"`
	IndexReady bool               `json:"indexReady"`
	Revision   int64              `json:"revision"`
	Desired    bool               `json:"desired"`
}
type webClip struct {
	ClipID       string       `json:"clipId"`
	SessionID    string       `json:"sessionId"`
	Start        int64        `json:"startAt"`
	End          int64        `json:"endAt"`
	Source       string       `json:"source"`
	Availability string       `json:"availability"`
	Locked       bool         `json:"locked"`
	EventCount   int          `json:"eventCount"`
	Bytes        int64        `json:"bytes"`
	Channels     []webChannel `json:"channels"`
	Telemetry    object       `json:"telemetry"`
}
type webChannel struct {
	Direction  string  `json:"direction"`
	Status     string  `json:"status"`
	Offset     float64 `json:"mediaStartOffsetMs"`
	Duration   float64 `json:"durationMs"`
	Projection string  `json:"projectionVersion,omitempty"`
}
type webBinding struct {
	ID, Vehicle, User, CodeHash, Status, Reason string
	Until                                       int64
}
type webJob struct {
	ID       string   `json:"jobId"`
	Type     string   `json:"type"`
	State    string   `json:"state"`
	Vehicle  string   `json:"-"`
	User     string   `json:"-"` // explicit persistence handled below
	Reason   string   `json:"reason,omitempty"`
	Created  int64    `json:"createdAt"`
	Updated  int64    `json:"updatedAt"`
	Until    int64    `json:"expiresAt"`
	Progress object   `json:"progress,omitempty"`
	Items    []object `json:"items,omitempty"`
	Payload  object   `json:"-"`
}

// Private envelope prevents authority and device payload accidentally appearing in browser DTOs.
type webJobRecord struct {
	Job           webJob
	Vehicle, User string
	Payload       object
}
type webLive struct {
	Flat                       object
	ID, Vehicle, User, Session string
	Until                      int64
	Channels                   []object
	Signals                    []object
	Ready                      bool
}
type webIdem struct {
	Fingerprint string
	Status      int
	Body        string
	Until       int64
}
type webEvent struct {
	ID      int64  `json:"sequence"`
	Type    string `json:"type"`
	Vehicle string `json:"vehicleId,omitempty"`
	Job     string `json:"jobId,omitempty"`
	Payload object `json:"payload"`
	At      int64  `json:"at"`
}
type webState struct {
	Users       map[string]*webUser
	Sessions    map[string]*webSession
	Challenges  map[string]*webChallenge
	Tickets     map[string]webTicket
	Vehicles    map[string]*webVehicle
	Bindings    map[string]*webBinding
	Jobs        map[string]*webJobRecord
	Live        map[string]*webLive
	Watched     map[string]bool
	Idempotency map[string]webIdem
	Events      []webEvent
	Sequence    int64
}
type webService struct {
	rangeSources   map[string]rangeSource
	rangeQueues    map[string]chan *rangeRead
	rangePending   map[string]*rangeRead
	mu             sync.Mutex
	state          webState
	path           string
	key            []byte
	now            func() time.Time
	origin         string
	attempts       map[string][]int64
	deviceAuth     func(string, string) bool
	enrollmentAuth func(string) bool
	mediaReaders   map[string]int
	uploading      bool
}

var phonePattern = regexp.MustCompile(`^[0-9]{11}$`)
var webID = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,128}$`)

func digest(s string) string { d := sha256.Sum256([]byte(s)); return hex.EncodeToString(d[:]) }
func openWeb(path string) (*webService, error) {
	w := &webService{path: path, now: time.Now, origin: os.Getenv("GALAXY_WEB_ORIGIN"), attempts: map[string][]int64{}, mediaReaders: map[string]int{}}
	if w.origin == "" {
		w.origin = "https://bridge.example.invalid"
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return nil, err
	}
	key, err := os.ReadFile(path + ".key")
	if os.IsNotExist(err) {
		// Existing ciphertext must never be silently orphaned by generating another key.
		if _, e := os.Stat(path); e == nil {
			return nil, errors.New("web key missing")
		}
		key = make([]byte, 32)
		if _, err = rand.Read(key); err != nil {
			return nil, err
		}
		f, e := os.OpenFile(path+".key", os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if e != nil {
			return nil, e
		}
		_, err = f.Write(key)
		if err == nil {
			err = f.Sync()
		}
		ce := f.Close()
		if err == nil {
			err = ce
		}
	}
	if err != nil || len(key) != 32 {
		return nil, errors.New("web key unavailable")
	}
	w.key = key
	data, err := os.ReadFile(path)
	if err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	if len(data) > 0 {
		if err = json.Unmarshal(data, &w.state); err != nil {
			return nil, err
		}
	}
	// Initialise missing maps on additive schema upgrades.
	empty := `{"Users":{},"Sessions":{},"Challenges":{},"Tickets":{},"Vehicles":{},"Bindings":{},"Jobs":{},"Live":{},"Watched":{},"Idempotency":{}}`
	var s webState
	_ = json.Unmarshal([]byte(empty), &s)
	if w.state.Users == nil {
		w.state.Users = s.Users
	}
	if w.state.Sessions == nil {
		w.state.Sessions = s.Sessions
	}
	if w.state.Challenges == nil {
		w.state.Challenges = s.Challenges
	}
	if w.state.Tickets == nil {
		w.state.Tickets = s.Tickets
	}
	if w.state.Vehicles == nil {
		w.state.Vehicles = s.Vehicles
	}
	if w.state.Bindings == nil {
		w.state.Bindings = s.Bindings
	}
	if w.state.Jobs == nil {
		w.state.Jobs = s.Jobs
	}
	if w.state.Live == nil {
		w.state.Live = s.Live
	}
	if w.state.Watched == nil {
		w.state.Watched = s.Watched
	}
	if w.state.Idempotency == nil {
		w.state.Idempotency = s.Idempotency
	}
	return w, nil
}
func (w *webService) save() error {
	data, e := json.Marshal(w.state)
	if e != nil {
		return e
	}
	f, e := os.OpenFile(w.path+".tmp", os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
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
	return os.Rename(w.path+".tmp", w.path)
}
func (w *webService) seal(value string) string {
	block, _ := aes.NewCipher(w.key)
	a, _ := cipher.NewGCM(block)
	n := make([]byte, a.NonceSize())
	if _, e := rand.Read(n); e != nil {
		panic(e)
	}
	return base64.RawURLEncoding.EncodeToString(a.Seal(n, n, []byte(value), []byte("galaxyd-web-totp-v1")))
}
func (w *webService) unseal(value string) string {
	b, e := base64.RawURLEncoding.DecodeString(value)
	if e != nil {
		return ""
	}
	block, _ := aes.NewCipher(w.key)
	a, _ := cipher.NewGCM(block)
	if len(b) < a.NonceSize() {
		return ""
	}
	plain, e := a.Open(nil, b[:a.NonceSize()], b[a.NonceSize():], []byte("galaxyd-web-totp-v1"))
	if e != nil {
		return ""
	}
	return string(plain)
}
func (w *webService) invite(phone string) (string, error) {
	if !phonePattern.MatchString(phone) {
		return "", errors.New("invalid phone")
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	var user *webUser
	for _, u := range w.state.Users {
		if u.Phone == phone {
			user = u
			break
		}
	}
	if user != nil && user.Secret != "" {
		return "", errors.New("factor already enrolled")
	}
	if user == nil {
		if len(w.state.Users) >= 100 {
			return "", errors.New("account limit")
		}
		id := token()[:32]
		user = &webUser{ID: id, Phone: phone, Name: "车主", Created: w.now().UnixMilli()}
		w.state.Users[id] = user
	}
	invitation := token()
	user.EnrollmentHash = digest(invitation)
	user.EnrollmentUntil = w.now().UnixMilli() + 86400000
	for id, ch := range w.state.Challenges {
		if ch.User == user.ID {
			delete(w.state.Challenges, id)
		}
	}
	if e := w.save(); e != nil {
		return "", e
	}
	return invitation, nil
}
func (w *webService) gc() {
	now := w.now().UnixMilli()
	for k, s := range w.state.Sessions {
		if s.Until <= now {
			delete(w.state.Sessions, k)
		}
	}
	for k, c := range w.state.Challenges {
		if c.Until <= now {
			delete(w.state.Challenges, k)
		}
	}
	for k, t := range w.state.Tickets {
		if t.Until <= now {
			delete(w.state.Tickets, k)
		}
	}
	for k, v := range w.state.Idempotency {
		if v.Until <= now {
			delete(w.state.Idempotency, k)
		}
	}
	for k, s := range w.state.Live {
		if s.Until <= now || w.state.Sessions[s.Session] == nil {
			delete(w.state.Live, k)
		}
	}
	for _, b := range w.state.Bindings {
		if b.Until <= now && (b.Status == "pending" || b.Status == "pending_device_confirmation") {
			b.Status = "expired"
		}
	}
	for k, j := range w.state.Jobs {
		if j.Job.Until <= now && !terminal(j.Job.State) {
			j.Job.State = "expired"
			j.Job.Updated = now
			w.emit("job", j.Vehicle, j.Job.ID, object{"state": "expired"})
		}
		if j.Job.Updated < now-7*86400000 {
			delete(w.state.Jobs, k)
		}
	}
}
func terminal(s string) bool {
	switch s {
	case "success", "partial_success", "failed", "expired", "cancelled", "ready":
		return true
	}
	return false
}
func (w *webService) emit(kind, vehicle, job string, payload object) {
	w.state.Sequence++
	w.state.Events = append(w.state.Events, webEvent{ID: w.state.Sequence, Type: kind, Vehicle: vehicle, Job: job, Payload: payload, At: w.now().UnixMilli()})
	if len(w.state.Events) > 512 {
		w.state.Events = w.state.Events[len(w.state.Events)-512:]
	}
}
func (w *webService) role(u, vid string) string {
	v := w.state.Vehicles[vid]
	if v == nil {
		return ""
	}
	if v.Owner == u {
		return "owner"
	}
	if _, ok := v.Members[u]; ok {
		return "viewer"
	}
	return ""
}
func clone(v object) object {
	b, _ := json.Marshal(v)
	var out object
	_ = json.Unmarshal(b, &out)
	if out == nil {
		out = object{}
	}
	return out
}
func readJSON(q io.Reader, v any) error {
	d := json.NewDecoder(io.LimitReader(q, 4<<20))
	if e := d.Decode(v); e != nil {
		return e
	}
	var tail any
	if d.Decode(&tail) != io.EOF {
		return errors.New("trailing JSON")
	}
	return nil
}
