package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"strings"
	"time"
)

func (r *Relay) inviteWeb(out http.ResponseWriter, q *http.Request) {
	host, _, _ := net.SplitHostPort(q.RemoteAddr)
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() || q.Header.Get("Origin") != "" || !equal(strings.TrimPrefix(q.Header.Get("Authorization"), "Bearer "), r.enrollment) {
		http.NotFound(out, q)
		return
	}
	var in struct{ Phone string }
	if !body(out, q, &in) {
		return
	}
	invitation, e := r.web.invite(in.Phone)
	if e != nil {
		webFail(out, 422, "invalid", "邀请参数无效")
		return
	}
	reply(out, 200, object{"invited": true, "invitationCode": invitation})
}
func inviteWebCLI(phone string) error {
	bootstrap, e := os.ReadFile(os.Getenv("GALAXY_ENROLLMENT_FILE"))
	if e != nil {
		return e
	}
	listen := os.Getenv("GALAXY_LISTEN")
	if listen == "" {
		listen = "127.0.0.1:8781"
	}
	host, _, e := net.SplitHostPort(listen)
	if e != nil || !net.ParseIP(host).IsLoopback() {
		return errors.New("local listener required")
	}
	b, _ := json.Marshal(object{"phone": phone})
	q, e := http.NewRequest("POST", "http://"+listen+"/internal/v1/web-invite", bytes.NewReader(b))
	if e != nil {
		return e
	}
	q.Header.Set("Authorization", "Bearer "+strings.TrimSpace(string(bootstrap)))
	client := &http.Client{Timeout: 10 * time.Second}
	res, e := client.Do(q)
	if e != nil {
		return e
	}
	defer res.Body.Close()
	var result struct{ InvitationCode string }
	e = json.NewDecoder(res.Body).Decode(&result)
	if e != nil {
		return e
	}
	if res.StatusCode != 200 {
		return errors.New("invitation rejected")
	}
	if result.InvitationCode == "" {
		return errors.New("invitation missing")
	}
	fmt.Fprintln(os.Stdout, result.InvitationCode)
	return nil
}
