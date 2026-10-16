package dms

import (
	"strconv"
	"time"
	_ "time/tzdata"
)

type Schedule struct {
	ID                 int64               `json:"id"`
	Timezone           string              `json:"timezone"`
	FallbackPlaylistID int64               `json:"fallback_playlist_id"`
	Slots              []ScheduleSlot      `json:"slots"`
	Playlists          map[string]Playback `json:"playlists"`
}

type ScheduleSlot struct {
	Weekday    int    `json:"weekday"`
	Start      string `json:"start"`
	End        string `json:"end"`
	PlaylistID int64  `json:"playlist_id"`
}

func ActivePlaylistID(plan Schedule, now time.Time) int64 {
	loc, err := time.LoadLocation(plan.Timezone)
	if err != nil || loc == nil {
		loc = time.FixedZone("UTC+8", 8*60*60)
	}
	local := now.In(loc)
	weekday := isoWeekday(local)
	minute := local.Hour()*60 + local.Minute()
	yesterday := weekday - 1
	if yesterday < 1 {
		yesterday = 7
	}
	for _, slot := range plan.Slots {
		start := clockMinutes(slot.Start)
		end := clockMinutes(slot.End)
		if end > start {
			if slot.Weekday == weekday && minute >= start && minute < end {
				return slot.PlaylistID
			}
			continue
		}
		if slot.Weekday == weekday && minute >= start {
			return slot.PlaylistID
		}
		if slot.Weekday == yesterday && minute < end {
			return slot.PlaylistID
		}
	}
	return plan.FallbackPlaylistID
}

func isoWeekday(t time.Time) int {
	day := int(t.Weekday())
	if day == 0 {
		return 7
	}
	return day
}

func clockMinutes(value string) int {
	if len(value) < 5 {
		return 0
	}
	hour, errH := strconv.Atoi(value[0:2])
	minute, errM := strconv.Atoi(value[3:5])
	if errH != nil || errM != nil {
		return 0
	}
	return hour*60 + minute
}
