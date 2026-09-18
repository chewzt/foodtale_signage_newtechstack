package beacon

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"strconv"
	"time"

	"foodtale/cms/internal/config"
	"foodtale/cms/internal/netutil"
)

type Packet struct {
	V         int    `json:"v"`
	Service   string `json:"service"`
	CMSID     string `json:"cmsId"`
	HTTP      string `json:"http"`
	ClockPort int    `json:"clockPort"`
}

func Run(cfg *config.Config) error {
	conn, err := net.DialUDP("udp4", nil, &net.UDPAddr{
		IP:   net.IPv4bcast,
		Port: cfg.BeaconPort,
	})
	if err != nil {
		return err
	}
	defer conn.Close()
	log.Printf("cms beacon udp 255.255.255.255:%d", cfg.BeaconPort)
	t := time.NewTicker(time.Second)
	defer t.Stop()
	for range t.C {
		httpURL := cfg.AdvertiseURL()
		if cfg.AdvertiseHost == "" {
			httpURL = netutil.AdvertiseURL(strconv.Itoa(cfg.HTTPPort))
		}
		pkt := Packet{
			V:         1,
			Service:   "foodtale-cms",
			CMSID:     cfg.CMSID,
			HTTP:      httpURL,
			ClockPort: cfg.ClockPort,
		}
		body, err := json.Marshal(pkt)
		if err != nil {
			continue
		}
		if _, err := conn.Write(body); err != nil {
			log.Printf("beacon write: %v", err)
		}
	}
	return fmt.Errorf("beacon stopped")
}
