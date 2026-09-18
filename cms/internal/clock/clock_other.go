//go:build !linux

package clock

import "time"

var start = time.Now()

func platformNowMs() int64 {
	return time.Since(start).Milliseconds()
}
