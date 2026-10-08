package hysteria

import (
	"github.com/kiktor/v2w-core/common/serial"
)

func init() {
	serial.RegisterCreator("xray.proxy.hysteria.ClientConfig", func() interface{} { return &ClientConfig{} })
	serial.RegisterCreator("xray.proxy.hysteria.ServerConfig", func() interface{} { return &ServerConfig{} })
}
