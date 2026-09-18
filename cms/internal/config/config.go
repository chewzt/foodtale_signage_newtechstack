package config

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"foodtale/cms/internal/netutil"
)

type Config struct {
	CMSID          string
	HTTPPort       int
	ClockPort      int
	BeaconPort     int
	DataDir        string
	AdminPassword  string
	AdvertiseHost  string
}

func Load() (*Config, error) {
	cfg := &Config{
		CMSID:         env("CMS_ID", ""),
		HTTPPort:      envInt("HTTP_PORT", 8080),
		ClockPort:     envInt("CLOCK_PORT", 8123),
		BeaconPort:    envInt("BEACON_PORT", 48720),
		DataDir:       env("DATA_DIR", defaultDataDir()),
		AdminPassword: env("ADMIN_PASSWORD", ""),
		AdvertiseHost: env("HTTP_ADVERTISE", ""),
	}
	if err := os.MkdirAll(cfg.MediaDir(), 0o755); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(cfg.PublicDir(), 0o755); err != nil {
		return nil, err
	}
	if cfg.CMSID == "" {
		idPath := filepath.Join(cfg.DataDir, "cms.id")
		if raw, err := os.ReadFile(idPath); err == nil && strings.TrimSpace(string(raw)) != "" {
			cfg.CMSID = strings.TrimSpace(string(raw))
		} else {
			id, err := randomID()
			if err != nil {
				return nil, err
			}
			cfg.CMSID = id
			_ = os.WriteFile(idPath, []byte(id+"\n"), 0o644)
		}
	}
	return cfg, nil
}

func (c *Config) DBPath() string {
	return filepath.Join(c.DataDir, "cms.db")
}

func (c *Config) MediaDir() string {
	return filepath.Join(c.DataDir, "media")
}

func (c *Config) PublicDir() string {
	return filepath.Join(c.DataDir, "public")
}

func (c *Config) APKPath() string {
	return filepath.Join(c.PublicDir(), "foodtale-player.apk")
}

func (c *Config) APKMetaPath() string {
	return filepath.Join(c.PublicDir(), "foodtale-player.json")
}

func (c *Config) HTTPAddr() string {
	return fmt.Sprintf(":%d", c.HTTPPort)
}

func (c *Config) AdvertiseURL() string {
	if strings.TrimSpace(c.AdvertiseHost) != "" {
		return strings.TrimRight(c.AdvertiseHost, "/")
	}
	return netutil.AdvertiseURL(strconv.Itoa(c.HTTPPort))
}

func defaultDataDir() string {
	if home, err := os.UserHomeDir(); err == nil {
		return filepath.Join(home, ".foodtale-cms")
	}
	return "./data"
}

func env(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

func envInt(key string, fallback int) int {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return fallback
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return fallback
	}
	return n
}

func randomID() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}
