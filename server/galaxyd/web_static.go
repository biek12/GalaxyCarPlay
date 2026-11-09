package main

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

func webStatic(dir string) http.HandlerFunc {
	return func(out http.ResponseWriter, q *http.Request) {
		if q.Method != "GET" && q.Method != "HEAD" {
			out.WriteHeader(405)
			return
		}
		path := strings.TrimPrefix(q.URL.Path, "/app/")
		clean := filepath.Clean("/" + path)
		full := filepath.Join(dir, clean)
		if st, e := os.Stat(full); e != nil || st.IsDir() {
			if strings.HasPrefix(path, "assets/") {
				http.NotFound(out, q)
				return
			}
			full = filepath.Join(dir, "index.html")
		}
		out.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")
		out.Header().Set("X-Content-Type-Options", "nosniff")
		out.Header().Set("Referrer-Policy", "no-referrer")
		out.Header().Set("Cache-Control", "no-cache")
		http.ServeFile(out, q, full)
	}
}
