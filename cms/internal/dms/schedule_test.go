package dms

import "testing"
import "time"

func TestActivePlaylistID(t *testing.T) {
	plan := Schedule{
		Timezone:           "Asia/Kuala_Lumpur",
		FallbackPlaylistID: 1,
		Slots: []ScheduleSlot{
			{Weekday: 1, Start: "09:00", End: "11:00", PlaylistID: 2},
			{Weekday: 1, Start: "11:00", End: "14:00", PlaylistID: 3},
			{Weekday: 6, Start: "22:00", End: "02:00", PlaylistID: 4},
		},
	}
	cases := []struct {
		at   string
		want int64
	}{
		{"2026-10-19T09:30:00+08:00", 2},
		{"2026-10-19T11:00:00+08:00", 3},
		{"2026-10-19T14:00:00+08:00", 1},
		{"2026-10-18T01:00:00+08:00", 4},
		{"2026-10-18T02:00:00+08:00", 1},
		{"2026-10-17T23:00:00+08:00", 4},
	}
	for _, tc := range cases {
		at, err := time.Parse(time.RFC3339, tc.at)
		if err != nil {
			t.Fatal(err)
		}
		if got := ActivePlaylistID(plan, at); got != tc.want {
			t.Fatalf("%s: got %d want %d", tc.at, got, tc.want)
		}
	}
}
