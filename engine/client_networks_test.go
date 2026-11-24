package main

import (
	"errors"
	"net"
	"testing"
)

func TestClientFilter(t *testing.T) {
	_, wifi, _ := net.ParseCIDR("192.168.1.50/24")
	for _, tc := range []struct {
		name    string
		extra   string
		address string
		want    bool
	}{
		{"legacy configuration", "", "192.168.1.43", true},
		{"different subnet by default", "", "10.20.30.45", false},
		{"explicit client", "10.20.30.45", "10.20.30.45", true},
		{"neighbour of explicit client", "10.20.30.45", "10.20.30.46", false},
		{"entire explicit subnet", "10.20.30.7/24", "10.20.30.46", true},
		{"outside explicit subnet", "10.20.30.0/24", "10.20.31.46", false},
		{"multiple clients", "10.20.30.0/24， 172.16.8.25", "172.16.8.25", true},
		{"shared address space", "100.64.0.0/10", "100.99.0.12", true},
		{"public address", "10.0.0.0/8", "8.8.8.8", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			filter, err := clientFilter(wifi, tc.extra)
			if err != nil {
				t.Fatal(err)
			}
			if got := filter(net.ParseIP(tc.address)); got != tc.want {
				t.Errorf("allow(%s) = %v, want %v", tc.address, got, tc.want)
			}
		})
	}
}

func TestClientFilterRejectsInvalidOrPublicNetworks(t *testing.T) {
	_, wifi, _ := net.ParseCIDR("192.168.1.50/24")
	for _, entry := range []string{
		"0.0.0.0/0", "10.0.0.0/7", "8.8.8.8", "100.64.0.0/9", "fd00::/8",
		"172.32.0.0/16", "192.168.1.256", "10.0.8.0/24, not-an-ip",
	} {
		t.Run(entry, func(t *testing.T) {
			if _, err := clientFilter(wifi, entry); !errors.Is(err, errClientNetworks) {
				t.Errorf("clientFilter(%q): got %v, want %v", entry, err, errClientNetworks)
			}
		})
	}
}
