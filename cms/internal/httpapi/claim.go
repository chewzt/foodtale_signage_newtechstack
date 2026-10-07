package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"time"

	"foodtale/cms/internal/dms"
	"foodtale/cms/internal/store"
)

type claimResponse struct {
	Ready       bool   `json:"ready"`
	Reason      string `json:"reason,omitempty"`
	HeadClaimed bool   `json:"head_claimed"`
	Claimed     bool   `json:"claimed"`
	Code        string `json:"code,omitempty"`
	URL         string `json:"url,omitempty"`
	DeviceToken string `json:"device_token,omitempty"`
	CMSID       string `json:"cms_id,omitempty"`
	DeviceID    int64  `json:"device_id,omitempty"`
	HTTP        string `json:"http,omitempty"`
}

func (s *Server) claimHead(w http.ResponseWriter, r *http.Request) {
	if strings.TrimSpace(s.cfg.DMSPublicURL) == "" {
		writeJSON(w, http.StatusOK, claimResponse{Ready: false, Reason: "dms_url_missing"})
		return
	}
	dev, claim, err := s.ensureClaim("head", "Head", nil)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, s.publishClaim(dev, claim))
}

func (s *Server) adoptClaim(w http.ResponseWriter, r *http.Request) {
	if strings.TrimSpace(s.cfg.DMSPublicURL) == "" {
		http.Error(w, "dms url missing", http.StatusServiceUnavailable)
		return
	}
	code, ok := claimCodeBody(r)
	if !ok {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	role, err := dms.New(s.cfg.DMSPublicURL).Role(code)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	if !role.IsHead || role.HeadDeviceID != role.DeviceID {
		http.Error(w, "not the branch head", http.StatusConflict)
		return
	}
	dev, claim, err := s.ensureClaim("head", "Head", nil)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	claim.Code = code
	claim.Claimed = true
	claim.Role = "head"
	if err := s.st.SaveDmsClaim(*claim); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, s.claimPayload(dev, claim, true))
}

func (s *Server) joinClaim(w http.ResponseWriter, r *http.Request) {
	if strings.TrimSpace(s.cfg.DMSPublicURL) == "" {
		http.Error(w, "dms url missing", http.StatusServiceUnavailable)
		return
	}
	code, ok := claimCodeBody(r)
	if !ok {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	self, err := s.st.DmsClaimByRole("head")
	if err != nil || self == nil || !self.Claimed {
		http.Error(w, "cms is not the branch head", http.StatusConflict)
		return
	}
	client := dms.New(s.cfg.DMSPublicURL)
	selfRole, err := client.Role(self.Code)
	if err != nil || !selfRole.IsHead {
		http.Error(w, "cms is not the branch head", http.StatusConflict)
		return
	}
	guest, err := client.Role(code)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	if guest.BranchID == 0 || guest.BranchID != selfRole.BranchID {
		http.Error(w, "device is not in this branch", http.StatusUnprocessableEntity)
		return
	}
	if guest.DeviceID == selfRole.DeviceID {
		dev, err := s.st.DeviceByID(self.LocalDeviceID)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		writeJSON(w, http.StatusOK, s.claimPayload(dev, self, true))
		return
	}
	if existing, err := s.st.DmsClaimByCode(code); err == nil && existing != nil {
		dev, err := s.st.DeviceByID(existing.LocalDeviceID)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		existing.Claimed = true
		_ = s.st.SaveDmsClaim(*existing)
		writeJSON(w, http.StatusOK, s.claimPayload(dev, existing, true))
		return
	}
	dev, err := s.st.PrepareClaimDevice("Screen")
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	claim := store.DmsClaim{LocalDeviceID: dev.ID, Role: "screen", Code: code, Claimed: true}
	if err := s.st.SaveDmsClaim(claim); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, s.claimPayload(dev, &claim, true))
}

func claimCodeBody(r *http.Request) (string, bool) {
	var body struct {
		Code string `json:"code"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&body); err != nil {
		return "", false
	}
	code := strings.ToUpper(strings.TrimSpace(body.Code))
	if code == "" {
		return "", false
	}
	return code, true
}

func (s *Server) claimScreen(w http.ResponseWriter, r *http.Request) {
	if strings.TrimSpace(s.cfg.DMSPublicURL) == "" {
		writeJSON(w, http.StatusOK, claimResponse{Ready: false, Reason: "dms_url_missing"})
		return
	}
	if !s.headIsClaimed() {
		writeJSON(w, http.StatusOK, claimResponse{Ready: true, HeadClaimed: false})
		return
	}
	var body struct {
		DeviceName  string `json:"device_name"`
		DeviceToken string `json:"device_token"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&body); err != nil && err != io.EOF {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	name := strings.TrimSpace(body.DeviceName)
	if name == "" {
		name = "Screen"
	}
	token := strings.TrimSpace(body.DeviceToken)
	dev, claim, err := s.ensureClaim("screen", name, &token)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, s.publishClaim(dev, claim))
}

func (s *Server) ensureClaim(role, name string, token *string) (*store.Device, *store.DmsClaim, error) {
	if token != nil && *token != "" {
		dev, err := s.st.DeviceByToken(*token)
		if err == nil && dev.DeviceToken != nil {
			claim, err := s.st.DmsClaimByDevice(dev.ID)
			if err != nil {
				return nil, nil, err
			}
			if claim != nil && claim.Role == role {
				return dev, claim, nil
			}
		}
	}
	if role == "head" {
		claim, err := s.st.DmsClaimByRole("head")
		if err != nil {
			return nil, nil, err
		}
		if claim != nil {
			dev, err := s.st.DeviceByID(claim.LocalDeviceID)
			if err != nil {
				return nil, nil, err
			}
			return dev, claim, nil
		}
	}
	dev, err := s.st.PrepareClaimDevice(name)
	if err != nil {
		return nil, nil, err
	}
	code, err := s.st.NewClaimCode()
	if err != nil {
		return nil, nil, err
	}
	claim := &store.DmsClaim{LocalDeviceID: dev.ID, Role: role, Code: code}
	if err := s.st.SaveDmsClaim(*claim); err != nil {
		return nil, nil, err
	}
	return dev, claim, nil
}

func (s *Server) headIsClaimed() bool {
	claim, err := s.st.DmsClaimByRole("head")
	if err != nil || claim == nil {
		return false
	}
	if claim.Claimed {
		return true
	}
	view, err := dms.New(s.cfg.DMSPublicURL).Get(claim.Code)
	if err != nil || !view.Claimed {
		return false
	}
	claim.Claimed = true
	_ = s.st.SaveDmsClaim(*claim)
	return true
}

func (s *Server) publishClaim(dev *store.Device, claim *store.DmsClaim) claimResponse {
	client := dms.New(s.cfg.DMSPublicURL)
	for attempt := 0; attempt < 2; attempt++ {
		view, err := client.Register(claim.Code, claim.Role, s.cfg.CMSID, dev.ID)
		if err != nil && strings.Contains(err.Error(), "Code already used") {
			claim.Claimed = true
			_ = s.st.SaveDmsClaim(*claim)
			return s.claimPayload(dev, claim, true)
		}
		if err != nil && claim.Role == "screen" && strings.Contains(err.Error(), "Scan the head first") {
			return claimResponse{Ready: true, HeadClaimed: false}
		}
		if err != nil {
			return claimResponse{Ready: false, Reason: "dms_unreachable", HeadClaimed: s.localHeadClaimed()}
		}
		if !view.Claimed {
			if fresh, getErr := client.Get(claim.Code); getErr == nil {
				view = fresh
			}
		}
		if view.Claimed {
			claim.Claimed = true
			_ = s.st.SaveDmsClaim(*claim)
			return s.claimPayload(dev, claim, true)
		}
		if view.Expired && attempt == 0 {
			if _, ok := s.rotateClaim(dev, claim); ok {
				continue
			}
		}
		return s.claimPayload(dev, claim, false)
	}
	return s.claimPayload(dev, claim, claim.Claimed)
}

func (s *Server) rotateClaim(dev *store.Device, claim *store.DmsClaim) (*store.DmsClaim, bool) {
	if claim.Claimed {
		return claim, false
	}
	code, err := s.st.NewClaimCode()
	if err != nil {
		return claim, false
	}
	claim.Code = code
	if err := s.st.SaveDmsClaim(*claim); err != nil {
		return claim, false
	}
	return claim, true
}

func (s *Server) claimPayload(dev *store.Device, claim *store.DmsClaim, claimed bool) claimResponse {
	if claimed {
		if fresh, err := s.st.DeviceByID(dev.ID); err == nil && fresh != nil {
			dev = fresh
		}
		if dev.DeviceToken == nil || *dev.DeviceToken == "" {
			if reissued, err := s.st.Pair(dev.PairingCode, dev.Name); err == nil {
				dev = reissued
			}
		}
	}
	token := ""
	if dev.DeviceToken != nil {
		token = *dev.DeviceToken
	}
	out := claimResponse{
		Ready:       true,
		HeadClaimed: true,
		Claimed:     claimed,
		Code:        claim.Code,
		URL:         s.cfg.DMSPublicURL + "/claim/" + claim.Code,
		DeviceToken: token,
		CMSID:       s.cfg.CMSID,
		DeviceID:    dev.ID,
		HTTP:        s.cfg.AdvertiseURL(),
	}
	if claim.Role == "head" {
		out.HeadClaimed = true
	}
	return out
}

func (s *Server) watchRevokes() {
	tick := time.NewTicker(30 * time.Second)
	defer tick.Stop()
	for {
		s.submitReport()
		<-tick.C
	}
}

func (s *Server) pollRevokes() {
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
		view, err := client.Get(claim.Code)
		if err != nil || !view.Revoked {
			continue
		}
		s.revokeLocal(&claim)
	}
}

func (s *Server) revokeLocal(claim *store.DmsClaim) {
	_ = s.st.ClearDeviceToken(claim.LocalDeviceID)
	code, err := s.st.NewClaimCode()
	if err != nil {
		return
	}
	claim.Claimed = false
	claim.Code = code
	_ = s.st.SaveDmsClaim(*claim)
}

func (s *Server) localHeadClaimed() bool {
	claim, err := s.st.DmsClaimByRole("head")
	return err == nil && claim != nil && claim.Claimed
}
