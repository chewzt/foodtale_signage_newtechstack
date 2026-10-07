package httpapi

import (
	"database/sql"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"foodtale/cms/internal/config"
	"foodtale/cms/internal/dms"
	"foodtale/cms/internal/store"
)

func TestClaimHeadThenScreen(t *testing.T) {
	var mu sync.Mutex
	type row struct {
		role    string
		claimed bool
	}
	rows := map[string]row{}
	sms := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		if r.Method == http.MethodPost {
			var body struct {
				Code  string `json:"code"`
				Role  string `json:"role"`
				CMSID string `json:"cms_id"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			if existing, ok := rows[body.Code]; ok && existing.claimed {
				w.WriteHeader(http.StatusUnprocessableEntity)
				_, _ = io.WriteString(w, `{"message":"Code already used."}`)
				return
			}
			if body.Role == "screen" {
				headOK := false
				for _, item := range rows {
					if item.role == "head" && item.claimed {
						headOK = true
					}
				}
				if !headOK {
					w.WriteHeader(http.StatusUnprocessableEntity)
					_, _ = io.WriteString(w, `{"message":"Scan the head first."}`)
					return
				}
			}
			rows[body.Code] = row{role: body.Role}
			w.WriteHeader(http.StatusCreated)
			_, _ = io.WriteString(w, `{"code":"`+body.Code+`","claimed":false,"expired":false}`)
			return
		}
		code := strings.TrimPrefix(r.URL.Path, "/api/claims/")
		item, ok := rows[code]
		if !ok {
			http.NotFound(w, r)
			return
		}
		claimed := "false"
		if item.claimed {
			claimed = "true"
		}
		_, _ = io.WriteString(w, `{"code":"`+code+`","claimed":`+claimed+`,"expired":false}`)
	}))
	defer sms.Close()

	dir := t.TempDir()
	st, err := store.Open(filepath.Join(dir, "cms.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	s := &Server{cfg: &config.Config{CMSID: "cms-test", DMSPublicURL: sms.URL, HTTPPort: 8080}, st: st}

	rec := httptest.NewRecorder()
	s.claimHead(rec, httptest.NewRequest(http.MethodGet, "/api/claim/head", nil))
	var head claimResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &head); err != nil {
		t.Fatal(err)
	}
	if !head.Ready || head.Code == "" || !strings.HasPrefix(head.URL, sms.URL+"/claim/") {
		t.Fatalf("head claim: %+v", head)
	}
	if head.DeviceToken == "" {
		t.Fatal("missing local token")
	}

	rec = httptest.NewRecorder()
	s.claimScreen(rec, httptest.NewRequest(http.MethodPost, "/api/claim/screen", strings.NewReader(`{"device_name":"Bar"}`)))
	var early claimResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &early)
	if early.HeadClaimed || early.Code != "" {
		t.Fatalf("screen before head: %+v", early)
	}

	mu.Lock()
	rows[head.Code] = row{role: "head", claimed: true}
	mu.Unlock()

	rec = httptest.NewRecorder()
	s.claimHead(rec, httptest.NewRequest(http.MethodGet, "/api/claim/head", nil))
	_ = json.Unmarshal(rec.Body.Bytes(), &head)
	if !head.Claimed {
		t.Fatalf("head should be claimed: %+v", head)
	}

	rec = httptest.NewRecorder()
	s.claimScreen(rec, httptest.NewRequest(http.MethodPost, "/api/claim/screen", strings.NewReader(`{"device_name":"Bar"}`)))
	var screen claimResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &screen)
	if !screen.Ready || !screen.HeadClaimed || screen.Code == "" || screen.Code == head.Code {
		t.Fatalf("screen claim: %+v", screen)
	}

	rec = httptest.NewRecorder()
	s.claimScreen(rec, httptest.NewRequest(http.MethodPost, "/api/claim/screen", strings.NewReader(`{"device_token":"`+screen.DeviceToken+`"}`)))
	var again claimResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &again)
	if again.Code != screen.Code || again.DeviceID != screen.DeviceID {
		t.Fatalf("screen code rotated: %+v", again)
	}
}

func TestPollRevokeClearsTheLocalToken(t *testing.T) {
	var mu sync.Mutex
	revoked := false
	sms := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		gone := revoked
		mu.Unlock()
		revokedJSON := "false"
		if gone {
			revokedJSON = "true"
		}
		_, _ = io.WriteString(w, `{"code":"OLD","claimed":true,"expired":false,"revoked":`+revokedJSON+`}`)
	}))
	defer sms.Close()

	dir := t.TempDir()
	st, err := store.Open(filepath.Join(dir, "cms.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	dev, err := st.PrepareClaimDevice("Head")
	if err != nil {
		t.Fatal(err)
	}
	if err := st.SaveDmsClaim(store.DmsClaim{LocalDeviceID: dev.ID, Role: "head", Code: "OLD", Claimed: true}); err != nil {
		t.Fatal(err)
	}
	s := &Server{cfg: &config.Config{CMSID: "cms-test", DMSPublicURL: sms.URL, HTTPPort: 8080}, st: st}
	s.pollRevokes()
	still, _ := st.DeviceByID(dev.ID)
	if still.DeviceToken == nil || *still.DeviceToken == "" {
		t.Fatal("token cleared before revoke")
	}
	mu.Lock()
	revoked = true
	mu.Unlock()
	s.pollRevokes()
	cleared, err := st.DeviceByID(dev.ID)
	if err != nil {
		t.Fatal(err)
	}
	if cleared.DeviceToken != nil && *cleared.DeviceToken != "" {
		t.Fatal("token still set")
	}
	claim, err := st.DmsClaimByDevice(dev.ID)
	if err != nil || claim == nil || claim.Claimed || claim.Code == "OLD" {
		t.Fatalf("claim not rotated: %+v", claim)
	}

	mu.Lock()
	revoked = false
	mu.Unlock()
	rec := httptest.NewRecorder()
	s.claimHead(rec, httptest.NewRequest(http.MethodGet, "/api/claim/head", nil))
	var body claimResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if !body.Claimed || body.DeviceToken == "" {
		t.Fatalf("reclaim did not issue a token: %+v", body)
	}
}

func TestPollPlaybackAssignsThePublishedVideo(t *testing.T) {
	var assigned bool
	file := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/file") {
			_, _ = io.WriteString(w, "video-bytes")
			return
		}
		if !assigned {
			_, _ = io.WriteString(w, `{"assigned":false,"playlist_id":0,"playlist_name":"","generation":0,"items":[]}`)
			return
		}
		_, _ = io.WriteString(w, `{"assigned":true,"playlist_id":2,"playlist_name":"Wall","media_asset_id":3,"generation":3,"items":[{"id":9,"name":"first","type":"video","media_asset_id":3,"duration_ms":4000},{"id":10,"name":"poster","type":"image","media_asset_id":4,"duration_ms":10000}]}`)
	}))
	defer file.Close()

	dir := t.TempDir()
	st, err := store.Open(filepath.Join(dir, "cms.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	dev, err := st.PrepareClaimDevice("Head")
	if err != nil {
		t.Fatal(err)
	}
	if err := st.SaveDmsClaim(store.DmsClaim{LocalDeviceID: dev.ID, Role: "head", Code: "PLAY", Claimed: true}); err != nil {
		t.Fatal(err)
	}
	s := &Server{cfg: &config.Config{CMSID: "cms-test", DMSPublicURL: file.URL, DataDir: dir, HTTPPort: 8080}, st: st}

	s.pollPlaybacks()
	bare, _ := st.DeviceByID(dev.ID)
	if bare.PlaylistID != nil {
		t.Fatal("assigned before DMS had a playlist")
	}

	assigned = true
	s.pollPlaybacks()
	playing, err := st.DeviceByID(dev.ID)
	if err != nil || playing.PlaylistID == nil {
		t.Fatal(err)
	}
	pl, err := st.Playlist(*playing.PlaylistID)
	if err != nil {
		t.Fatal(err)
	}
	if pl.Name != "Wall" || len(pl.Items) != 2 || pl.Items[0].Type != "video" || pl.Items[0].DurationMs != 4000 || pl.Items[1].Type != "image" || pl.Items[1].DurationMs != 10000 {
		t.Fatalf("playlist: %+v", pl)
	}
	if _, _, ok, err := st.DmsLocalPlaylist(2, 0); err != nil || !ok {
		t.Fatalf("shared wall playlist was not stored: %v", err)
	}
	if playing.PanelIndex != 0 {
		t.Fatalf("screen row was not stored as the panel, got %d", playing.PanelIndex)
	}
	choice, err := st.DmsChoiceSHA(dev.ID)
	if err != nil || choice != "" {
		t.Fatalf("row playback should not pin one file, choice %q err %v", choice, err)
	}
	if _, err := os.Stat(filepath.Join(dir, "media", pl.Items[0].SHA256+".mp4")); err != nil {
		t.Fatal(err)
	}
	generation := pl.SyncGeneration
	s.pollPlaybacks()
	again, _ := st.Playlist(pl.ID)
	if again.SyncGeneration != generation {
		t.Fatalf("sync restarted without a new publication: %d -> %d", generation, again.SyncGeneration)
	}

	assigned = false
	s.pollPlaybacks()
	cleared, _ := st.DeviceByID(dev.ID)
	if cleared.PlaylistID != nil {
		t.Fatal("playlist stayed assigned after DMS cleared it")
	}
}

func TestAdoptAndJoinFollowsTheBranchHead(t *testing.T) {
	roles := map[string]string{
		"BOX": `{"device_id":8,"branch_id":1,"is_head":true,"head_device_id":8,"head_device_name":"tvbox"}`,
		"TAB": `{"device_id":9,"branch_id":1,"is_head":false,"head_device_id":8,"head_device_name":"tvbox"}`,
	}
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		code := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/api/claims/"), "/role")
		body, ok := roles[code]
		if !ok {
			http.NotFound(w, r)
			return
		}
		_, _ = io.WriteString(w, body)
	}))
	defer fake.Close()

	dir := t.TempDir()
	st, err := store.Open(filepath.Join(dir, "cms.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	s := &Server{cfg: &config.Config{CMSID: "cms-test", DMSPublicURL: fake.URL, DataDir: dir, HTTPPort: 8080}, st: st}

	rec := httptest.NewRecorder()
	s.adoptClaim(rec, httptest.NewRequest(http.MethodPost, "/api/claim/adopt", strings.NewReader(`{"code":"TAB"}`)))
	if rec.Code != http.StatusConflict {
		t.Fatalf("follower adopted: %d %s", rec.Code, rec.Body.String())
	}

	rec = httptest.NewRecorder()
	s.adoptClaim(rec, httptest.NewRequest(http.MethodPost, "/api/claim/adopt", strings.NewReader(`{"code":"BOX"}`)))
	if rec.Code != http.StatusOK {
		t.Fatalf("head adopt: %d %s", rec.Code, rec.Body.String())
	}
	var head claimResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &head); err != nil || head.DeviceToken == "" || !head.Claimed {
		t.Fatalf("adopt payload: %+v", head)
	}

	rec = httptest.NewRecorder()
	s.joinClaim(rec, httptest.NewRequest(http.MethodPost, "/api/claim/join", strings.NewReader(`{"code":"TAB"}`)))
	if rec.Code != http.StatusOK {
		t.Fatalf("join: %d %s", rec.Code, rec.Body.String())
	}
	var guest claimResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &guest); err != nil || guest.DeviceToken == "" || guest.DeviceToken == head.DeviceToken {
		t.Fatalf("join payload: %+v", guest)
	}

	roles["OTHER"] = `{"device_id":3,"branch_id":2,"is_head":false,"head_device_id":4,"head_device_name":"gate"}`
	rec = httptest.NewRecorder()
	s.joinClaim(rec, httptest.NewRequest(http.MethodPost, "/api/claim/join", strings.NewReader(`{"code":"OTHER"}`)))
	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("foreign branch joined: %d", rec.Code)
	}
}

func TestReportRevokesOneAndAssignsTheOther(t *testing.T) {
	var mu sync.Mutex
	var posts int
	var fileGets int
	var posted []dms.ReportDevice
	var unexpected []string
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		if r.Method == http.MethodPost && r.URL.Path == "/api/claims/HEAD/report" {
			posts++
			var body struct {
				Devices []dms.ReportDevice `json:"devices"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			posted = body.Devices
			_, _ = io.WriteString(w, `{"devices":[`+
				`{"code":"HEAD","revoked":false,"is_head":true,"playback":{"assigned":true,"playlist_id":2,"playlist_name":"Wall","media_asset_id":3,"generation":3,"items":[{"id":9,"name":"first","type":"video","media_asset_id":3,"duration_ms":4000}]}},`+
				`{"code":"GUEST","revoked":true,"is_head":false,"playback":{"assigned":false,"playlist_id":0,"playlist_name":"","media_asset_id":0,"generation":0,"items":[]}}`+
				`]}`)
			return
		}
		if r.Method == http.MethodGet && strings.Contains(r.URL.Path, "/items/") && strings.HasSuffix(r.URL.Path, "/file") {
			fileGets++
			_, _ = io.WriteString(w, "video-bytes")
			return
		}
		unexpected = append(unexpected, r.Method+" "+r.URL.Path)
		http.NotFound(w, r)
	}))
	defer fake.Close()

	dir := t.TempDir()
	st, err := store.Open(filepath.Join(dir, "cms.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	headDev, err := st.PrepareClaimDevice("Head")
	if err != nil {
		t.Fatal(err)
	}
	guestDev, err := st.PrepareClaimDevice("Screen")
	if err != nil {
		t.Fatal(err)
	}
	if err := st.SaveDmsClaim(store.DmsClaim{LocalDeviceID: headDev.ID, Role: "head", Code: "HEAD", Claimed: true}); err != nil {
		t.Fatal(err)
	}
	if err := st.SaveDmsClaim(store.DmsClaim{LocalDeviceID: guestDev.ID, Role: "screen", Code: "GUEST", Claimed: true}); err != nil {
		t.Fatal(err)
	}
	playing := true
	itemID := int64(1)
	if err := st.Touch(headDev.ID, nil, nil, nil, &itemID, nil, &playing, ""); err != nil {
		t.Fatal(err)
	}
	conn, err := sql.Open("sqlite", filepath.Join(dir, "cms.db")+"?_pragma=busy_timeout(5000)")
	if err != nil {
		t.Fatal(err)
	}
	stale := time.Now().UTC().Add(-40 * time.Second).Format(time.RFC3339Nano)
	if _, err := conn.Exec(`UPDATE devices SET last_seen_at=?, play_playing=1 WHERE id=?`, stale, guestDev.ID); err != nil {
		t.Fatal(err)
	}
	_ = conn.Close()
	s := &Server{cfg: &config.Config{CMSID: "cms-test", DMSPublicURL: fake.URL, DataDir: dir, HTTPPort: 8080}, st: st}

	s.submitReport()
	mu.Lock()
	if posts != 1 || len(unexpected) != 0 {
		t.Fatalf("posts=%d unexpected=%v", posts, unexpected)
	}
	var headRow, guestRow *dms.ReportDevice
	for i := range posted {
		switch posted[i].Code {
		case "HEAD":
			headRow = &posted[i]
		case "GUEST":
			guestRow = &posted[i]
		}
	}
	if headRow == nil || !headRow.Playing || headRow.SeenSeconds > 20 {
		t.Fatalf("head row: %+v", headRow)
	}
	if guestRow == nil || guestRow.Playing || guestRow.SeenSeconds < 20 {
		t.Fatalf("guest row: %+v", guestRow)
	}
	gets := fileGets
	mu.Unlock()
	if gets != 1 {
		t.Fatalf("file downloads: %d", gets)
	}
	playingNow, err := st.DeviceByID(headDev.ID)
	if err != nil || playingNow.PlaylistID == nil {
		t.Fatal(err)
	}
	guestClaim, err := st.DmsClaimByDevice(guestDev.ID)
	if err != nil || guestClaim == nil || guestClaim.Claimed || guestClaim.Code == "GUEST" {
		t.Fatalf("guest was not revoked: %+v", guestClaim)
	}
	cleared, _ := st.DeviceByID(guestDev.ID)
	if cleared.DeviceToken != nil {
		t.Fatal("revoked guest kept its token")
	}

	s.submitReport()
	mu.Lock()
	defer mu.Unlock()
	if posts != 2 || fileGets != 1 || len(unexpected) != 0 {
		t.Fatalf("second report posts=%d files=%d unexpected=%v", posts, fileGets, unexpected)
	}

	s.cfg.DMSPublicURL = "http://127.0.0.1:1"
	s.submitReport()
	still, _ := st.DeviceByID(headDev.ID)
	if still.DeviceToken == nil || still.PlaylistID == nil {
		t.Fatal("a failed report cleared the head")
	}
}

func TestClaimWithoutDmsURL(t *testing.T) {
	dir := t.TempDir()
	st, err := store.Open(filepath.Join(dir, "cms.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	s := &Server{cfg: &config.Config{CMSID: "cms-test", HTTPPort: 8080}, st: st}
	rec := httptest.NewRecorder()
	s.claimHead(rec, httptest.NewRequest(http.MethodGet, "/api/claim/head", nil))
	var body claimResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &body)
	if body.Ready || body.Reason != "dms_url_missing" || body.URL != "" {
		t.Fatalf("invented a url: %+v", body)
	}
}

func TestWallScreensShareOnePlaylistAndOneLeader(t *testing.T) {
	file := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/file") {
			_, _ = io.WriteString(w, "video-bytes")
			return
		}
		row := 2
		if strings.Contains(r.URL.Path, "/MK49/") {
			row = 3
		}
		_, _ = io.WriteString(w, `{"assigned":true,"playlist_id":56,"playlist_name":"restaurant wall","generation":1,"screen_row":`+strconv.Itoa(row)+`,"row_items":[{"id":1,"name":"clip","type":"video","media_asset_id":1,"duration_ms":4000,"row_index":`+strconv.Itoa(row)+`}],"wall":[{"id":1,"name":"clip","type":"video","media_asset_id":1,"duration_ms":4000,"row_index":2},{"id":2,"name":"still","type":"image","media_asset_id":2,"duration_ms":10000,"row_index":2},{"id":3,"name":"other","type":"video","media_asset_id":3,"duration_ms":8000,"row_index":3}]}`)
	}))
	defer file.Close()

	dir := t.TempDir()
	st, err := store.Open(filepath.Join(dir, "cms.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	head, err := st.PrepareClaimDevice("Head")
	if err != nil {
		t.Fatal(err)
	}
	screen, err := st.PrepareClaimDevice("Screen")
	if err != nil {
		t.Fatal(err)
	}
	if err := st.SaveDmsClaim(store.DmsClaim{LocalDeviceID: head.ID, Role: "head", Code: "43Z8", Claimed: true}); err != nil {
		t.Fatal(err)
	}
	if err := st.SaveDmsClaim(store.DmsClaim{LocalDeviceID: screen.ID, Role: "screen", Code: "MK49", Claimed: true}); err != nil {
		t.Fatal(err)
	}
	s := &Server{cfg: &config.Config{CMSID: "cms-test", DMSPublicURL: file.URL, DataDir: dir, HTTPPort: 8080}, st: st}
	s.pollPlaybacks()

	headDev, _ := st.DeviceByID(head.ID)
	screenDev, _ := st.DeviceByID(screen.ID)
	if headDev.PlaylistID == nil || screenDev.PlaylistID == nil || *headDev.PlaylistID != *screenDev.PlaylistID {
		t.Fatalf("screens split onto their own playlists: head %+v screen %+v", headDev.PlaylistID, screenDev.PlaylistID)
	}
	if headDev.PanelIndex != 2 || screenDev.PanelIndex != 3 {
		t.Fatalf("panels head=%d screen=%d", headDev.PanelIndex, screenDev.PanelIndex)
	}
	peers, err := st.Peers(*headDev.PlaylistID, screenDev.ID)
	if err != nil || len(peers) != 2 {
		t.Fatalf("peers %+v err %v", peers, err)
	}
	body := s.manifestBody(screenDev)
	if body["leader_id"] != head.ID {
		t.Fatalf("screen crowned itself, leader_id=%v", body["leader_id"])
	}
	items, _ := body["items"].([]map[string]any)
	if len(items) != 1 || items[0]["duration_ms"] != 8000 {
		t.Fatalf("screen saw another row: %+v", items)
	}
}
