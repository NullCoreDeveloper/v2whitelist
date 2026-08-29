package main

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"fmt"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/proxy/vless"
	vless_outbound "github.com/xtls/xray-core/proxy/vless/outbound"
	"github.com/xtls/xray-core/transport/internet"
	"github.com/xtls/xray-core/transport/internet/reality"
	"github.com/xtls/xray-core/transport/internet/stat"
	"github.com/xtls/xray-core/transport/internet/tcp"
	
	core "github.com/xtls/xray-core" // the scanner package
)

type customDialer struct {
	streamSettings *internet.MemoryStreamConfig
}

func (d *customDialer) Dial(ctx context.Context, dest net.Destination) (stat.Connection, error) {
	return internet.Dial(ctx, dest, d.streamSettings)
}
func (d *customDialer) DestIpAddress() net.IP {
	return nil
}

func main() {
	pbk, _ := base64.RawURLEncoding.DecodeString("4z3TyuT34K2_QGkDTEZvt7lPRtP52okE0J0WX1PhCXk")
	shortId, _ := hex.DecodeString("1a552b0d4d15e0f7")

	streamSettings := &internet.MemoryStreamConfig{
		ProtocolName: "tcp",
		SecurityType: "reality",
		SecuritySettings: &reality.Config{
			Show:        false,
			Dest:        "ads.x5.ru:443",
			Type:        "tcp",
			Xver:        0,
			ServerNames: []string{"ads.x5.ru"},
			ShortIds:    [][]byte{shortId},
			PublicKey:   pbk,
			Fingerprint: "qq",
		},
		ProtocolSettings: &tcp.Config{},
	}

	dialer := &customDialer{streamSettings: streamSettings}

	account := &vless.Account{
		Id:         "303035c0-dd28-4568-87bb-9a11637c5407",
		Encryption: "none",
		Flow:       "xtls-rprx-vision",
	}

	config := &vless_outbound.Config{
		Vnext: &protocol.ServerEndpoint{
			Address: net.NewIPOrDomain(net.ParseAddress("37.18.14.156")),
			Port:    10443,
			User: &protocol.User{
				Account: serial.ToTypedMessage(account),
			},
		},
	}

	handler, err := vless_outbound.New(context.Background(), config)
	if err != nil {
		panic(err)
	}

	scanner := core.NewScanner()
	dest := net.TCPDestination(net.ParseAddress("37.18.14.156"), 10443)
	
	fmt.Printf("Scanning node 37.18.14.156:10443 (REALITY, Yandex)...\n")
	
	result := scanner.TestNode(context.Background(), handler, dialer, dest)
	
	if result.Error != nil {
		fmt.Printf("Result: Failed (%v)\n", result.Error)
	} else {
		fmt.Printf("Result: Success! Latency: %v\n", result.Latency)
	}
}
