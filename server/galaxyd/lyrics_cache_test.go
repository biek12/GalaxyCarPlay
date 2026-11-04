package main

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestLyricCacheLRUBytesAndCopies(t *testing.T) {
	now := time.Now()
	c, err := newLyricCache("", 2, 4096, func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	lyric := []Lyric{{Track: "song", Artist: "artist", Synced: "[00:01]line"}}
	c.put("a", lyric)
	lyric[0].Synced = "caller changed"
	c.put("b", lyric)
	got, ok := c.get("a")
	if !ok || got[0].Synced != "[00:01]line" {
		t.Fatal("cache retained caller-owned slice")
	}
	got[0].Synced = "reader changed"
	c.put("c", lyric)
	if _, ok := c.get("b"); ok {
		t.Fatal("least recently used entry was not evicted")
	}
	if got, ok := c.get("a"); !ok || got[0].Synced != "[00:01]line" {
		t.Fatal("cache changed through returned slice")
	}
	c.maxBytes = 1
	c.put("oversize", lyric)
	if _, ok := c.get("oversize"); ok {
		t.Fatal("byte budget ignored")
	}
	c.put("empty", nil)
	if _, ok := c.get("empty"); ok {
		t.Fatal("negative result cached")
	}
}

func TestLyricCacheSurvivesRestartAndExpires(t *testing.T) {
	dir := t.TempDir()
	now := time.Now()
	clock := func() time.Time { return now }
	first, err := newLyricCache(dir, 2, 4096, clock)
	if err != nil {
		t.Fatal(err)
	}
	first.put("song", []Lyric{{Track: "song", Artist: "artist", Synced: "[00:01]line"}})
	if info, err := os.Stat(first.path("song")); err != nil || info.Mode().Perm() != 0600 {
		t.Fatal("private persistent cache missing")
	}
	restarted, err := newLyricCache(dir, 2, 4096, clock)
	if err != nil {
		t.Fatal(err)
	}
	if got, ok := restarted.get("song"); !ok || got[0].Synced != "[00:01]line" {
		t.Fatal("restart lost result")
	}
	now = now.Add(lyricCacheTTL + time.Second)
	if _, ok := restarted.get("song"); ok {
		t.Fatal("expired memory/disk result returned")
	}
	if err := os.WriteFile(restarted.path("broken"), []byte("{"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, ok := restarted.get("broken"); ok {
		t.Fatal("corrupt disk result accepted")
	}
}

func TestLyricCacheDiskQuotaAndInterruptedWriteCleanup(t *testing.T) {
	dir := t.TempDir()
	// 稀疏文件模拟磁盘配额，不把大份歌词夹具写入测试源码。
	for i := 0; i < 70; i++ {
		path := filepath.Join(dir, fmt.Sprintf("%064x.json", i))
		if err := os.WriteFile(path, []byte("{}"), 0600); err != nil {
			t.Fatal(err)
		}
		if err := os.Truncate(path, lyricEntryBytes); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(dir, "interrupted.json.tmp"), []byte("partial"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := newLyricCache(dir, 2, 4096, time.Now); err != nil {
		t.Fatal(err)
	}
	files, _ := os.ReadDir(dir)
	var size int64
	for _, file := range files {
		info, _ := file.Info()
		size += info.Size()
	}
	if size > lyricDiskBytes || len(files) > lyricDiskEntries {
		t.Fatal("disk quota exceeded")
	}
	if _, err := os.Stat(filepath.Join(dir, "interrupted.json.tmp")); !os.IsNotExist(err) {
		t.Fatal("interrupted write retained")
	}
}

func TestLyricCacheConcurrentReadersAndWriters(t *testing.T) {
	c, _ := newLyricCache("", 16, 4096, time.Now)
	var workers sync.WaitGroup
	for i := 0; i < 8; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for j := 0; j < 100; j++ {
				key := fmt.Sprint(j % 20)
				c.put(key, []Lyric{{Synced: "[00:01]line"}})
				c.get(key)
			}
		}()
	}
	workers.Wait()
}
