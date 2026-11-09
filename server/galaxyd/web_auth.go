package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"encoding/base32"
	"encoding/binary"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strings"
)

func totp(secret string, step int64) string {
	key, e := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(secret)
	if e != nil {
		return ""
	}
	b := make([]byte, 8)
	binary.BigEndian.PutUint64(b, uint64(step))
	h := hmac.New(sha1.New, key)
	_, _ = h.Write(b)
	s := h.Sum(nil)
	o := s[len(s)-1] & 15
	n := binary.BigEndian.Uint32(s[o:o+4]) & 0x7fffffff
	return fmt.Sprintf("%06d", n%1000000)
}
func newTOTP() string {
	b := make([]byte, 20)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(b)
}
func otpURI(secret, phone string) string {
	return "otpauth://totp/AutoRecord:" + url.PathEscape(phone) + "?secret=" + secret + "&issuer=AutoRecord&algorithm=SHA1&digits=6&period=30"
}
func (w *webService) checkOTP(u *webUser, secret, code string) bool {
	if len(code) != 6 || secret == "" {
		return false
	}
	now := w.now().Unix() / 30
	for s := now - 1; s <= now+1; s++ {
		if !equal(totp(secret, s), code) {
			continue
		}
		for _, used := range u.UsedSteps {
			if used == s {
				return false
			}
		}
		steps := []int64{s}
		for _, used := range u.UsedSteps {
			if used >= now-1 {
				steps = append(steps, used)
			}
		}
		u.UsedSteps = steps
		return true
	}
	return false
}
func recovery(u *webUser) []string {
	codes := make([]string, 10)
	u.Recovery = nil
	for i := range codes {
		a := token()[:20]
		codes[i] = a[:10] + "-" + a[10:]
		u.Recovery = append(u.Recovery, digest(codes[i]))
	}
	return codes
}
func maskedPhone(p string) string {
	if len(p) != 11 {
		return ""
	}
	return p[:3] + "****" + p[7:]
}
func maskedIP(q *http.Request) string {
	ip, _, _ := net.SplitHostPort(q.RemoteAddr)
	if x := q.Header.Get("X-Real-IP"); x != "" {
		ip = x
	}
	parsed := net.ParseIP(ip)
	if parsed == nil {
		return "unknown"
	}
	if v := parsed.To4(); v != nil {
		return fmt.Sprintf("%d.%d.*.*", v[0], v[1])
	}
	return "IPv6 masked"
}
func (w *webService) limited(key string, max int) bool {
	now := w.now().UnixMilli()
	entries := w.attempts[key]
	out := entries[:0]
	for _, at := range entries {
		if at > now-60000 {
			out = append(out, at)
		}
	}
	if len(out) >= max {
		w.attempts[key] = out
		return true
	}
	if len(w.attempts) > 2048 {
		for k, v := range w.attempts {
			if len(v) == 0 || v[len(v)-1] < now-60000 {
				delete(w.attempts, k)
			}
		}
		if len(w.attempts) > 2048 {
			return true
		}
	}
	w.attempts[key] = append(out, now)
	return false
}
func (w *webService) session(q *http.Request) (string, *webSession, *webUser) {
	cookie, e := q.Cookie("ar_session")
	if e != nil {
		return "", nil, nil
	}
	key := digest(cookie.Value)
	s := w.state.Sessions[key]
	if s == nil || s.Until <= w.now().UnixMilli() {
		return "", nil, nil
	}
	u := w.state.Users[s.User]
	if u == nil || u.Secret == "" {
		return "", nil, nil
	}
	return key, s, u
}
func (w *webService) cookie(out http.ResponseWriter, value, csrf string, maxAge int) {
	http.SetCookie(out, &http.Cookie{Name: "ar_session", Value: value, Path: "/", MaxAge: maxAge, Secure: true, HttpOnly: true, SameSite: http.SameSiteStrictMode})
	http.SetCookie(out, &http.Cookie{Name: "ar_csrf", Value: csrf, Path: "/", MaxAge: maxAge, Secure: true, SameSite: http.SameSiteStrictMode})
}
func (w *webService) ticket(session, value string) bool {
	key := digest(value)
	t, ok := w.state.Tickets[key]
	if !ok || t.Session != session || t.Until <= w.now().UnixMilli() {
		return false
	}
	delete(w.state.Tickets, key)
	return true
}
func (w *webService) authRoute(out http.ResponseWriter, q *http.Request, path string, in object) (int, any, bool) {
	now := w.now().UnixMilli()
	method := q.Method
	parts := strings.Split(strings.Trim(path, "/"), "/")
	str := func(k string) string { s, _ := in[k].(string); return s }
	err := func(status int, code, msg string) (int, any, bool) { return status, webError(code, msg), true }
	if method == "POST" && path == "/auth/login" {
		phone := strings.TrimSpace(str("phone"))
		if !phonePattern.MatchString(phone) {
			return err(422, "invalid", "请输入11位手机号")
		}
		if w.limited("login-ip:"+maskedIP(q), 10) || w.limited("login-phone:"+digest(phone), 5) {
			return err(429, "throttled", "尝试次数过多，请稍后重试")
		}
		var u *webUser
		for _, v := range w.state.Users {
			if v.Phone == phone {
				u = v
				break
			}
		}
		id := token()
		next := "totp"
		uid := ""
		if u != nil {
			uid = u.ID
			if u.Secret == "" {
				next = "enroll"
			}
		}
		w.state.Challenges[id] = &webChallenge{User: uid, Kind: next, Until: now + 300000}
		return 200, object{"challengeId": id, "expiresAt": now + 300000, "next": next}, true
	}
	if method == "POST" && len(parts) == 4 && parts[0] == "auth" && parts[1] == "challenges" {
		c := w.state.Challenges[parts[2]]
		if c == nil || c.Until <= now {
			return err(401, "challenge_expired", "登录会话已过期，请重新登录")
		}
		if c.Failures >= 5 || w.limited("verify:"+maskedIP(q), 20) {
			return err(429, "throttled", "尝试次数过多，请稍后重试")
		}
		u := w.state.Users[c.User]
		if parts[3] == "enroll" {
			if c.Kind != "enroll" || u == nil {
				return err(409, "conflict", "当前登录不能绑定验证器")
			}
			if u.EnrollmentUntil <= now || !equal(digest(str("invitationCode")), u.EnrollmentHash) {
				c.Failures++
				return err(403, "invitation_required", "首次绑定需要有效的受邀激活码")
			}
			if c.Secret != "" {
				return err(409, "enrollment_already_issued", "密钥仅展示一次，请重新登录后重新绑定")
			}
			secret := newTOTP()
			c.Secret = w.seal(secret)
			return 200, object{"otpauthUri": otpURI(secret, u.Phone), "secret": secret}, true
		}
		if parts[3] == "verify" {
			if u == nil {
				c.Failures++
				return err(401, "bad_code", "认证信息无效")
			}
			var codes []string
			secret := w.unseal(u.Secret)
			if c.Kind == "enroll" {
				secret = w.unseal(c.Secret)
				if secret == "" {
					return err(409, "mfa_enroll_required", "请先扫码绑定验证器")
				}
			}
			ok := false
			if str("code") != "" {
				ok = w.checkOTP(u, secret, str("code"))
			} else if c.Kind != "enroll" && str("recoveryCode") != "" {
				hash := digest(str("recoveryCode"))
				for i, h := range u.Recovery {
					if equal(hash, h) {
						ok = true
						u.Recovery = append(u.Recovery[:i], u.Recovery[i+1:]...)
						break
					}
				}
			}
			if !ok {
				c.Failures++
				return err(401, "bad_code", "认证信息无效或已被使用")
			}
			if c.Kind == "enroll" {
				if u.Secret != "" {
					return err(409, "conflict", "验证器已绑定，请重新登录")
				}
				u.Secret = c.Secret
				u.EnrollmentHash = ""
				u.EnrollmentUntil = 0
				codes = recovery(u)
			}
			if len(w.state.Sessions) >= 1000 {
				return err(429, "throttled", "会话数量达到上限")
			}
			raw := token()
			sid := digest(raw)
			csrf := token()
			w.state.Sessions[sid] = &webSession{ID: token()[:32], User: u.ID, CSRF: csrf, Device: strings.ToValidUTF8(q.UserAgent(), ""), IP: maskedIP(q), Created: now, Active: now, Until: now + 12*3600000}
			if len(w.state.Sessions[sid].Device) > 160 {
				w.state.Sessions[sid].Device = w.state.Sessions[sid].Device[:160]
			}
			delete(w.state.Challenges, parts[2])
			w.cookie(out, raw, csrf, 12*3600)
			result := object{"authenticated": true}
			if codes != nil {
				result["recoveryCodes"] = codes
			}
			return 200, result, true
		}
	}
	sid, s, u := w.session(q)
	if s == nil {
		return 0, nil, false
	}
	if method == "GET" && path == "/me" {
		s.Active = now
		return 200, object{"userId": u.ID, "displayName": u.Name, "phoneMasked": maskedPhone(u.Phone), "mfaEnabled": u.Secret != "", "createdAt": u.Created}, true
	}
	if method == "POST" && path == "/auth/logout" {
		delete(w.state.Sessions, sid)
		w.cookie(out, "", "", -1)
		return 200, object{}, true
	}
	if method == "POST" && path == "/auth/reauth" {
		if w.limited("reauth:"+u.ID, 10) {
			return err(429, "throttled", "尝试次数过多，请稍后重试")
		}
		if !w.checkOTP(u, w.unseal(u.Secret), str("code")) {
			return err(401, "bad_code", "验证码错误或已被使用")
		}
		t := token()
		w.state.Tickets[digest(t)] = webTicket{Session: sid, Until: now + 300000}
		return 200, object{"actionTicket": t, "expiresAt": now + 300000}, true
	}
	if method == "GET" && path == "/me/sessions" {
		list := []object{}
		for key, x := range w.state.Sessions {
			if x.User == u.ID {
				list = append(list, object{"id": x.ID, "device": x.Device, "ipMasked": x.IP, "lastActiveAt": x.Active, "current": key == sid})
			}
		}
		return 200, list, true
	}
	if method == "DELETE" && len(parts) == 3 && parts[0] == "me" && parts[1] == "sessions" {
		current := false
		found := parts[2] == "all"
		for key, x := range w.state.Sessions {
			if x.User != u.ID {
				continue
			}
			if (parts[2] == "all" && key != sid) || x.ID == parts[2] {
				found = true
				current = current || key == sid
				delete(w.state.Sessions, key)
			}
		}
		if !found {
			return err(404, "not_found", "会话不存在")
		}
		if current {
			w.cookie(out, "", "", -1)
		}
		return 200, object{"revokedCurrent": current}, true
	}
	if method == "POST" && path == "/me/mfa/disable" {
		return err(409, "last_factor_required", "免密码账号须保留验证器，可更换验证器或使用恢复码")
	}
	if method == "POST" && path == "/me/password" {
		return err(409, "passwordless_account", "该账号使用手机号和验证器登录，无需设置密码")
	}
	if method == "POST" && path == "/me/mfa/recovery-codes" {
		if !w.ticket(sid, str("actionTicket")) {
			return err(403, "reauth_required", "请重新认证")
		}
		return 200, object{"recoveryCodes": recovery(u)}, true
	}
	if method == "POST" && path == "/me/mfa/enrollments" {
		if !w.ticket(sid, str("actionTicket")) {
			return err(403, "reauth_required", "更换验证器前请重新认证")
		}
		id := token()
		secret := newTOTP()
		w.state.Challenges[id] = &webChallenge{User: u.ID, Session: sid, Kind: "replace", Secret: w.seal(secret), Until: now + 300000}
		return 200, object{"enrollmentId": id, "otpauthUri": otpURI(secret, u.Phone), "secret": secret}, true
	}
	if method == "POST" && len(parts) == 5 && parts[0] == "me" && parts[1] == "mfa" && parts[2] == "enrollments" && parts[4] == "confirm" {
		c := w.state.Challenges[parts[3]]
		if c == nil || c.Session != sid || c.User != u.ID || c.Kind != "replace" || c.Until <= now {
			return err(404, "not_found", "设置不存在或已过期")
		}
		if c.Failures >= 5 {
			return err(429, "throttled", "验证码错误次数过多")
		}
		// New factor has its own replay history; old factor steps must not block confirmation.
		temp := &webUser{}
		if !w.checkOTP(temp, w.unseal(c.Secret), str("code")) {
			c.Failures++
			return err(401, "bad_code", "验证码错误")
		}
		u.Secret = c.Secret
		u.UsedSteps = temp.UsedSteps
		codes := recovery(u)
		delete(w.state.Challenges, parts[3])
		for key, x := range w.state.Sessions {
			if x.User == u.ID && key != sid {
				delete(w.state.Sessions, key)
			}
		}
		return 200, object{"enabled": true, "recoveryCodes": codes}, true
	}
	return 0, nil, false
}
