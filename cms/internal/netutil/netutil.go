package netutil

import (
	"context"
	"net"
	"strings"
	"time"
)

func LanIPv4() string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return "127.0.0.1"
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			ip := ipnet.IP.To4()
			if ip == nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
				continue
			}
			return ip.String()
		}
	}
	return "127.0.0.1"
}

func AdvertiseURL(port string) string {
	return "http://" + LanIPv4() + ":" + port
}

// Nameservers parses a comma- or space-separated list of DNS server IPs.
func Nameservers(list string) []string {
	parts := strings.FieldsFunc(list, func(r rune) bool {
		return r == ',' || r == ';' || r == ' ' || r == '\n' || r == '\t'
	})
	out := make([]string, 0, len(parts))
	seen := map[string]bool{}
	for _, part := range parts {
		host := strings.TrimSpace(part)
		host = strings.Trim(host, "[]")
		if i := strings.LastIndex(host, "%"); i > 0 {
			host = host[:i]
		}
		if net.ParseIP(host) == nil || seen[host] {
			continue
		}
		seen[host] = true
		out = append(out, host)
	}
	return out
}

// UseNameservers makes this process resolve through the given DNS servers.
// The Android boxes ship an empty resolv.conf, so the stock Go resolver asks [::1]:53 and fails.
func UseNameservers(list string) {
	servers := Nameservers(list)
	if len(servers) == 0 {
		return
	}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			dialer := net.Dialer{Timeout: 3 * time.Second}
			proto := "udp"
			if strings.HasPrefix(network, "tcp") {
				proto = "tcp"
			}
			var first error
			for _, server := range servers {
				conn, err := dialer.DialContext(ctx, proto, net.JoinHostPort(server, "53"))
				if err == nil {
					return conn, nil
				}
				if first == nil {
					first = err
				}
			}
			return nil, first
		},
	}
}
