package main

import (
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"unicode"
)

var errClientNetworks = errors.New("invalid client network")

var lanRanges = [...]netip.Prefix{
	netip.MustParsePrefix("10.0.0.0/8"),
	netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.168.0.0/16"),
	netip.MustParsePrefix("100.64.0.0/10"),
}

func clientFilter(wifiSubnet *net.IPNet, extra string) (func(net.IP) bool, error) {
	entries := strings.FieldsFunc(extra, func(r rune) bool {
		return r == ',' || r == '，' || unicode.IsSpace(r)
	})
	allowed := make([]netip.Prefix, 0, len(entries))
	for _, entry := range entries {
		if !strings.Contains(entry, "/") {
			entry += "/32"
		}
		prefix, err := netip.ParsePrefix(entry)
		if err != nil || !prefix.Addr().Is4() || !isLANPrefix(prefix) {
			return nil, fmt.Errorf("%w: %q", errClientNetworks, entry)
		}
		allowed = append(allowed, prefix.Masked())
	}

	return func(ip net.IP) bool {
		if (ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast()) && wifiSubnet.Contains(ip) {
			return true
		}
		client, ok := netip.AddrFromSlice(ip.To4())
		if !ok {
			return false
		}
		for _, prefix := range allowed {
			if prefix.Contains(client) {
				return true
			}
		}
		return false
	}, nil
}

func isLANPrefix(prefix netip.Prefix) bool {
	for _, lan := range lanRanges {
		if prefix.Bits() >= lan.Bits() && lan.Contains(prefix.Addr()) {
			return true
		}
	}
	return false
}
