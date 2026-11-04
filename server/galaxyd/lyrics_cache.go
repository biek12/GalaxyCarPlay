package main

import (
	"container/list"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const lyricCacheTTL = 30 * 24 * time.Hour
const lyricDiskEntries = 1024
const lyricDiskBytes int64 = 32 * 1024 * 1024
const lyricEntryBytes = 512 * 1024

type cachedLyric struct {
	Expires int64   `json:"expires"`
	Lyrics  []Lyric `json:"lyrics"`
}

type lyricMemoryEntry struct {
	key string
	cachedLyric
	bytes int
}

// 只保存成功歌词结果，不记录逐次请求、设备或播放进度。内存LRU逐条淘汰，磁盘跨重启复用。
type lyricResultCache struct {
	mu        sync.Mutex
	directory string
	limit     int
	maxBytes  int
	bytes     int
	entries   map[string]*list.Element
	recent    *list.List
	now       func() time.Time
}

func newLyricCache(directory string, limit, maxBytes int, now func() time.Time) (*lyricResultCache, error) {
	c := &lyricResultCache{directory: directory, limit: limit, maxBytes: maxBytes,
		entries: map[string]*list.Element{}, recent: list.New(), now: now}
	if directory != "" {
		if err := os.MkdirAll(directory, 0700); err != nil {
			return nil, err
		}
		c.pruneDisk()
	}
	return c, nil
}

func (c *lyricResultCache) path(key string) string {
	hash := sha256.Sum256([]byte(key))
	return filepath.Join(c.directory, hex.EncodeToString(hash[:])+".json")
}

func (c *lyricResultCache) remove(element *list.Element) {
	entry := element.Value.(lyricMemoryEntry)
	delete(c.entries, entry.key)
	c.bytes -= entry.bytes
	c.recent.Remove(element)
}

func (c *lyricResultCache) remember(key string, value cachedLyric, size int) {
	if previous := c.entries[key]; previous != nil {
		c.remove(previous)
	}
	if size > c.maxBytes {
		return
	}
	c.entries[key] = c.recent.PushFront(lyricMemoryEntry{key: key, cachedLyric: value, bytes: size})
	c.bytes += size
	for c.recent.Len() > c.limit || c.bytes > c.maxBytes {
		c.remove(c.recent.Back())
	}
}

func (c *lyricResultCache) get(key string) ([]Lyric, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	if element := c.entries[key]; element != nil {
		entry := element.Value.(lyricMemoryEntry)
		if entry.Expires > now.Unix() {
			c.recent.MoveToFront(element)
			if c.directory != "" {
				_ = os.Chtimes(c.path(key), now, now)
			}
			return append([]Lyric(nil), entry.Lyrics...), true
		}
		c.remove(element)
	}
	if c.directory == "" {
		return nil, false
	}
	path := c.path(key)
	file, err := os.Open(path)
	if err != nil {
		return nil, false
	}
	data, err := io.ReadAll(io.LimitReader(file, lyricEntryBytes+1))
	_ = file.Close()
	var value cachedLyric
	if err != nil || len(data) > lyricEntryBytes || json.Unmarshal(data, &value) != nil ||
		value.Expires <= now.Unix() || len(value.Lyrics) == 0 {
		_ = os.Remove(path)
		return nil, false
	}
	c.remember(key, value, len(data))
	_ = os.Chtimes(path, now, now)
	return append([]Lyric(nil), value.Lyrics...), true
}

func (c *lyricResultCache) put(key string, lyrics []Lyric) {
	if len(lyrics) == 0 {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	value := cachedLyric{Expires: c.now().Add(lyricCacheTTL).Unix(), Lyrics: append([]Lyric(nil), lyrics...)}
	data, err := json.Marshal(value)
	if err != nil || len(data) > lyricEntryBytes {
		return
	}
	c.remember(key, value, len(data))
	if c.directory == "" {
		return
	}
	path := c.path(key)
	file, err := os.OpenFile(path+".tmp", os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return
	}
	_, err = file.Write(data)
	closeErr := file.Close()
	if err != nil || closeErr != nil || os.Rename(path+".tmp", path) != nil {
		_ = os.Remove(path + ".tmp")
		return
	}
	now := c.now()
	_ = os.Chtimes(path, now, now)
	c.pruneDisk()
}

// 调用者持有mu或尚未发布实例；目录仅由本服务管理，淘汰仅限本缓存文件。
func (c *lyricResultCache) pruneDisk() {
	files, _ := os.ReadDir(c.directory)
	type diskEntry struct {
		path string
		size int64
		used time.Time
	}
	var entries []diskEntry
	var total int64
	for _, file := range files {
		if !file.IsDir() && strings.HasSuffix(file.Name(), ".tmp") {
			_ = os.Remove(filepath.Join(c.directory, file.Name()))
			continue
		}
		if file.IsDir() || !strings.HasSuffix(file.Name(), ".json") {
			continue
		}
		info, err := file.Info()
		if err != nil {
			continue
		}
		path := filepath.Join(c.directory, file.Name())
		if c.now().Sub(info.ModTime()) >= lyricCacheTTL || info.Size() > lyricEntryBytes {
			_ = os.Remove(path)
			continue
		}
		entries = append(entries, diskEntry{path, info.Size(), info.ModTime()})
		total += info.Size()
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].used.Before(entries[j].used) })
	for index, entry := range entries {
		if len(entries)-index <= lyricDiskEntries && total <= lyricDiskBytes {
			break
		}
		_ = os.Remove(entry.path)
		total -= entry.size
	}
}
