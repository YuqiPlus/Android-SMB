package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"strconv"
	"strings"
	"syscall"

	"github.com/tobychui/smbsrv"
	"github.com/tobychui/smbsrv/localfs"
)

type options struct {
	Host            string
	Subnet          string
	AllowedNetworks string
	Port            int
	User            string
	Password        string
	Directory       string
}

func main() {
	if err := run(os.Stdin, os.Stdout); err != nil {
		switch {
		case errors.Is(err, errClientNetworks):
			fmt.Println("INVALID_CLIENT_NETWORKS")
		case errors.Is(err, syscall.EADDRINUSE):
			fmt.Println("PORT_IN_USE")
		case errors.Is(err, syscall.EADDRNOTAVAIL), errors.Is(err, syscall.EACCES):
			fmt.Println("BIND_FAILED")
		default:
			fmt.Println("START_FAILED")
		}
		os.Exit(1)
	}
}

func run(input io.Reader, status io.Writer) error {
	control := bufio.NewReader(input)
	line, err := control.ReadBytes('\n')
	if err != nil {
		return fmt.Errorf("read configuration: %w", err)
	}

	var cfg options
	if err := json.Unmarshal(line, &cfg); err != nil {
		return fmt.Errorf("decode configuration: %w", err)
	}

	ip := net.ParseIP(cfg.Host)
	_, network, err := net.ParseCIDR(cfg.Subnet)
	if err != nil || ip.To4() == nil || ip.IsUnspecified() || !network.Contains(ip) {
		return errors.New("the listener requires a Wi-Fi IPv4 address and subnet")
	}
	if cfg.Port < 1024 || cfg.Port > 65535 || cfg.User == "" || cfg.Password == "" {
		return errors.New("invalid listen port or credentials")
	}

	allowClient, err := clientFilter(network, cfg.AllowedNetworks)
	if err != nil {
		return err
	}

	share, err := localfs.New(localfs.Config{
		Name:    "Share",
		Path:    cfg.Directory,
		Comment: "Android SMB",
	})
	if err != nil {
		return err
	}
	hash := smbsrv.NTHash(cfg.Password)
	cfg.Password = ""
	backend, err := localfs.NewBackend(localfs.Options{
		Lookup: func(name string) (string, []byte, bool) {
			if strings.EqualFold(name, cfg.User) {
				return cfg.User, hash, true
			}
			return "", nil, false
		},
		Shares: func(string) []smbsrv.Share { return []smbsrv.Share{share} },
		// The custom filter also handles explicitly approved shared IPv4 ranges.
		AllowPublicAddresses: true,
		AllowIP:              allowClient,
	})
	if err != nil {
		return err
	}

	server, err := smbsrv.New(smbsrv.Config{
		Address:    net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port)),
		ServerName: "ANDROID-SMB",
		Backend:    backend,
		Logger:     slog.New(slog.DiscardHandler),
		MaxConns:   32,
		MaxPerIP:   8,
	})
	if err != nil {
		return err
	}
	if err := server.Start(); err != nil {
		return err
	}
	defer server.Close()
	fmt.Fprintln(status, "READY")
	// The Android service keeps this pipe open while it owns the server.
	_, err = io.Copy(io.Discard, control)
	return err
}
