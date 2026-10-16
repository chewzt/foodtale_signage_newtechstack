package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"

	"foodtale/cms/internal/dms"
)

func (s *Server) applyBrand(client *dms.Client, code string, brand dms.Brand) {
	if brand.BranchName == "" && brand.PrimaryColor == "" && brand.LogoVersion == "" {
		return
	}
	if brand.BranchName != "" {
		s.brandBranch.Store(brand.BranchName)
	}
	if brand.PrimaryColor != "" {
		s.brandColor.Store(brand.PrimaryColor)
	}
	prev, _ := s.brandLogoVer.Load().(string)
	s.brandLogoVer.Store(brand.LogoVersion)
	s.saveBrand(brand)
	path := s.brandLogoPath()
	if brand.LogoVersion == "" {
		_ = os.Remove(path)
		return
	}
	if brand.LogoVersion == prev && fileExists(path) {
		return
	}
	body, err := client.OpenBrandLogo(code)
	if err != nil {
		return
	}
	defer body.Close()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return
	}
	tmp := path + ".tmp"
	f, err := os.Create(tmp)
	if err != nil {
		return
	}
	_, copyErr := io.Copy(f, io.LimitReader(body, 8<<20))
	closeErr := f.Close()
	if copyErr != nil || closeErr != nil {
		_ = os.Remove(tmp)
		return
	}
	_ = os.Rename(tmp, path)
}

func (s *Server) loadBrand() {
	s.brandBranch.Store("")
	s.brandColor.Store("#2C3E50")
	s.brandLogoVer.Store("")
	raw, err := os.ReadFile(s.brandMetaPath())
	if err != nil {
		return
	}
	var brand dms.Brand
	if json.Unmarshal(raw, &brand) != nil {
		return
	}
	if brand.BranchName != "" {
		s.brandBranch.Store(brand.BranchName)
	}
	if brand.PrimaryColor != "" {
		s.brandColor.Store(brand.PrimaryColor)
	}
	s.brandLogoVer.Store(brand.LogoVersion)
}

func (s *Server) saveBrand(brand dms.Brand) {
	raw, err := json.Marshal(brand)
	if err != nil {
		return
	}
	_ = os.MkdirAll(s.cfg.DataDir, 0o755)
	_ = os.WriteFile(s.brandMetaPath(), raw, 0o644)
}

func (s *Server) brandMetaPath() string {
	return filepath.Join(s.cfg.DataDir, "brand.json")
}

func (s *Server) brandLogoPath() string {
	return filepath.Join(s.cfg.DataDir, "brand-logo")
}

func (s *Server) brandTriple() (string, string, string) {
	branch, _ := s.brandBranch.Load().(string)
	color, _ := s.brandColor.Load().(string)
	if color == "" {
		color = "#2C3E50"
	}
	logo := ""
	if fileExists(s.brandLogoPath()) {
		logo = "/brand/logo"
	}
	return branch, color, logo
}

func (s *Server) brandLogo(w http.ResponseWriter, r *http.Request) {
	path := s.brandLogoPath()
	if !fileExists(path) {
		http.NotFound(w, r)
		return
	}
	http.ServeFile(w, r, path)
}
