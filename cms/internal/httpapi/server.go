package httpapi

import (
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"

	"foodtale/cms/internal/clock"
	"foodtale/cms/internal/config"
	"foodtale/cms/internal/store"
)

type Server struct {
	cfg *config.Config
	st  *store.Store
	tpl *templateEngine
}

func Listen(cfg *config.Config, st *store.Store) error {
	s := &Server{cfg: cfg, st: st, tpl: newTemplate()}
	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(middleware.RealIP)
	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)
	r.Use(middleware.Timeout(30 * time.Minute))

	r.Get("/api/health", s.health)
	r.Get("/api/app/version", s.appVersion)
	r.Post("/api/pair", s.pair)
	r.Get("/api/device/manifest", s.manifest)
	r.Post("/api/device/heartbeat", s.heartbeat)

	r.Get("/media/{sha}", s.media)
	r.Get("/foodtale-player.apk", s.apk)
	r.Get("/foodtale-player.json", s.appVersion)

	r.Group(func(r chi.Router) {
		if cfg.AdminPassword != "" {
			r.Use(s.basicAuth)
		}
		r.Get("/", s.admin)
		r.Get("/admin", s.admin)
		r.Post("/admin/devices", s.createDevice)
		r.Post("/admin/devices/{id}/assign", s.assignDevice)
		r.Post("/admin/devices/{id}/unassign", s.unassignDevice)
		r.Post("/admin/devices/{id}/reset", s.resetDevice)
		r.Post("/admin/devices/{id}/delete", s.deleteDevice)
		r.Post("/admin/playlists", s.createPlaylist)
		r.Post("/admin/carousels", s.createCarousel)
		r.Post("/admin/playlists/{id}/items", s.uploadItem)
		r.Post("/admin/playlists/{id}/items/{item}/delete", s.deleteItem)
		r.Post("/admin/playlists/{id}/items/{item}/move", s.moveItem)
		r.Post("/admin/playlists/{id}/delete", s.deletePlaylist)
		r.Post("/admin/playlists/{id}/restart", s.restartSync)
	})

	srv := &http.Server{
		Addr:              cfg.HTTPAddr(),
		Handler:           r,
		ReadHeaderTimeout: 15 * time.Second,
	}
	return srv.ListenAndServe()
}

func (s *Server) basicAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		user, pass, ok := r.BasicAuth()
		if !ok || user != "admin" || pass != s.cfg.AdminPassword {
			w.Header().Set("WWW-Authenticate", `Basic realm="foodtale"`)
			http.Error(w, "auth required", http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":          true,
		"cms_id":      s.cfg.CMSID,
		"http":        s.cfg.AdvertiseURL(),
		"clock_port":  s.cfg.ClockPort,
		"beacon_port": s.cfg.BeaconPort,
		"master_ms":   clock.MasterNowMs(),
	})
}

func (s *Server) appVersion(w http.ResponseWriter, r *http.Request) {
	meta := map[string]any{
		"version":      "0.2.0",
		"version_code": 1,
		"apk_url":      s.cfg.AdvertiseURL() + "/foodtale-player.apk",
		"available":    fileExists(s.cfg.APKPath()),
	}
	if raw, err := os.ReadFile(s.cfg.APKMetaPath()); err == nil {
		var extra map[string]any
		if json.Unmarshal(raw, &extra) == nil {
			for k, v := range extra {
				meta[k] = v
			}
		}
	}
	writeJSON(w, http.StatusOK, meta)
}

func (s *Server) apk(w http.ResponseWriter, r *http.Request) {
	if !fileExists(s.cfg.APKPath()) {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	http.ServeFile(w, r, s.cfg.APKPath())
}

func (s *Server) media(w http.ResponseWriter, r *http.Request) {
	sha := chi.URLParam(r, "sha")
	if len(sha) < 16 || strings.Contains(sha, "/") {
		http.NotFound(w, r)
		return
	}
	path := s.cfg.MediaDir() + "/" + sha + ".mp4"
	if !fileExists(path) {
		http.NotFound(w, r)
		return
	}
	http.ServeFile(w, r, path)
}

func (s *Server) pair(w http.ResponseWriter, r *http.Request) {
	var body struct {
		PairingCode string `json:"pairing_code"`
		DeviceName  string `json:"device_name"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&body); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	d, err := s.st.Pair(body.PairingCode, body.DeviceName)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"device_token": *d.DeviceToken,
		"cms_id":       s.cfg.CMSID,
		"device_id":    d.ID,
		"http":         s.cfg.AdvertiseURL(),
	})
}

func (s *Server) device(r *http.Request) (*store.Device, error) {
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, "Bearer ") {
		return nil, errors.New("unauthorized")
	}
	tok := strings.TrimSpace(strings.TrimPrefix(h, "Bearer "))
	return s.st.DeviceByToken(tok)
}

func (s *Server) manifest(w http.ResponseWriter, r *http.Request) {
	d, err := s.device(r)
	if err != nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	_ = s.st.Touch(d.ID, queryIntPtr(r, "clock_offset_ms"), queryIntPtr(r, "clock_rtt_ms"), nil, nil, nil, nil, remoteIP(r))

	body := s.manifestBody(d)
	raw, _ := json.Marshal(body)
	sum := sha1.Sum(raw)
	etag := `"` + hex.EncodeToString(sum[:]) + `"`
	if match := strings.TrimSpace(r.Header.Get("If-None-Match")); match != "" && match == etag {
		w.Header().Set("ETag", etag)
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.Header().Set("ETag", etag)
	writeJSON(w, http.StatusOK, body)
}

func (s *Server) manifestBody(d *store.Device) map[string]any {
	base := s.cfg.AdvertiseURL()
	if d.PlaylistID == nil {
		return map[string]any{
			"playlist_name":   "Unassigned",
			"playlist_id":     0,
			"device_id":       d.ID,
			"cms_id":          s.cfg.CMSID,
			"kind":            "playlist",
			"panel_count":     1,
			"panel_index":     0,
			"peer_count":      1,
			"peers":           []store.Peer{{ID: d.ID, Name: d.Name}},
			"start_at":        time.Now().UTC().Format(time.RFC3339Nano),
			"start_master_ms": 0,
			"sync_generation": 0,
			"items":           []any{},
		}
	}
	pl, err := s.st.Playlist(*d.PlaylistID)
	if err != nil {
		return map[string]any{"error": err.Error()}
	}
	panelCount := pl.PanelCount
	if panelCount < 1 {
		panelCount = 1
	}
	panelIndex := d.PanelIndex
	if panelIndex < 0 {
		panelIndex = 0
	}
	if panelIndex >= panelCount {
		panelIndex = panelCount - 1
	}
	items := pl.Items
	if pl.IsCarousel() {
		var filtered []store.Item
		for _, it := range items {
			if it.PanelIndex != nil && *it.PanelIndex == panelIndex {
				filtered = append(filtered, it)
			}
		}
		items = filtered
	}
	mapped := make([]map[string]any, 0, len(items))
	for _, it := range items {
		mapped = append(mapped, map[string]any{
			"id":               it.ID,
			"type":             it.Type,
			"url":              base + "/media/" + it.SHA256,
			"sha256":           it.SHA256,
			"duration_ms":      it.DurationMs,
			"fit":              it.Fit,
			"file_duration_ms": it.FileDurationMs,
		})
	}
	peers, _ := s.st.Peers(pl.ID, d.ID)
	return map[string]any{
		"playlist_name":   pl.Name,
		"playlist_id":     pl.ID,
		"device_id":       d.ID,
		"cms_id":          s.cfg.CMSID,
		"kind":            pl.Kind,
		"panel_count":     panelCount,
		"panel_index":     panelIndex,
		"peer_count":      len(peers),
		"peers":           peers,
		"start_at":        pl.StartAt,
		"start_master_ms": pl.StartMasterMs,
		"sync_generation": pl.SyncGeneration,
		"items":           mapped,
	}
}

func (s *Server) heartbeat(w http.ResponseWriter, r *http.Request) {
	d, err := s.device(r)
	if err != nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var body struct {
		ClockOffsetMs *int   `json:"clock_offset_ms"`
		ClockRTTMs    *int   `json:"clock_rtt_ms"`
		Index         *int   `json:"index"`
		ItemID        *int64 `json:"item_id"`
		LagMs         *int   `json:"lag_ms"`
		Playing       *bool  `json:"playing"`
	}
	_ = json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&body)
	_ = s.st.Touch(d.ID, body.ClockOffsetMs, body.ClockRTTMs, body.Index, body.ItemID, body.LagMs, body.Playing, remoteIP(r))
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func queryIntPtr(r *http.Request, key string) *int {
	v := r.URL.Query().Get(key)
	if v == "" {
		return nil
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return nil
	}
	return &n
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func fileExists(path string) bool {
	st, err := os.Stat(path)
	return err == nil && !st.IsDir()
}

func remoteIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return strings.TrimSpace(r.RemoteAddr)
	}
	return host
}
