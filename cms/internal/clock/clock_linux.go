//go:build linux

package clock

import "golang.org/x/sys/unix"

func platformNowMs() int64 {
	var ts unix.Timespec
	if err := unix.ClockGettime(unix.CLOCK_BOOTTIME, &ts); err != nil {
		_ = unix.ClockGettime(unix.CLOCK_MONOTONIC, &ts)
	}
	return ts.Sec*1000 + int64(ts.Nsec)/1_000_000
}
