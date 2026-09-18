package store

import (
	"path/filepath"
	"testing"
)

func TestPairAssignRestart(t *testing.T) {
	dir := t.TempDir()
	st, err := Open(filepath.Join(dir, "cms.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	dev, err := st.CreatePairingCode()
	if err != nil {
		t.Fatal(err)
	}
	paired, err := st.Pair(dev.PairingCode, "Box1")
	if err != nil {
		t.Fatal(err)
	}
	if paired.DeviceToken == nil || *paired.DeviceToken == "" {
		t.Fatal("missing token")
	}
	pl, err := st.CreatePlaylist("Lunch", "playlist", 1)
	if err != nil {
		t.Fatal(err)
	}
	if err := st.Assign(paired.ID, pl.ID, 0); err != nil {
		t.Fatal(err)
	}
	if err := st.RestartSync(pl.ID, "2026-01-01T00:00:08Z", 8000); err != nil {
		t.Fatal(err)
	}
	got, err := st.Playlist(pl.ID)
	if err != nil {
		t.Fatal(err)
	}
	if got.SyncGeneration != 1 || got.StartMasterMs != 8000 {
		t.Fatalf("sync %+v", got)
	}
}

func TestMoveItem(t *testing.T) {
	dir := t.TempDir()
	st, err := Open(filepath.Join(dir, "cms.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	pl, err := st.CreatePlaylist("Lunch", "playlist", 1)
	if err != nil {
		t.Fatal(err)
	}
	a, err := st.AddItem(Item{PlaylistID: pl.ID, Type: "video", SHA256: "aa", Filename: "a.mp4", DurationMs: 1000, Fit: "cut", SortOrder: 0})
	if err != nil {
		t.Fatal(err)
	}
	b, err := st.AddItem(Item{PlaylistID: pl.ID, Type: "video", SHA256: "bb", Filename: "b.mp4", DurationMs: 1000, Fit: "cut", SortOrder: 1})
	if err != nil {
		t.Fatal(err)
	}
	if err := st.MoveItem(b, -1); err != nil {
		t.Fatal(err)
	}
	items, err := st.Items(pl.ID)
	if err != nil {
		t.Fatal(err)
	}
	if items[0].ID != b || items[1].ID != a {
		t.Fatalf("order %d %d want %d %d", items[0].ID, items[1].ID, b, a)
	}
}
