package netutil

import (
	"crypto/tls"
	"crypto/x509"
	"net/http"
	"os"
	"path/filepath"
)

// UseSystemCAs loads the Android CA directory into the default HTTP client.
// A CGO-off Go binary does not see the box trust store, so SIT's public certificate fails verification.
func UseSystemCAs() int {
	pool := x509.NewCertPool()
	count := 0
	for _, dir := range []string{
		"/system/etc/security/cacerts",
		"/apex/com.android.conscrypt/cacerts",
	} {
		entries, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, entry := range entries {
			if entry.IsDir() {
				continue
			}
			raw, err := os.ReadFile(filepath.Join(dir, entry.Name()))
			if err != nil {
				continue
			}
			if pool.AppendCertsFromPEM(raw) {
				count++
			}
		}
	}
	if count == 0 {
		return 0
	}
	base, ok := http.DefaultTransport.(*http.Transport)
	if !ok {
		return count
	}
	cloned := base.Clone()
	cloned.TLSClientConfig = &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}
	http.DefaultTransport = cloned
	return count
}
