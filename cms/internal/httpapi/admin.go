package httpapi

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"

	"foodtale/cms/internal/clock"
	"foodtale/cms/internal/store"
	"foodtale/cms/internal/transcode"
)

func (s *Server) admin(w http.ResponseWriter, r *http.Request) {
	devices, err := s.st.Devices()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	walls, err := s.st.PlaylistsByKind("carousel")
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	playlists, err := s.st.PlaylistsByKind("playlist")
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	targets, err := s.st.AllPlaylists()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	apkVer := "dev"
	if raw, err := os.ReadFile(s.cfg.APKMetaPath()); err == nil {
		var meta map[string]any
		if json.Unmarshal(raw, &meta) == nil {
			if v, ok := meta["version"].(string); ok && v != "" {
				apkVer = v
			}
		}
	}
	data := adminData{
		CMSID:        s.cfg.CMSID,
		ServerURL:    s.cfg.AdvertiseURL(),
		APKURL:       s.cfg.AdvertiseURL() + "/foodtale-player.apk",
		APKAvailable: fileExists(s.cfg.APKPath()),
		APKVersion:   apkVer,
		Flash:        r.URL.Query().Get("ok"),
		Error:        r.URL.Query().Get("err"),
		Devices:      decorateDevices(devices),
		Walls:        walls,
		Playlists:    playlists,
		Targets:      targets,
		Now:          time.Now().UTC().Format(time.RFC3339),
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := s.tpl.admin.Execute(w, data); err != nil {
		http.Error(w, err.Error(), 500)
	}
}

func (s *Server) createDevice(w http.ResponseWriter, r *http.Request) {
	if _, err := s.st.CreatePairingCode(); err != nil {
		redirect(w, r, "/admin?err="+urlErr(err))
		return
	}
	redirect(w, r, "/admin?ok=pairing+code+created")
}

func (s *Server) assignDevice(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
	if err := r.ParseForm(); err != nil {
		redirect(w, r, "/admin?err=bad+form")
		return
	}
	pid, _ := strconv.ParseInt(r.FormValue("playlist_id"), 10, 64)
	panel, _ := strconv.Atoi(r.FormValue("panel_index"))
	if pid == 0 {
		_ = s.st.Unassign(id)
		redirect(w, r, "/admin?ok=unassigned")
		return
	}
	if err := s.st.Assign(id, pid, panel); err != nil {
		redirect(w, r, "/admin?err="+urlErr(err))
		return
	}
	redirect(w, r, "/admin?ok=assigned")
}

func (s *Server) unassignDevice(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
	_ = s.st.Unassign(id)
	redirect(w, r, "/admin?ok=unassigned")
}

func (s *Server) resetDevice(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
	if err := s.st.ResetDevice(id); err != nil {
		redirect(w, r, "/admin?err="+urlErr(err))
		return
	}
	redirect(w, r, "/admin?ok=pairing+reset")
}

func (s *Server) deleteDevice(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
	_ = s.st.DeleteDevice(id)
	redirect(w, r, "/admin?ok=device+deleted")
}

func (s *Server) createPlaylist(w http.ResponseWriter, r *http.Request) {
	_ = r.ParseForm()
	name := strings.TrimSpace(r.FormValue("name"))
	if name == "" {
		redirect(w, r, "/admin?err=name+required")
		return
	}
	if _, err := s.st.CreatePlaylist(name, "playlist", 1); err != nil {
		redirect(w, r, "/admin?err="+urlErr(err))
		return
	}
	redirect(w, r, "/admin?ok=playlist+created")
}

func (s *Server) createCarousel(w http.ResponseWriter, r *http.Request) {
	_ = r.ParseForm()
	name := strings.TrimSpace(r.FormValue("name"))
	n, _ := strconv.Atoi(r.FormValue("panel_count"))
	if n < 2 {
		n = 2
	}
	if n > 8 {
		n = 8
	}
	if name == "" {
		redirect(w, r, "/admin?err=name+required")
		return
	}
	if _, err := s.st.CreatePlaylist(name, "carousel", n); err != nil {
		redirect(w, r, "/admin?err="+urlErr(err))
		return
	}
	redirect(w, r, "/admin?ok=wall+created")
}

func (s *Server) uploadItem(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
	r.Body = http.MaxBytesReader(w, r.Body, 512<<20)
	if err := r.ParseMultipartForm(512 << 20); err != nil {
		redirect(w, r, "/admin?err=upload+too+large")
		return
	}
	file, hdr, err := r.FormFile("media")
	if err != nil {
		redirect(w, r, "/admin?err=need+an+mp4")
		return
	}
	defer file.Close()
	ext := strings.ToLower(filepath.Ext(hdr.Filename))
	if ext != ".mp4" {
		redirect(w, r, "/admin?err=need+an+.mp4")
		return
	}
	tmp, err := os.CreateTemp("", "foodtale-up-*.mp4")
	if err != nil {
		redirect(w, r, "/admin?err="+urlErr(err))
		return
	}
	defer os.Remove(tmp.Name())
	if _, err := io.Copy(tmp, file); err != nil {
		tmp.Close()
		redirect(w, r, "/admin?err="+urlErr(err))
		return
	}
	tmp.Close()

	res, err := transcode.SaveVideo(tmp.Name(), s.cfg.MediaDir())
	if err != nil {
		redirect(w, r, "/admin?err="+urlErr(err))
		return
	}
	var panel *int
	if v := r.FormValue("panel_index"); v != "" {
		n, _ := strconv.Atoi(v)
		panel = &n
	}
	sort, _ := s.st.NextSort(id, panel)
	_, err = s.st.AddItem(store.Item{
		PlaylistID:     id,
		Type:           "video",
		SHA256:         res.SHA256,
		Filename:       hdr.Filename,
		DurationMs:     res.DurationMs,
		FileDurationMs: res.FileDurationMs,
		Fit:            "cut",
		SortOrder:      sort,
		PanelIndex:     panel,
	})
	if err != nil {
		redirect(w, r, "/admin?err="+urlErr(err))
		return
	}
	redirect(w, r, "/admin?ok=uploaded")
}

func (s *Server) deleteItem(w http.ResponseWriter, r *http.Request) {
	item, _ := strconv.ParseInt(chi.URLParam(r, "item"), 10, 64)
	_ = s.st.DeleteItem(item)
	redirect(w, r, "/admin?ok=item+removed")
}

func (s *Server) moveItem(w http.ResponseWriter, r *http.Request) {
	item, _ := strconv.ParseInt(chi.URLParam(r, "item"), 10, 64)
	_ = r.ParseForm()
	delta := 1
	if r.FormValue("dir") == "up" {
		delta = -1
	}
	if err := s.st.MoveItem(item, delta); err != nil {
		redirect(w, r, "/admin?err="+urlErr(err))
		return
	}
	redirect(w, r, "/admin?ok=reordered")
}

func (s *Server) deletePlaylist(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
	_ = s.st.DeletePlaylist(id)
	redirect(w, r, "/admin?ok=deleted")
}

func (s *Server) restartSync(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
	if err := RestartSync(s.st, id); err != nil {
		redirect(w, r, "/admin?err="+urlErr(err))
		return
	}
	redirect(w, r, "/admin?ok=restart+sync")
}

// RestartAll runs the admin Restart sync button for every playlist.
// Called when the process starts, so a Pi reboot does not leave the old boot clock in the future.
func RestartAll(st *store.Store) error {
	playlists, err := st.AllPlaylists()
	if err != nil {
		return err
	}
	for _, pl := range playlists {
		if err := RestartSync(st, pl.ID); err != nil {
			return err
		}
		log.Printf("restart sync playlist=%d name=%s", pl.ID, pl.Name)
	}
	return nil
}

func RestartSync(st *store.Store, id int64) error {
	master := clock.MasterNowMs() + (8 * time.Second).Milliseconds()
	if rem := master % 1000; rem != 0 {
		master += 1000 - rem
	}
	wait := time.Duration(master-clock.MasterNowMs()) * time.Millisecond
	startAt := time.Now().UTC().Add(wait).Format(time.RFC3339Nano)
	return st.RestartSync(id, startAt, master)
}

func redirect(w http.ResponseWriter, r *http.Request, loc string) {
	http.Redirect(w, r, loc, http.StatusSeeOther)
}

func urlErr(err error) string {
	s := err.Error()
	s = strings.ReplaceAll(s, " ", "+")
	if len(s) > 180 {
		s = s[:180]
	}
	return s
}

type adminDevice struct {
	store.Device
	StatusLabel string
	SeenLabel   string
	ClockLabel  string
	ClockClass  string
	Assigned    string
}

func decorateDevices(in []store.Device) []adminDevice {
	out := make([]adminDevice, 0, len(in))
	now := time.Now().UTC()
	for _, d := range in {
		ad := adminDevice{Device: d, StatusLabel: d.Status, SeenLabel: "never", ClockLabel: "no report", ClockClass: "hint"}
		if d.DeviceToken == nil {
			ad.StatusLabel = "waiting"
		}
		if d.LastSeenAt != nil {
			t, err := time.Parse(time.RFC3339Nano, *d.LastSeenAt)
			if err != nil {
				t, err = time.Parse(time.RFC3339, *d.LastSeenAt)
			}
			if err == nil {
				age := now.Sub(t)
				ad.SeenLabel = fmt.Sprintf("%ds ago", int(age.Seconds()))
				if age < 15*time.Second {
					ad.StatusLabel = "online"
				} else if age < 2*time.Minute {
					ad.StatusLabel = "idle"
				} else {
					ad.StatusLabel = "offline"
				}
			}
		}
		if d.ClockOffsetMs != nil {
			rtt := 0
			if d.ClockRTTMs != nil {
				rtt = *d.ClockRTTMs
			}
			ad.ClockLabel = fmt.Sprintf("off %+dms · rtt %dms", *d.ClockOffsetMs, rtt)
			ad.ClockClass = ""
		}
		if d.Playlist != nil {
			ad.Assigned = d.Playlist.Name
			if d.Playlist.IsCarousel() {
				ad.Assigned = fmt.Sprintf("%s · part %d/%d", d.Playlist.Name, d.PanelIndex+1, d.Playlist.PanelCount)
			}
		} else {
			ad.Assigned = "Unassigned"
		}
		out = append(out, ad)
	}
	return out
}
