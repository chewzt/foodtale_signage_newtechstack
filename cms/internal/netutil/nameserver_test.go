package netutil

import "testing"

func TestNameservers(t *testing.T) {
	got := Nameservers(" 1.1.1.1, 8.8.8.8 ,not-an-ip,[2001:4860:4860::8888],1.1.1.1,fe80::1%eth0")
	want := []string{"1.1.1.1", "8.8.8.8", "2001:4860:4860::8888", "fe80::1"}
	if len(got) != len(want) {
		t.Fatalf("got %v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v", got)
		}
	}
	if len(Nameservers("")) != 0 {
		t.Fatal("empty list should stay empty")
	}
}
