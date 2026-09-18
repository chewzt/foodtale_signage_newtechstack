package main

import (
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"foodtale/cms/internal/beacon"
	"foodtale/cms/internal/config"
	"foodtale/cms/internal/httpapi"
	"foodtale/cms/internal/sntp"
	"foodtale/cms/internal/store"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatal(err)
	}
	st, err := store.Open(cfg.DBPath())
	if err != nil {
		log.Fatal(err)
	}
	defer st.Close()

	log.Printf("foodtale cms id=%s data=%s", cfg.CMSID, cfg.DataDir)
	log.Printf("admin %s  clock udp :%d  beacon udp :%d", cfg.AdvertiseURL(), cfg.ClockPort, cfg.BeaconPort)
	go func() {
		last := ""
		for {
			url := cfg.AdvertiseURL()
			if url != last {
				log.Printf("LAN URL %s", url)
				last = url
			}
			time.Sleep(15 * time.Second)
		}
	}()

	go func() {
		if err := sntp.Listen(cfg.ClockPort); err != nil {
			log.Fatal(err)
		}
	}()
	go func() {
		if err := beacon.Run(cfg); err != nil {
			log.Fatal(err)
		}
	}()

	go func() {
		ch := make(chan os.Signal, 1)
		signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
		<-ch
		os.Exit(0)
	}()

	if err := httpapi.Listen(cfg, st); err != nil {
		log.Fatal(err)
	}
}
