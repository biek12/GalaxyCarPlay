package main

import "testing"

func TestArtistAliasesAndCompositeCreditsKeepTheSamePerformers(t *testing.T) {
	for _, pair := range [][2]string{{"周杰伦 (Jay Chou)", "周杰伦"}, {"林俊杰/G.E.M.邓紫棋", "林俊杰,邓紫棋"}, {"Beyonce", "Beyonce"}} {
		if !artistMatches(pair[0], pair[1]) {
			t.Fatal("matching credits rejected")
		}
	}
	for _, pair := range [][2]string{{"孙楠", "韩红"}, {"林俊杰/邓紫棋", "林俊杰"}, {"Artist A", "Artist B"}} {
		if artistMatches(pair[0], pair[1]) {
			t.Fatal("different performers accepted")
		}
	}
	if normalized("拯救") == normalized("拯救(Live)") {
		t.Fatal("version qualifier discarded")
	}
}
