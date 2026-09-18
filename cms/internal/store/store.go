package store

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"fmt"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

type Store struct {
	db *sql.DB
}

type Playlist struct {
	ID             int64
	Name           string
	Kind           string
	PanelCount     int
	StartAt        string
	StartMasterMs  int64
	SyncGeneration int64
	CreatedAt      string
	Items          []Item
}

type Item struct {
	ID             int64
	PlaylistID     int64
	Type           string
	SHA256         string
	Filename       string
	DurationMs     int
	FileDurationMs int
	Fit            string
	SortOrder      int
	PanelIndex     *int
}

type Device struct {
	ID            int64
	Name          string
	PairingCode   string
	DeviceToken   *string
	PlaylistID    *int64
	PanelIndex    int
	LastSeenAt    *string
	Status        string
	ClockOffsetMs *int
	ClockRTTMs    *int
	PlayIndex     *int
	PlayItemID    *int64
	PlayLagMs     *int
	PlayPlaying   bool
	CreatedAt     string
	Playlist      *Playlist
}

type Peer struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
	IP   string `json:"ip"`
}

func Open(path string) (*Store, error) {
	dsn := path + "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(4)
	s := &Store{db: db}
	if err := s.migrate(); err != nil {
		_ = db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error {
	return s.db.Close()
}

func (s *Store) migrate() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS playlists (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  kind TEXT NOT NULL DEFAULT 'playlist',
  panel_count INTEGER NOT NULL DEFAULT 1,
  start_at TEXT NOT NULL,
  start_master_ms INTEGER NOT NULL DEFAULT 0,
  sync_generation INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS items (
  id INTEGER PRIMARY KEY,
  playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
  type TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  filename TEXT NOT NULL,
  duration_ms INTEGER NOT NULL,
  file_duration_ms INTEGER NOT NULL DEFAULT 0,
  fit TEXT NOT NULL DEFAULT 'cut',
  sort_order INTEGER NOT NULL DEFAULT 0,
  panel_index INTEGER,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL DEFAULT 'Unpaired',
  pairing_code TEXT NOT NULL UNIQUE,
  device_token TEXT UNIQUE,
  playlist_id INTEGER REFERENCES playlists(id) ON DELETE SET NULL,
  panel_index INTEGER NOT NULL DEFAULT 0,
  last_seen_at TEXT,
  status TEXT NOT NULL DEFAULT 'pending',
  clock_offset_ms INTEGER,
  clock_rtt_ms INTEGER,
  play_index INTEGER,
  play_item_id INTEGER,
  play_lag_ms INTEGER,
  play_playing INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS items_playlist ON items(playlist_id, sort_order);
`)
	if err != nil {
		return err
	}
	_, _ = s.db.Exec(`ALTER TABLE devices ADD COLUMN last_ip TEXT NOT NULL DEFAULT ''`)
	return nil
}

func nowRFC() string {
	return time.Now().UTC().Format(time.RFC3339Nano)
}

func (s *Store) CreatePlaylist(name, kind string, panelCount int) (*Playlist, error) {
	if panelCount < 1 {
		panelCount = 1
	}
	res, err := s.db.Exec(
		`INSERT INTO playlists(name, kind, panel_count, start_at, start_master_ms, sync_generation, created_at)
		 VALUES(?,?,?,?,0,0,?)`,
		name, kind, panelCount, nowRFC(), nowRFC(),
	)
	if err != nil {
		return nil, err
	}
	id, _ := res.LastInsertId()
	return s.Playlist(id)
}

func (s *Store) Playlist(id int64) (*Playlist, error) {
	p := &Playlist{}
	err := s.db.QueryRow(
		`SELECT id, name, kind, panel_count, start_at, start_master_ms, sync_generation, created_at
		 FROM playlists WHERE id=?`, id,
	).Scan(&p.ID, &p.Name, &p.Kind, &p.PanelCount, &p.StartAt, &p.StartMasterMs, &p.SyncGeneration, &p.CreatedAt)
	if err != nil {
		return nil, err
	}
	items, err := s.Items(id)
	if err != nil {
		return nil, err
	}
	p.Items = items
	return p, nil
}

func (s *Store) PlaylistsByKind(kind string) ([]Playlist, error) {
	rows, err := s.db.Query(
		`SELECT id, name, kind, panel_count, start_at, start_master_ms, sync_generation, created_at
		 FROM playlists WHERE kind=? ORDER BY id DESC`, kind,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Playlist
	for rows.Next() {
		var p Playlist
		if err := rows.Scan(&p.ID, &p.Name, &p.Kind, &p.PanelCount, &p.StartAt, &p.StartMasterMs, &p.SyncGeneration, &p.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}
	for i := range out {
		items, err := s.Items(out[i].ID)
		if err != nil {
			return nil, err
		}
		out[i].Items = items
	}
	if out == nil {
		out = []Playlist{}
	}
	return out, rows.Err()
}

func (s *Store) AllPlaylists() ([]Playlist, error) {
	rows, err := s.db.Query(
		`SELECT id, name, kind, panel_count, start_at, start_master_ms, sync_generation, created_at
		 FROM playlists ORDER BY kind, id DESC`,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Playlist
	for rows.Next() {
		var p Playlist
		if err := rows.Scan(&p.ID, &p.Name, &p.Kind, &p.PanelCount, &p.StartAt, &p.StartMasterMs, &p.SyncGeneration, &p.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	if out == nil {
		out = []Playlist{}
	}
	return out, rows.Err()
}

func (s *Store) DeletePlaylist(id int64) error {
	_, err := s.db.Exec(`DELETE FROM playlists WHERE id=?`, id)
	return err
}

func (s *Store) RestartSync(id int64, startAt string, startMasterMs int64) error {
	_, err := s.db.Exec(
		`UPDATE playlists SET start_at=?, start_master_ms=?, sync_generation=sync_generation+1 WHERE id=?`,
		startAt, startMasterMs, id,
	)
	return err
}

func (s *Store) Items(playlistID int64) ([]Item, error) {
	rows, err := s.db.Query(
		`SELECT id, playlist_id, type, sha256, filename, duration_ms, file_duration_ms, fit, sort_order, panel_index
		 FROM items WHERE playlist_id=? ORDER BY panel_index IS NULL, panel_index, sort_order, id`, playlistID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Item
	for rows.Next() {
		var it Item
		var panel sql.NullInt64
		if err := rows.Scan(&it.ID, &it.PlaylistID, &it.Type, &it.SHA256, &it.Filename, &it.DurationMs, &it.FileDurationMs, &it.Fit, &it.SortOrder, &panel); err != nil {
			return nil, err
		}
		if panel.Valid {
			v := int(panel.Int64)
			it.PanelIndex = &v
		}
		out = append(out, it)
	}
	if out == nil {
		out = []Item{}
	}
	return out, rows.Err()
}

func (s *Store) AddItem(it Item) (int64, error) {
	var panel any
	if it.PanelIndex != nil {
		panel = *it.PanelIndex
	}
	res, err := s.db.Exec(
		`INSERT INTO items(playlist_id, type, sha256, filename, duration_ms, file_duration_ms, fit, sort_order, panel_index, created_at)
		 VALUES(?,?,?,?,?,?,?,?,?,?)`,
		it.PlaylistID, it.Type, it.SHA256, it.Filename, it.DurationMs, it.FileDurationMs, it.Fit, it.SortOrder, panel, nowRFC(),
	)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

func (s *Store) DeleteItem(id int64) error {
	_, err := s.db.Exec(`DELETE FROM items WHERE id=?`, id)
	return err
}

func (s *Store) MoveItem(id int64, delta int) error {
	var playlistID int64
	var sort int
	var panel sql.NullInt64
	if err := s.db.QueryRow(`SELECT playlist_id, sort_order, panel_index FROM items WHERE id=?`, id).Scan(&playlistID, &sort, &panel); err != nil {
		return err
	}
	q := `SELECT id, sort_order FROM items WHERE playlist_id=? AND id!=? `
	args := []any{playlistID, id}
	if panel.Valid {
		q += `AND panel_index=? `
		args = append(args, panel.Int64)
	} else {
		q += `AND panel_index IS NULL `
	}
	if delta < 0 {
		q += `AND sort_order < ? ORDER BY sort_order DESC LIMIT 1`
	} else {
		q += `AND sort_order > ? ORDER BY sort_order ASC LIMIT 1`
	}
	args = append(args, sort)
	var otherID int64
	var otherSort int
	if err := s.db.QueryRow(q, args...).Scan(&otherID, &otherSort); err != nil {
		if err == sql.ErrNoRows {
			return nil
		}
		return err
	}
	if _, err := s.db.Exec(`UPDATE items SET sort_order=? WHERE id=?`, otherSort, id); err != nil {
		return err
	}
	_, err := s.db.Exec(`UPDATE items SET sort_order=? WHERE id=?`, sort, otherID)
	return err
}

func (s *Store) NextSort(playlistID int64, panel *int) (int, error) {
	var n int
	if panel == nil {
		err := s.db.QueryRow(`SELECT COALESCE(MAX(sort_order),-1)+1 FROM items WHERE playlist_id=? AND panel_index IS NULL`, playlistID).Scan(&n)
		return n, err
	}
	err := s.db.QueryRow(`SELECT COALESCE(MAX(sort_order),-1)+1 FROM items WHERE playlist_id=? AND panel_index=?`, playlistID, *panel).Scan(&n)
	return n, err
}

func (s *Store) CreatePairingCode() (*Device, error) {
	code, err := pairingCode()
	if err != nil {
		return nil, err
	}
	res, err := s.db.Exec(
		`INSERT INTO devices(name, pairing_code, status, created_at) VALUES('Unpaired', ?, 'pending', ?)`,
		code, nowRFC(),
	)
	if err != nil {
		return nil, err
	}
	id, _ := res.LastInsertId()
	return s.DeviceByID(id)
}

func (s *Store) DeviceByID(id int64) (*Device, error) {
	return s.scanDevice(s.db.QueryRow(deviceSelect+` WHERE d.id=?`, id))
}

func (s *Store) DeviceByToken(token string) (*Device, error) {
	return s.scanDevice(s.db.QueryRow(deviceSelect+` WHERE d.device_token=?`, token))
}

func (s *Store) DeviceByCode(code string) (*Device, error) {
	return s.scanDevice(s.db.QueryRow(deviceSelect+` WHERE d.pairing_code=?`, strings.ToUpper(strings.TrimSpace(code))))
}

const deviceSelect = `SELECT d.id, d.name, d.pairing_code, d.device_token, d.playlist_id, d.panel_index,
 d.last_seen_at, d.status, d.clock_offset_ms, d.clock_rtt_ms, d.play_index, d.play_item_id, d.play_lag_ms, d.play_playing, d.created_at,
 p.id, p.name, p.kind, p.panel_count
 FROM devices d LEFT JOIN playlists p ON p.id = d.playlist_id`

func (s *Store) scanDevice(row *sql.Row) (*Device, error) {
	d := &Device{}
	var token, seen sql.NullString
	var playlistID sql.NullInt64
	var off, rtt, playIndex, playLag sql.NullInt64
	var playItem sql.NullInt64
	var playing int
	var pid sql.NullInt64
	var pname, pkind sql.NullString
	var pcount sql.NullInt64
	err := row.Scan(
		&d.ID, &d.Name, &d.PairingCode, &token, &playlistID, &d.PanelIndex,
		&seen, &d.Status, &off, &rtt, &playIndex, &playItem, &playLag, &playing, &d.CreatedAt,
		&pid, &pname, &pkind, &pcount,
	)
	if err != nil {
		return nil, err
	}
	if token.Valid {
		d.DeviceToken = &token.String
	}
	if playlistID.Valid {
		id := playlistID.Int64
		d.PlaylistID = &id
	}
	if seen.Valid {
		d.LastSeenAt = &seen.String
	}
	if off.Valid {
		v := int(off.Int64)
		d.ClockOffsetMs = &v
	}
	if rtt.Valid {
		v := int(rtt.Int64)
		d.ClockRTTMs = &v
	}
	if playIndex.Valid {
		v := int(playIndex.Int64)
		d.PlayIndex = &v
	}
	if playItem.Valid {
		d.PlayItemID = &playItem.Int64
	}
	if playLag.Valid {
		v := int(playLag.Int64)
		d.PlayLagMs = &v
	}
	d.PlayPlaying = playing != 0
	if pid.Valid {
		d.Playlist = &Playlist{
			ID:         pid.Int64,
			Name:       pname.String,
			Kind:       pkind.String,
			PanelCount: int(pcount.Int64),
		}
	}
	return d, nil
}

func (s *Store) Devices() ([]Device, error) {
	rows, err := s.db.Query(deviceSelect + ` ORDER BY d.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Device
	for rows.Next() {
		// QueryRow scanner expects a single row; scan via columns again.
		d, err := scanDeviceRow(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *d)
	}
	if out == nil {
		out = []Device{}
	}
	return out, rows.Err()
}

func scanDeviceRow(rows *sql.Rows) (*Device, error) {
	d := &Device{}
	var token, seen sql.NullString
	var playlistID sql.NullInt64
	var off, rtt, playIndex, playLag sql.NullInt64
	var playItem sql.NullInt64
	var playing int
	var pid sql.NullInt64
	var pname, pkind sql.NullString
	var pcount sql.NullInt64
	err := rows.Scan(
		&d.ID, &d.Name, &d.PairingCode, &token, &playlistID, &d.PanelIndex,
		&seen, &d.Status, &off, &rtt, &playIndex, &playItem, &playLag, &playing, &d.CreatedAt,
		&pid, &pname, &pkind, &pcount,
	)
	if err != nil {
		return nil, err
	}
	if token.Valid {
		d.DeviceToken = &token.String
	}
	if playlistID.Valid {
		id := playlistID.Int64
		d.PlaylistID = &id
	}
	if seen.Valid {
		d.LastSeenAt = &seen.String
	}
	if off.Valid {
		v := int(off.Int64)
		d.ClockOffsetMs = &v
	}
	if rtt.Valid {
		v := int(rtt.Int64)
		d.ClockRTTMs = &v
	}
	if playIndex.Valid {
		v := int(playIndex.Int64)
		d.PlayIndex = &v
	}
	if playItem.Valid {
		d.PlayItemID = &playItem.Int64
	}
	if playLag.Valid {
		v := int(playLag.Int64)
		d.PlayLagMs = &v
	}
	d.PlayPlaying = playing != 0
	if pid.Valid {
		d.Playlist = &Playlist{
			ID:         pid.Int64,
			Name:       pname.String,
			Kind:       pkind.String,
			PanelCount: int(pcount.Int64),
		}
	}
	return d, nil
}

func (s *Store) Pair(code, name string) (*Device, error) {
	d, err := s.DeviceByCode(code)
	if err != nil {
		return nil, err
	}
	if d.DeviceToken != nil && *d.DeviceToken != "" {
		return nil, fmt.Errorf("code already used")
	}
	tok, err := randomToken()
	if err != nil {
		return nil, err
	}
	if name == "" {
		name = "Device"
	}
	_, err = s.db.Exec(
		`UPDATE devices SET name=?, device_token=?, status='online', last_seen_at=? WHERE id=?`,
		name, tok, nowRFC(), d.ID,
	)
	if err != nil {
		return nil, err
	}
	return s.DeviceByID(d.ID)
}

func (s *Store) Assign(deviceID, playlistID int64, panelIndex int) error {
	_, err := s.db.Exec(`UPDATE devices SET playlist_id=?, panel_index=? WHERE id=?`, playlistID, panelIndex, deviceID)
	return err
}

func (s *Store) Unassign(deviceID int64) error {
	_, err := s.db.Exec(`UPDATE devices SET playlist_id=NULL, panel_index=0 WHERE id=?`, deviceID)
	return err
}

func (s *Store) ResetDevice(deviceID int64) error {
	code, err := pairingCode()
	if err != nil {
		return err
	}
	_, err = s.db.Exec(
		`UPDATE devices SET device_token=NULL, name='Unpaired', pairing_code=?, status='pending', last_seen_at=NULL WHERE id=?`,
		code, deviceID,
	)
	return err
}

func (s *Store) DeleteDevice(id int64) error {
	_, err := s.db.Exec(`DELETE FROM devices WHERE id=?`, id)
	return err
}

func (s *Store) Touch(deviceID int64, offsetMs, rttMs *int, playIndex *int, playItem *int64, lag *int, playing *bool, lastIP string) error {
	seen := nowRFC()
	if offsetMs != nil {
		_, err := s.db.Exec(
			`UPDATE devices SET last_seen_at=?, status='online', clock_offset_ms=?, clock_rtt_ms=? WHERE id=?`,
			seen, *offsetMs, valueOr(rttMs, 0), deviceID,
		)
		if err != nil {
			return err
		}
	} else {
		if _, err := s.db.Exec(`UPDATE devices SET last_seen_at=?, status='online' WHERE id=?`, seen, deviceID); err != nil {
			return err
		}
	}
	if lastIP != "" {
		if _, err := s.db.Exec(`UPDATE devices SET last_ip=? WHERE id=?`, lastIP, deviceID); err != nil {
			return err
		}
	}
	if playIndex != nil || playItem != nil || playing != nil {
		var p int
		if playing != nil && *playing {
			p = 1
		}
		_, err := s.db.Exec(
			`UPDATE devices SET play_index=?, play_item_id=?, play_lag_ms=?, play_playing=? WHERE id=?`,
			playIndex, playItem, lag, p, deviceID,
		)
		return err
	}
	return nil
}

func (s *Store) Peers(playlistID, selfID int64) ([]Peer, error) {
	rows, err := s.db.Query(
		`SELECT id, name, IFNULL(last_ip,'') FROM devices WHERE playlist_id=? AND device_token IS NOT NULL ORDER BY id`, playlistID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Peer
	for rows.Next() {
		var p Peer
		if err := rows.Scan(&p.ID, &p.Name, &p.IP); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	if len(out) == 0 {
		out = []Peer{{ID: selfID, Name: "self"}}
	}
	return out, rows.Err()
}

func valueOr(p *int, fallback int) int {
	if p == nil {
		return fallback
	}
	return *p
}

func pairingCode() (string, error) {
	const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	b := make([]byte, 6)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	out := make([]byte, 6)
	for i := range b {
		out[i] = alphabet[int(b[i])%len(alphabet)]
	}
	return string(out), nil
}

func randomToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func (p Playlist) IsCarousel() bool {
	return p.Kind == "carousel"
}
