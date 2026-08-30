package udp

import (
	"context"

	"github.com/kiktor/v2w-core/common"
	"github.com/kiktor/v2w-core/common/net"
	"github.com/kiktor/v2w-core/transport/internet"
	"github.com/kiktor/v2w-core/transport/internet/stat"
)

func init() {
	common.Must(internet.RegisterTransportDialer(protocolName,
		func(ctx context.Context, dest net.Destination, streamSettings *internet.MemoryStreamConfig) (stat.Connection, error) {
			var sockopt *internet.SocketConfig
			if streamSettings != nil {
				sockopt = streamSettings.SocketSettings
			}
			conn, err := internet.DialSystem(ctx, dest, sockopt)
			if err != nil {
				return nil, err
			}


			return conn, nil
		}))
}
