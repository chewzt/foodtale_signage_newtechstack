package httpapi

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	"foodtale/cms/internal/dms"
	"foodtale/cms/internal/store"
)

func (s *Server) submitReport() {
	if strings.TrimSpace(s.cfg.DMSPublicURL) == "" {
		return
	}
	head, err := s.st.DmsClaimByRole("head")
	if err != nil || head == nil || !head.Claimed {
		return
	}
	claims, err := s.st.DmsClaims()
	if err != nil {
		return
	}
	rows := make([]dms.ReportDevice, 0, len(claims))
	acks := make([]struct {
		deviceID int64
		eventIDs []string
	}, 0, len(claims))
	for _, claim := range claims {
		if !claim.Claimed {
			continue
		}
		dev, err := s.st.DeviceByID(claim.LocalDeviceID)
		if err != nil || dev == nil {
			continue
		}
		seen := seenSeconds(dev.LastSeenAt)
		playing := dev.PlayPlaying && seen <= 20
		item := ""
		if dev.PlayItemID != nil {
			item, _ = s.st.ItemFilename(*dev.PlayItemID)
		}
		tel, _ := s.st.Telemetry(dev.ID)
		pending, _ := s.st.TakeEvents(dev.ID, 50)
		events := make([]dms.ReportEvent, 0, len(pending))
		ids := make([]string, 0, len(pending))
		for _, event := range pending {
			var meta map[string]any
			if event.Meta != "" {
				_ = json.Unmarshal([]byte(event.Meta), &meta)
			}
			events = append(events, dms.ReportEvent{
				ID:       event.ID,
				At:       event.OccurredAt,
				Type:     event.Type,
				Severity: event.Severity,
				Message:  event.Message,
				Meta:     meta,
			})
			ids = append(ids, event.ID)
		}
		rows = append(rows, dms.ReportDevice{
			Code:          claim.Code,
			Playing:       playing,
			Item:          item,
			SeenSeconds:   seen,
			Speed:         tel.Speed,
			DriftMs:       tel.DriftMs,
			ClockOffsetMs: dev.ClockOffsetMs,
			ClockRttMs:    dev.ClockRTTMs,
			LeadMs:        tel.LeadMs,
			Phase:         tel.Phase,
			AppVersion:    tel.AppVersion,
			PlayedMs:      tel.PlayedMs,
			DriftAbsSum:   tel.DriftAbsSum,
			DriftSamples:  tel.DriftSamples,
			SpeedNudges:   tel.SpeedNudges,
			UptimeMs:      tel.UptimeMs,
			Events:        events,
		})
		acks = append(acks, struct {
			deviceID int64
			eventIDs []string
		}{dev.ID, ids})
	}
	client := dms.New(s.cfg.DMSPublicURL)
	results, brand, err := client.Report(head.Code, rows)
	if err != nil {
		if strings.Contains(err.Error(), "dms 422") {
			s.branchHead.Store(false)
		}
		log.Printf("dms report: %v", err)
		return
	}
	for _, ack := range acks {
		_ = s.st.AckReport(ack.deviceID, ack.eventIDs)
	}
	s.applyBrand(client, head.Code, brand)
	demoted := false
	for _, result := range results {
		if result.Code == head.Code && !result.IsHead {
			demoted = true
			s.branchHead.Store(false)
			if result.HeadDeviceID != 0 {
				s.branchHeadID.Store(result.HeadDeviceID)
				s.branchHeadName.Store(result.HeadDeviceName)
			}
		}
	}
	if demoted {
		return
	}
	byCode := map[string]dms.ReportResult{}
	for _, result := range results {
		byCode[result.Code] = result
	}
	for _, claim := range claims {
		result, ok := byCode[claim.Code]
		if !ok {
			continue
		}
		if result.Revoked {
			current := claim
			s.revokeLocal(&current)
			continue
		}
		if err := s.applyPlayback(client, &claim, result.Playback); err != nil {
			log.Printf("dms playback device=%d: %v", claim.LocalDeviceID, err)
		}
	}
}

func seenSeconds(raw *string) int {
	if raw == nil || strings.TrimSpace(*raw) == "" {
		return 1 << 20
	}
	seen, err := time.Parse(time.RFC3339Nano, *raw)
	if err != nil {
		return 1 << 20
	}
	secs := int(time.Since(seen).Seconds())
	if secs < 0 {
		return 0
	}
	return secs
}

func (s *Server) pollPlaybacks() {
	if strings.TrimSpace(s.cfg.DMSPublicURL) == "" {
		return
	}
	claims, err := s.st.DmsClaims()
	if err != nil {
		return
	}
	client := dms.New(s.cfg.DMSPublicURL)
	for _, claim := range claims {
		if !claim.Claimed {
			continue
		}
		playback, err := client.Playback(claim.Code)
		if err != nil {
			continue
		}
		if err := s.applyPlayback(client, &claim, playback); err != nil {
			log.Printf("dms playback device=%d: %v", claim.LocalDeviceID, err)
		}
	}
}

func (s *Server) applyPlayback(client *dms.Client, claim *store.DmsClaim, playback dms.Playback) error {
	dev, err := s.st.DeviceByID(claim.LocalDeviceID)
	if err != nil {
		return err
	}
	if playback.Schedule != nil && playback.Schedule.ID != 0 {
		raw, err := json.Marshal(playback.Schedule)
		if err != nil {
			return err
		}
		if err := s.st.SaveDeviceSchedule(dev.ID, playback.ScreenRow, string(raw)); err != nil {
			return err
		}
		for _, nested := range playback.Schedule.Playlists {
			if _, err := s.prepareLocal(client, claim.Code, nested); err != nil {
				return err
			}
		}
		if err := s.st.SaveDmsChoice(dev.ID, 0, ""); err != nil {
			return err
		}
		return s.activateSchedule(dev, *playback.Schedule, playback.ScreenRow)
	}
	if err := s.st.ClearDeviceSchedule(dev.ID); err != nil {
		return err
	}
	localID, err := s.prepareLocal(client, claim.Code, playback)
	if err != nil {
		return err
	}
	if localID == 0 {
		if dev.PlaylistID != nil {
			return s.st.Unassign(dev.ID)
		}
		return nil
	}
	if err := s.st.SaveDmsChoice(dev.ID, 0, ""); err != nil {
		return err
	}
	if dev.PlaylistID != nil && *dev.PlaylistID == localID && dev.PanelIndex == playback.ScreenRow {
		return nil
	}
	return s.st.Assign(dev.ID, localID, playback.ScreenRow)
}

func (s *Server) prepareLocal(client *dms.Client, code string, playback dms.Playback) (int64, error) {
	if !playback.Assigned || playback.PlaylistID == 0 || len(playableItems(rowSource(playback))) == 0 {
		return 0, nil
	}
	wall := wallSource(playback)
	generation, localID, ok, err := s.st.DmsLocalPlaylist(playback.PlaylistID, 0)
	if err != nil {
		return 0, err
	}
	covers, err := s.st.DmsMediaCovers(playback.PlaylistID, mediaIDs(playableItems(wall)))
	if err != nil {
		return 0, err
	}
	if ok && localID != 0 {
		if pl, err := s.st.Playlist(localID); err == nil && pl.PanelCount < panelCount(wall) {
			covers = false
		}
	}
	if !ok || generation != playback.Generation || !covers {
		localID, err = s.materializePlayback(client, code, playback, 0, localID)
		if err != nil {
			return 0, err
		}
		log.Printf("dms playback playlist=%d generation=%d", playback.PlaylistID, playback.Generation)
	}
	if err := s.st.SetPanelCount(localID, panelCount(wall)); err != nil {
		return 0, err
	}
	return localID, nil
}

func (s *Server) activateSchedule(dev *store.Device, plan dms.Schedule, screenRow int) error {
	active := dms.ActivePlaylistID(plan, time.Now())
	if active == 0 {
		return nil
	}
	_, localID, ok, err := s.st.DmsLocalPlaylist(active, 0)
	if err != nil || !ok || localID == 0 {
		return err
	}
	if dev.PlaylistID != nil && *dev.PlaylistID == localID && dev.PanelIndex == screenRow {
		return nil
	}
	if err := RestartSync(s.st, localID); err != nil {
		return err
	}
	return s.st.Assign(dev.ID, localID, screenRow)
}

func (s *Server) followStoredSchedules() {
	rows, err := s.st.DeviceSchedules()
	if err != nil {
		log.Printf("schedule: %v", err)
		return
	}
	for _, row := range rows {
		var plan dms.Schedule
		if err := json.Unmarshal([]byte(row.Body), &plan); err != nil {
			continue
		}
		dev, err := s.st.DeviceByID(row.DeviceID)
		if err != nil || dev == nil {
			continue
		}
		if err := s.activateSchedule(dev, plan, row.ScreenRow); err != nil {
			log.Printf("schedule device=%d: %v", row.DeviceID, err)
		}
	}
}

func mediaIDs(items []dms.PlaybackItem) []int64 {
	ids := make([]int64, 0, len(items))
	for _, item := range items {
		if item.MediaAssetID > 0 {
			ids = append(ids, item.MediaAssetID)
		}
	}
	return ids
}

func rowSource(playback dms.Playback) []dms.PlaybackItem {
	if len(playback.RowItems) > 0 {
		return playback.RowItems
	}
	return playback.Items
}

func wallSource(playback dms.Playback) []dms.PlaybackItem {
	if len(playback.Wall) > 0 {
		return playback.Wall
	}
	return rowSource(playback)
}

func intPtr(v int) *int {
	n := v
	return &n
}

func panelCount(items []dms.PlaybackItem) int {
	count := 1
	for _, item := range items {
		if item.RowIndex+1 > count {
			count = item.RowIndex + 1
		}
	}
	return count
}

func playableItems(items []dms.PlaybackItem) []dms.PlaybackItem {
	out := make([]dms.PlaybackItem, 0, len(items))
	for _, item := range items {
		switch item.Type {
		case "hold", "gap":
			out = append(out, item)
		case "video", "image":
			if item.ID > 0 {
				out = append(out, item)
			}
		}
	}
	return out
}

func (s *Server) materializePlayback(client *dms.Client, code string, playback dms.Playback, rowKey, localID int64) (int64, error) {
	if err := os.MkdirAll(s.cfg.MediaDir(), 0o755); err != nil {
		return 0, err
	}
	if localID == 0 {
		pl, err := s.st.CreatePlaylist(playbackName(playback), "playlist", 1)
		if err != nil {
			return 0, err
		}
		localID = pl.ID
	} else if err := s.st.RenamePlaylist(localID, playbackName(playback)); err != nil {
		return 0, err
	}
	items := make([]store.Item, 0, len(playback.Items))
	files := map[int64]string{}
	order := map[int]int{}
	for _, item := range playableItems(wallSource(playback)) {
		duration := item.DurationMs
		if duration < 1 {
			duration = 1
		}
		row := item.RowIndex
		sort := order[row]
		order[row] = sort + 1
		if item.Type == "hold" || item.Type == "gap" {
			items = append(items, store.Item{
				Type:           item.Type,
				SHA256:         "",
				Filename:       item.Type,
				DurationMs:     duration,
				FileDurationMs: 0,
				Fit:            "cut",
				SortOrder:      sort,
				PanelIndex:     intPtr(row),
			})
			continue
		}
		sum, err := s.savePlaybackFile(client, code, item.ID)
		if err != nil {
			return 0, err
		}
		name := strings.TrimSpace(item.Name)
		if name == "" {
			name = "media"
		}
		kind := "video"
		filename := name + ".mp4"
		fileDur := item.FileDurationMs
		if item.Type == "image" {
			kind = "image"
			filename = name + ".jpg"
			fileDur = duration
		}
		if fileDur < 1 {
			fileDur = duration
		}
		items = append(items, store.Item{
			Type:           kind,
			SHA256:         sum,
			Filename:       filename,
			DurationMs:     duration,
			FileDurationMs: fileDur,
			Fit:            "cut",
			SortOrder:      sort,
			PanelIndex:     intPtr(row),
		})
		if item.MediaAssetID > 0 {
			files[item.MediaAssetID] = sum
		}
	}
	if err := s.st.ReplaceItems(localID, items); err != nil {
		return 0, err
	}
	if err := s.st.ReplaceDmsMedia(playback.PlaylistID, files); err != nil {
		return 0, err
	}
	if err := RestartSync(s.st, localID); err != nil {
		return 0, err
	}
	if err := s.st.SaveDmsPlayback(playback.PlaylistID, rowKey, playback.Generation, localID); err != nil {
		return 0, err
	}
	return localID, nil
}

func (s *Server) savePlaybackFile(client *dms.Client, code string, itemID int64) (string, error) {
	body, err := client.OpenItem(code, itemID)
	if err != nil {
		return "", err
	}
	defer body.Close()
	tmp, err := os.CreateTemp(s.cfg.MediaDir(), "dms-*.mp4")
	if err != nil {
		return "", err
	}
	tmpName := tmp.Name()
	hash := sha256.New()
	if _, err := io.Copy(io.MultiWriter(tmp, hash), body); err != nil {
		tmp.Close()
		os.Remove(tmpName)
		return "", err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmpName)
		return "", err
	}
	sum := hex.EncodeToString(hash.Sum(nil))
	dest := filepath.Join(s.cfg.MediaDir(), sum+".mp4")
	if _, err := os.Stat(dest); err == nil {
		os.Remove(tmpName)
		return sum, nil
	}
	if err := os.Rename(tmpName, dest); err != nil {
		os.Remove(tmpName)
		return "", err
	}
	return sum, nil
}

func playbackName(playback dms.Playback) string {
	name := strings.TrimSpace(playback.PlaylistName)
	if name == "" {
		return "DMS"
	}
	return name
}
