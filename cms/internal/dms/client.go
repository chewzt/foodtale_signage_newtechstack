package dms

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type Client struct {
	Base string
	HTTP *http.Client
}

type View struct {
	Code    string `json:"code"`
	Claimed bool   `json:"claimed"`
	Expired bool   `json:"expired"`
	Revoked bool   `json:"revoked"`
	Message string `json:"message"`
}

type Playback struct {
	Assigned     bool           `json:"assigned"`
	PlaylistID   int64          `json:"playlist_id"`
	PlaylistName string         `json:"playlist_name"`
	ScreenRow    int            `json:"screen_row"`
	MediaAssetID int64          `json:"media_asset_id"`
	Generation   int            `json:"generation"`
	Items        []PlaybackItem `json:"items"`
	RowItems     []PlaybackItem `json:"row_items"`
	Wall         []PlaybackItem `json:"wall"`
	Schedule     *Schedule      `json:"schedule,omitempty"`
}

type PlaybackItem struct {
	ID             int64  `json:"id"`
	MediaAssetID   int64  `json:"media_asset_id"`
	Name           string `json:"name"`
	Type           string `json:"type"`
	DurationMs     int    `json:"duration_ms"`
	FileDurationMs int    `json:"file_duration_ms"`
	RowIndex       int    `json:"row_index"`
}

type ReportEvent struct {
	ID       string         `json:"id"`
	At       any            `json:"at,omitempty"`
	Type     string         `json:"type"`
	Severity string         `json:"severity"`
	Message  string         `json:"message"`
	Meta     map[string]any `json:"meta,omitempty"`
}

type ReportDevice struct {
	Code          string        `json:"code"`
	Playing       bool          `json:"playing"`
	Item          string        `json:"item"`
	SeenSeconds   int           `json:"seen_seconds"`
	Speed         *float64      `json:"speed,omitempty"`
	DriftMs       *int          `json:"drift_ms,omitempty"`
	ClockOffsetMs *int          `json:"clock_offset_ms,omitempty"`
	ClockRttMs    *int          `json:"clock_rtt_ms,omitempty"`
	LeadMs        *int          `json:"lead_ms,omitempty"`
	Phase         string        `json:"phase,omitempty"`
	AppVersion    string        `json:"app_version,omitempty"`
	PlayedMs      int64         `json:"played_ms,omitempty"`
	DriftAbsSum   int64         `json:"drift_abs_sum,omitempty"`
	DriftSamples  int           `json:"drift_samples,omitempty"`
	SpeedNudges   int           `json:"speed_nudges,omitempty"`
	UptimeMs      int64         `json:"uptime_ms,omitempty"`
	Events        []ReportEvent `json:"events,omitempty"`
}

type ReportResult struct {
	Code           string   `json:"code"`
	Revoked        bool     `json:"revoked"`
	IsHead         bool     `json:"is_head"`
	HeadDeviceID   int64    `json:"head_device_id"`
	HeadDeviceName string   `json:"head_device_name"`
	Playback       Playback `json:"playback"`
}

type Brand struct {
	BranchName   string `json:"branch_name"`
	PrimaryColor string `json:"primary_color"`
	LogoVersion  string `json:"logo_version"`
}

type Role struct {
	DeviceID       int64  `json:"device_id"`
	BranchID       int64  `json:"branch_id"`
	IsHead         bool   `json:"is_head"`
	HeadDeviceID   int64  `json:"head_device_id"`
	HeadDeviceName string `json:"head_device_name"`
}

func New(base string) *Client {
	return &Client{
		Base: strings.TrimRight(strings.TrimSpace(base), "/"),
		HTTP: &http.Client{Timeout: 8 * time.Second},
	}
}

func (c *Client) Register(code, role, cmsID string, localID int64) (View, error) {
	body, _ := json.Marshal(map[string]any{
		"code":            code,
		"role":            role,
		"cms_id":          cmsID,
		"local_device_id": localID,
	})
	req, err := http.NewRequest(http.MethodPost, c.Base+"/api/claims", bytes.NewReader(body))
	if err != nil {
		return View{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	return c.do(req)
}

func (c *Client) Get(code string) (View, error) {
	req, err := http.NewRequest(http.MethodGet, c.Base+"/api/claims/"+code, nil)
	if err != nil {
		return View{}, err
	}
	return c.do(req)
}

func (c *Client) Role(code string) (Role, error) {
	req, err := http.NewRequest(http.MethodGet, c.Base+"/api/claims/"+code+"/role", nil)
	if err != nil {
		return Role{}, err
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return Role{}, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode >= 400 {
		return Role{}, fmt.Errorf("dms %d: %s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	var role Role
	if err := json.Unmarshal(raw, &role); err != nil {
		return Role{}, err
	}
	return role, nil
}

func (c *Client) Report(code string, devices []ReportDevice) ([]ReportResult, Brand, error) {
	if devices == nil {
		devices = []ReportDevice{}
	}
	body, err := json.Marshal(map[string]any{"devices": devices})
	if err != nil {
		return nil, Brand{}, err
	}
	req, err := http.NewRequest(http.MethodPost, c.Base+"/api/claims/"+code+"/report", bytes.NewReader(body))
	if err != nil {
		return nil, Brand{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, Brand{}, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode >= 400 {
		return nil, Brand{}, fmt.Errorf("dms %d: %s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	var payload struct {
		Devices []ReportResult `json:"devices"`
		Brand   Brand          `json:"brand"`
	}
	if err := json.Unmarshal(raw, &payload); err != nil {
		return nil, Brand{}, err
	}
	if payload.Devices == nil {
		payload.Devices = []ReportResult{}
	}
	for i := range payload.Devices {
		if payload.Devices[i].Playback.Items == nil {
			payload.Devices[i].Playback.Items = []PlaybackItem{}
		}
	}
	return payload.Devices, payload.Brand, nil
}

func (c *Client) OpenBrandLogo(code string) (io.ReadCloser, error) {
	req, err := http.NewRequest(http.MethodGet, c.Base+"/api/claims/"+code+"/brand/logo", nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode >= 400 {
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		resp.Body.Close()
		return nil, fmt.Errorf("dms %d: %s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	return resp.Body, nil
}

func (c *Client) Playback(code string) (Playback, error) {
	req, err := http.NewRequest(http.MethodGet, c.Base+"/api/claims/"+code+"/playback", nil)
	if err != nil {
		return Playback{}, err
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return Playback{}, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode >= 400 {
		return Playback{}, fmt.Errorf("dms %d: %s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	var playback Playback
	if err := json.Unmarshal(raw, &playback); err != nil {
		return Playback{}, err
	}
	if playback.Items == nil {
		playback.Items = []PlaybackItem{}
	}
	return playback, nil
}

func (c *Client) OpenItem(code string, itemID int64) (io.ReadCloser, error) {
	req, err := http.NewRequest(http.MethodGet, fmt.Sprintf("%s/api/claims/%s/items/%d/file", c.Base, code, itemID), nil)
	if err != nil {
		return nil, err
	}
	client := c.HTTP
	if client == nil || client.Timeout < time.Minute {
		client = &http.Client{Timeout: 2 * time.Minute}
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode >= 400 {
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		resp.Body.Close()
		return nil, fmt.Errorf("dms %d: %s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	return resp.Body, nil
}

func (c *Client) do(req *http.Request) (View, error) {
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return View{}, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	var view View
	_ = json.Unmarshal(raw, &view)
	if resp.StatusCode >= 400 {
		msg := view.Message
		if msg == "" {
			msg = strings.TrimSpace(string(raw))
		}
		if msg == "" {
			msg = resp.Status
		}
		return view, fmt.Errorf("dms %d: %s", resp.StatusCode, msg)
	}
	return view, nil
}
