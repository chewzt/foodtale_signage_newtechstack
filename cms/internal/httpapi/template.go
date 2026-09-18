package httpapi

import (
	"embed"
	"html/template"

	"foodtale/cms/internal/store"
)

//go:embed embed/admin.html
var adminFS embed.FS

type templateEngine struct {
	admin *template.Template
}

type adminData struct {
	CMSID        string
	ServerURL    string
	APKURL       string
	APKAvailable bool
	APKVersion   string
	Flash        string
	Error        string
	Devices      []adminDevice
	Walls        []store.Playlist
	Playlists    []store.Playlist
	Targets      []store.Playlist
	Now          string
}

func newTemplate() *templateEngine {
	funcMap := template.FuncMap{
		"add": func(a, b int) int { return a + b },
		"seq": func(n int) []int {
			out := make([]int, n)
			for i := 0; i < n; i++ {
				out[i] = i
			}
			return out
		},
		"itemURL": func(server, sha string) string {
			return server + "/media/" + sha
		},
		"derefInt": func(p *int) int {
			if p == nil {
				return 0
			}
			return *p
		},
		"playlistID": func(d adminDevice) int64 {
			if d.PlaylistID == nil {
				return 0
			}
			return *d.PlaylistID
		},
		"eqInt64": func(a, b int64) bool { return a == b },
		"eqInt":   func(a, b int) bool { return a == b },
		"sel": func(on bool) string {
			if on {
				return "selected"
			}
			return ""
		},
		"panelItems": func(p store.Playlist, panel int) []store.Item {
			var out []store.Item
			for _, it := range p.Items {
				if it.PanelIndex != nil && *it.PanelIndex == panel {
					out = append(out, it)
				}
			}
			return out
		},
		"isCarousel": func(d adminDevice) bool {
			return d.Playlist != nil && d.Playlist.IsCarousel()
		},
		"panelCount": func(d adminDevice) int {
			if d.Playlist == nil {
				return 1
			}
			return d.Playlist.PanelCount
		},
	}
	t := template.Must(template.New("admin.html").Funcs(funcMap).ParseFS(adminFS, "embed/admin.html"))
	return &templateEngine{admin: t}
}
