package main

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"os"
	"strconv"
	"strings"
)

// Coturn REST credentials expire with this viewing session. The shared key never leaves the server.
func (w *webService) iceServers(session string, until int64) []object {
	path := os.Getenv("GALAXY_TURN_SECRET_FILE")
	if path == "" {
		return []object{}
	}
	secret, err := os.ReadFile(path)
	if err != nil || len(strings.TrimSpace(string(secret))) < 32 {
		return []object{}
	}
	urls := strings.Split(os.Getenv("GALAXY_TURN_URLS"), ",")
	valid := []string{}
	for _, url := range urls {
		url = strings.TrimSpace(url)
		if strings.HasPrefix(url, "turn:") || strings.HasPrefix(url, "turns:") {
			valid = append(valid, url)
		}
	}
	if len(valid) == 0 {
		return []object{}
	}
	username := strconv.FormatInt(until/1000+60, 10) + ":" + digest(session)[:16]
	mac := hmac.New(sha1.New, []byte(strings.TrimSpace(string(secret))))
	mac.Write([]byte(username))
	return []object{{"urls": valid, "username": username, "credential": base64.StdEncoding.EncodeToString(mac.Sum(nil))}}
}
