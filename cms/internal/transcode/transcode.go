package transcode

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

type Result struct {
	SHA256         string
	Path           string
	DurationMs     int
	FileDurationMs int
}

func SaveVideo(src, mediaDir string) (*Result, error) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		return nil, fmt.Errorf("ffmpeg is not installed")
	}
	tmp, err := os.CreateTemp(mediaDir, "upload-*.mp4")
	if err != nil {
		return nil, err
	}
	tmp.Close()
	defer os.Remove(tmp.Name())

	args := []string{
		"-y", "-i", src,
		"-vf", "scale='min(1920,iw)':'min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",
		"-c:v", "libx264", "-profile:v", "baseline", "-level", "4.0",
		"-pix_fmt", "yuv420p", "-preset", "veryfast", "-crf", "23",
		"-g", "15", "-keyint_min", "15", "-bf", "0",
		"-x264-params", "keyint=15:min-keyint=15:scenecut=0",
		"-c:a", "aac", "-ac", "2", "-b:a", "128k",
		"-movflags", "+faststart", "-brand", "mp42",
		tmp.Name(),
	}
	cmd := exec.Command("ffmpeg", args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("ffmpeg: %v\n%s", err, truncate(string(out), 800))
	}
	sum, err := hashFile(tmp.Name())
	if err != nil {
		return nil, err
	}
	dest := filepath.Join(mediaDir, sum+".mp4")
	if err := os.Rename(tmp.Name(), dest); err != nil {
		if copyErr := copyFile(tmp.Name(), dest); copyErr != nil {
			return nil, copyErr
		}
		_ = os.Remove(tmp.Name())
	}
	ms, err := probeDurationMs(dest)
	if err != nil {
		ms = 10000
	}
	return &Result{SHA256: sum, Path: dest, DurationMs: ms, FileDurationMs: ms}, nil
}

func probeDurationMs(path string) (int, error) {
	cmd := exec.Command("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", path)
	out, err := cmd.Output()
	if err != nil {
		return 0, err
	}
	sec, err := strconv.ParseFloat(strings.TrimSpace(string(out)), 64)
	if err != nil {
		return 0, err
	}
	return int(sec * 1000), nil
}

func hashFile(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()
	if _, err := io.Copy(out, in); err != nil {
		return err
	}
	return out.Close()
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}
