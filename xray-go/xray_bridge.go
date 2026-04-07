package xraybridge

import (
	"fmt"
	"os"
	"strings"
	"sync"

	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	mu       sync.Mutex
	instance *core.Instance
)

// StartXray starts a single Xray instance using json config and Android TUN fd.
// It returns an empty string on success; otherwise it returns an error message.
func StartXray(jsonConfig string, tunFd int) string {
	mu.Lock()
	defer mu.Unlock()

	if strings.TrimSpace(jsonConfig) == "" {
		return "empty config"
	}
	if tunFd <= 0 {
		return fmt.Sprintf("invalid tun fd: %d", tunFd)
	}
	if instance != nil {
		if err := instance.Close(); err != nil {
			return fmt.Sprintf("failed to stop previous instance: %v", err)
		}
		instance = nil
	}

	fdValue := fmt.Sprintf("%d", tunFd)
	_ = os.Setenv("xray.tun.fd", fdValue)
	_ = os.Setenv("XRAY_TUN_FD", fdValue)

	config, err := core.LoadConfig("json", strings.NewReader(jsonConfig))
	if err != nil {
		return fmt.Sprintf("config parse error: %v", err)
	}

	created, err := core.New(config)
	if err != nil {
		return fmt.Sprintf("core init error: %v", err)
	}

	if err := created.Start(); err != nil {
		_ = created.Close()
		return fmt.Sprintf("core start error: %v", err)
	}

	instance = created
	return ""
}

// StopXray closes the running instance.
// It returns an empty string on success; otherwise it returns an error message.
func StopXray() string {
	mu.Lock()
	defer mu.Unlock()

	if instance == nil {
		return ""
	}

	err := instance.Close()
	instance = nil
	if err != nil {
		return fmt.Sprintf("core stop error: %v", err)
	}
	return ""
}
