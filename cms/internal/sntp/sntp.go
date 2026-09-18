package sntp

import (
	"encoding/json"
	"fmt"
	"log"
	"net"

	"foodtale/cms/internal/clock"
)

type request struct {
	V  int   `json:"v"`
	T1 int64 `json:"t1"`
}

type reply struct {
	V       int   `json:"v"`
	T1      int64 `json:"t1"`
	TMaster int64 `json:"tMaster"`
	T2Send  int64 `json:"t2send"`
}

func Listen(port int) error {
	addr, err := net.ResolveUDPAddr("udp4", fmt.Sprintf(":%d", port))
	if err != nil {
		return err
	}
	conn, err := net.ListenUDP("udp4", addr)
	if err != nil {
		return err
	}
	log.Printf("cristian clock udp :%d", port)
	buf := make([]byte, 2048)
	for {
		n, remote, err := conn.ReadFromUDP(buf)
		if err != nil {
			log.Printf("clock read: %v", err)
			continue
		}
		var req request
		if err := json.Unmarshal(buf[:n], &req); err != nil {
			continue
		}
		tMaster := clock.MasterNowMs()
		resp := reply{V: 1, T1: req.T1, TMaster: tMaster, T2Send: clock.MasterNowMs()}
		body, err := json.Marshal(resp)
		if err != nil {
			continue
		}
		if _, err := conn.WriteToUDP(body, remote); err != nil {
			log.Printf("clock write: %v", err)
		}
	}
}
