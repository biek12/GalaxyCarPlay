package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"html"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
	"unicode"
)

type Lyric struct {
	Track  string `json:"trackName"`
	Artist string `json:"artistName"`
	Synced string `json:"syncedLyrics"`
	Plain  string `json:"plainLyrics"`
	Source string `json:"source"`
}

var lyricHTTP = &http.Client{Timeout: 10 * time.Second}
var lyricCache, _ = newLyricCache("", 512, 8*1024*1024, time.Now)

func normalized(s string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			return unicode.ToLower(r)
		}
		return -1
	}, s)
}
func artistMatches(wanted, actual string) bool {
	if artistEquivalent(wanted, actual) {
		return true
	}
	split := func(s string) []string {
		return strings.FieldsFunc(s, func(r rune) bool { return strings.ContainsRune("/,、;&", r) })
	}
	a, b := split(wanted), split(actual)
	if len(a) == 0 || len(a) != len(b) {
		return false
	}
	for _, part := range a {
		found := false
		for _, other := range b {
			if artistEquivalent(part, other) {
				found = true
			}
		}
		if !found {
			return false
		}
	}
	return true
}
func artistEquivalent(a, b string) bool {
	if normalized(a) == normalized(b) {
		return true
	}
	han := func(s string) string {
		return strings.Map(func(r rune) rune {
			if unicode.Is(unicode.Han, r) {
				return r
			}
			return -1
		}, s)
	}
	x, y := han(a), han(b)
	return x != "" && x == y
}
func getJSON(ctx context.Context, address string, out any) error {
	q, e := http.NewRequestWithContext(ctx, "GET", address, nil)
	if e != nil {
		return e
	}
	q.Header.Set("User-Agent", "GalaxyPlay/0.1 personal lyric lookup")
	q.Header.Set("Referer", "https://music.163.com/")
	if strings.Contains(address, "qq.com/") {
		q.Header.Set("Referer", "https://y.qq.com/")
	}
	r, e := lyricHTTP.Do(q)
	if e != nil {
		return e
	}
	defer r.Body.Close()
	if r.StatusCode != 200 {
		return io.ErrUnexpectedEOF
	}
	return json.NewDecoder(io.LimitReader(r.Body, 2*1024*1024)).Decode(out)
}
func qqLyrics(ctx context.Context, title, artist string) []Lyric {
	values := url.Values{"w": {title + " " + artist}, "p": {"1"}, "n": {"20"}, "format": {"json"}}
	var search struct {
		Data struct {
			Song struct {
				List []struct {
					Name   string `json:"songname"`
					MID    string `json:"songmid"`
					Singer []struct{ Name string }
				}
			}
		}
	}
	if getJSON(ctx, "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?"+values.Encode(), &search) != nil {
		return nil
	}
	for _, song := range search.Data.Song.List {
		names := []string{}
		for _, s := range song.Singer {
			names = append(names, s.Name)
		}
		if normalized(song.Name) != normalized(title) || !artistMatches(artist, strings.Join(names, ",")) {
			continue
		}
		var value struct {
			Code  int
			Lyric string
		}
		query := url.Values{"songmid": {song.MID}, "format": {"json"}, "nobase64": {"1"}}
		if getJSON(ctx, "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?"+query.Encode(), &value) != nil || value.Code != 0 {
			continue
		}
		content := html.UnescapeString(value.Lyric)
		if !strings.HasPrefix(strings.TrimSpace(content), "[") {
			if decoded, e := base64.StdEncoding.DecodeString(content); e == nil {
				content = string(decoded)
			}
		}
		if strings.HasPrefix(strings.TrimSpace(content), "[") {
			return []Lyric{{Track: title, Artist: artist, Synced: content, Source: "qq"}}
		}
	}
	return nil
}
func lrclib(ctx context.Context, title, artist string) []Lyric {
	var candidates []Lyric
	values := url.Values{"track_name": {title}, "artist_name": {artist}}
	if getJSON(ctx, "https://lrclib.net/api/search?"+values.Encode(), &candidates) != nil {
		return nil
	}
	result := []Lyric{}
	for _, v := range candidates {
		if normalized(v.Track) == normalized(title) && artistMatches(artist, v.Artist) && (v.Synced != "" || v.Plain != "") {
			v.Track = title
			v.Artist = artist
			v.Source = "lrclib"
			result = append(result, v)
		}
	}
	return result
}
func netease(ctx context.Context, title, artist string) []Lyric {
	query := url.Values{"s": {title + " " + artist}, "type": {"1"}, "limit": {"15"}, "offset": {"0"}}
	var search struct {
		Result struct {
			Songs []struct {
				ID      int64
				Name    string
				Artists []struct{ Name string }
			}
		}
	}
	if getJSON(ctx, "https://music.163.com/api/search/get/web?"+query.Encode(), &search) != nil {
		return nil
	}
	for _, s := range search.Result.Songs {
		names := []string{}
		for _, a := range s.Artists {
			names = append(names, a.Name)
		}
		if normalized(title) != normalized(s.Name) || !artistMatches(artist, strings.Join(names, ",")) {
			continue
		}
		var value struct{ LRC struct{ Lyric string } }
		if getJSON(ctx, "https://music.163.com/api/song/lyric?id="+strconv.FormatInt(s.ID, 10)+"&lv=1&kv=1&tv=-1", &value) == nil && strings.TrimSpace(value.LRC.Lyric) != "" {
			return []Lyric{{Track: title, Artist: artist, Synced: value.LRC.Lyric, Source: "netease"}}
		}
	}
	return nil
}
func lookupLyrics(ctx context.Context, title, artist string) ([]Lyric, bool) {
	key := normalized(title) + "\x01" + normalized(artist)
	cached, ok := lyricCache.get(key)
	if ok {
		return cached, true
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	results := make(chan []Lyric, 3)
	for _, provider := range []func(context.Context, string, string) []Lyric{qqLyrics, netease, lrclib} {
		go func(fetch func(context.Context, string, string) []Lyric) { results <- fetch(ctx, title, artist) }(provider)
	}
	var result []Lyric
collect:
	for i := 0; i < 3; i++ {
		select {
		case candidates := <-results:
			if len(candidates) > 0 {
				result = candidates
				for _, v := range candidates {
					if v.Synced != "" {
						break collect
					}
				}
			}
		case <-ctx.Done():
			break collect
		}
	}
	// 只缓存命中；网络失败/查无结果都允许下次重新查询，不以空结果覆盖已有歌词。
	if len(result) > 0 {
		lyricCache.put(key, result)
	}
	if result == nil {
		return []Lyric{}, false
	}
	return result, false
}
func lyricsSearch(w http.ResponseWriter, q *http.Request) {
	title, artist := strings.TrimSpace(q.URL.Query().Get("track_name")), strings.TrimSpace(q.URL.Query().Get("artist_name"))
	if len(title) == 0 || len(artist) == 0 || len(title) > 900 || len(artist) > 900 {
		fail(w, 400)
		return
	}
	ctx, cancel := context.WithTimeout(q.Context(), 18*time.Second)
	defer cancel()
	result, hit := lookupLyrics(ctx, title, artist)
	cacheState := "MISS"
	if hit {
		cacheState = "HIT"
	}
	w.Header().Set("X-Galaxy-Lyrics-Cache", cacheState)
	reply(w, 200, result)
}
