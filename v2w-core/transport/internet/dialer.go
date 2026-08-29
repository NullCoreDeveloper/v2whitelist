package internet

import (
	"context"
	"fmt"
	stdnet "net"
	"strings"

	"github.com/xtls/xray-core/common/errors"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet/stat"
)

// Dialer is the interface for dialing outbound connections.
type Dialer interface {
	// Dial dials a system connection to the given destination.
	Dial(ctx context.Context, destination net.Destination) (stat.Connection, error)

	// DestIpAddress returns the ip of proxy server. It is useful in case of Android client, which prepare an IP before proxy connection is established
	DestIpAddress() net.IP

	// SetOutboundGateway removed for v2w-core
}

// dialFunc is an interface to dial network connection to a specific destination.
type dialFunc func(ctx context.Context, dest net.Destination, streamSettings *MemoryStreamConfig) (stat.Connection, error)

var transportDialerCache = make(map[string]dialFunc)

// RegisterTransportDialer registers a Dialer with given name.
func RegisterTransportDialer(protocol string, dialer dialFunc) error {
	if _, found := transportDialerCache[protocol]; found {
		return errors.New(protocol, " dialer already registered").AtError()
	}
	transportDialerCache[protocol] = dialer
	return nil
}

// Dial dials a internet connection towards the given destination.
func Dial(ctx context.Context, dest net.Destination, streamSettings *MemoryStreamConfig) (stat.Connection, error) {
	if dest.Network == net.Network_TCP {
		if streamSettings == nil {
			s, err := ToMemoryStreamConfig(nil)
			if err != nil {
				return nil, errors.New("failed to create default stream settings").Base(err)
			}
			streamSettings = s
		}

		protocol := streamSettings.ProtocolName
		dialer := transportDialerCache[protocol]
		if dialer == nil {
			return nil, errors.New(protocol, " dialer not registered").AtError()
		}
		return dialer(ctx, dest, streamSettings)
	}

	if dest.Network == net.Network_UDP {
		udpDialer := transportDialerCache["udp"]
		if udpDialer == nil {
			return nil, errors.New("UDP dialer not registered").AtError()
		}
		return udpDialer(ctx, dest, streamSettings)
	}

	return nil, errors.New("unknown network ", dest.Network)
}

// DestIpAddress returns the ip of proxy server. It is useful in case of Android client, which prepare an IP before proxy connection is established
func DestIpAddress() net.IP {
	return effectiveSystemDialer.DestIpAddress()
}

// DNS and Routing dependencies removed for v2w-core

func LookupForIP(domain string, strategy DomainStrategy, localAddr net.Address) ([]net.IP, error) {
	// Replaced with standard Go DNS resolution for v2w-core
	ips, err := stdnet.LookupIP(domain)
	if err != nil {
		return nil, err
	}
	var res []net.IP
	for _, ip := range ips {
		res = append(res, ip)
	}
	return res, nil
}

func checkAddressPortStrategy(ctx context.Context, dest net.Destination, sockopt *SocketConfig) (*net.Destination, error) {
	if sockopt.AddressPortStrategy == AddressPortStrategy_None {
		return nil, nil
	}
	newDest := dest
	var OverridePort, OverrideAddress bool
	var OverrideBy string
	switch sockopt.AddressPortStrategy {
	case AddressPortStrategy_SrvPortOnly:
		OverridePort = true
		OverrideAddress = false
		OverrideBy = "srv"
	case AddressPortStrategy_SrvAddressOnly:
		OverridePort = false
		OverrideAddress = true
		OverrideBy = "srv"
	case AddressPortStrategy_SrvPortAndAddress:
		OverridePort = true
		OverrideAddress = true
		OverrideBy = "srv"
	case AddressPortStrategy_TxtPortOnly:
		OverridePort = true
		OverrideAddress = false
		OverrideBy = "txt"
	case AddressPortStrategy_TxtAddressOnly:
		OverridePort = false
		OverrideAddress = true
		OverrideBy = "txt"
	case AddressPortStrategy_TxtPortAndAddress:
		OverridePort = true
		OverrideAddress = true
		OverrideBy = "txt"
	default:
		return nil, errors.New("unknown AddressPortStrategy")
	}

	if !dest.Address.Family().IsDomain() {
		return nil, nil
	}

	if OverrideBy == "srv" {
		errors.LogDebug(ctx, "query SRV record for "+dest.Address.String())
		parts := strings.SplitN(dest.Address.String(), ".", 3)
		if len(parts) != 3 {
			return nil, errors.New("invalid address format", dest.Address.String())
		}
		_, srvRecords, err := net.DefaultResolver.LookupSRV(context.Background(), parts[0][1:], parts[1][1:], parts[2])
		if err != nil {
			return nil, errors.New("failed to lookup SRV record").Base(err)
		}
		errors.LogDebug(ctx, "SRV record: "+fmt.Sprintf("addr=%s, port=%d, priority=%d, weight=%d", srvRecords[0].Target, srvRecords[0].Port, srvRecords[0].Priority, srvRecords[0].Weight))
		if OverridePort {
			newDest.Port = net.Port(srvRecords[0].Port)
		}
		if OverrideAddress {
			newDest.Address = net.ParseAddress(srvRecords[0].Target)
		}
		return &newDest, nil
	}
	if OverrideBy == "txt" {
		errors.LogDebug(ctx, "query TXT record for "+dest.Address.String())
		txtRecords, err := net.DefaultResolver.LookupTXT(ctx, dest.Address.String())
		if err != nil {
			errors.LogError(ctx, "failed to lookup SRV record: "+err.Error())
			return nil, errors.New("failed to lookup SRV record").Base(err)
		}
		for _, txtRecord := range txtRecords {
			errors.LogDebug(ctx, "TXT record: "+txtRecord)
			addr_s, port_s, _ := net.SplitHostPort(string(txtRecord))
			addr := net.ParseAddress(addr_s)
			port, err := net.PortFromString(port_s)
			if err != nil {
				continue
			}

			if OverridePort {
				newDest.Port = port
			}
			if OverrideAddress {
				newDest.Address = addr
			}
			return &newDest, nil
		}
	}
	return nil, nil
}

// DialSystem calls system dialer to create a network connection.
func DialSystem(ctx context.Context, dest net.Destination, sockopt *SocketConfig) (net.Conn, error) {
	if sockopt == nil {
		return effectiveSystemDialer.Dial(ctx, nil, dest, sockopt)
	}

	if sockopt.DomainStrategy.HasStrategy() && dest.Address.Family().IsDomain() {
		ips, err := LookupForIP(dest.Address.Domain(), sockopt.DomainStrategy, nil)
		if err != nil {
			if sockopt.DomainStrategy.ForceIP() {
				return nil, err
			}
		} else if len(ips) > 0 {
			dest.Address = net.IPAddress(ips[0]) // just take the first IP for scanner
		}
	}

	return effectiveSystemDialer.Dial(ctx, nil, dest, sockopt)
}
