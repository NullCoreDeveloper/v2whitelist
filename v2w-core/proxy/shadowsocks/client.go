package shadowsocks

import (
	"context"
	"time"

	"github.com/xtls/xray-core/common"
	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/errors"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
	"github.com/xtls/xray-core/common/retry"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/common/signal"
	"github.com/xtls/xray-core/common/task"
	"github.com/xtls/xray-core/common/signal"
	"github.com/xtls/xray-core/common/task"
	"github.com/xtls/xray-core/transport"
	"github.com/xtls/xray-core/transport/internet"
	"github.com/xtls/xray-core/transport/internet/stat"
)

// Client is a inbound handler for Shadowsocks protocol
type Client struct {
	server *protocol.ServerSpec
}

// NewClient create a new Shadowsocks client.
func NewClient(ctx context.Context, config *ClientConfig) (*Client, error) {
	if config.Server == nil {
		return nil, errors.New(`no target server found`)
	}
	server, err := protocol.NewServerSpecFromPB(config.Server)
	if err != nil {
		return nil, errors.New("failed to get server spec").Base(err)
	}

	client := &Client{
		server: server,
	}
	return client, nil
}

// Process implements OutboundHandler.Process().
func (c *Client) Process(ctx context.Context, link *transport.Link, dialer internet.Dialer, destination net.Destination) error {
	network := destination.Network

	server := c.server
	dest := server.Destination
	dest.Network = network
	var conn stat.Connection

	err := retry.ExponentialBackoff(5, 100).On(func() error {
		rawConn, err := dialer.Dial(ctx, dest)
		if err != nil {
			return err
		}
		conn = rawConn

		return nil
	})
	if err != nil {
		return errors.New("failed to find an available destination").AtWarning().Base(err)
	}
	errors.LogInfo(ctx, "tunneling request to ", destination, " via ", network, ":", server.Destination.NetAddr())

	defer conn.Close()

	request := &protocol.RequestHeader{
		Version: Version,
		Address: destination.Address,
		Port:    destination.Port,
	}
	if destination.Network == net.Network_TCP {
		request.Command = protocol.RequestCommandTCP
	} else {
		request.Command = protocol.RequestCommandUDP
	}

	user := server.User
	_, ok := user.Account.(*MemoryAccount)
	if !ok {
		return errors.New("user account is not valid")
	}
	request.User = user

	var newCtx context.Context
	var newCancel context.CancelFunc
	if session.TimeoutOnlyFromContext(ctx) {
		newCtx, newCancel = context.WithCancel(context.Background())
	}

	ctx, cancel := context.WithCancel(ctx)
	timer := signal.CancelAfterInactivity(ctx, func() {
		cancel()
		if newCancel != nil {
			newCancel()
		}
	}, 10*time.Second) // Hardcoded for scanner

	if newCtx != nil {
		ctx = newCtx
	}

	if request.Command == protocol.RequestCommandTCP {
		requestDone := func() error {
			defer timer.SetTimeout(10 * time.Second) // Hardcoded downlink timeout
			bufferedWriter := buf.NewBufferedWriter(buf.NewWriter(conn))
			bodyWriter, err := WriteTCPRequest(request, bufferedWriter)
			if err != nil {
				return errors.New("failed to write request").Base(err)
			}

			if err = buf.CopyOnceTimeout(link.Reader, bodyWriter, time.Millisecond*100); err != nil && err != buf.ErrNotTimeoutReader && err != buf.ErrReadTimeout {
				return errors.New("failed to write A request payload").Base(err).AtWarning()
			}

			if err := bufferedWriter.SetBuffered(false); err != nil {
				return err
			}

			return buf.Copy(link.Reader, bodyWriter, buf.UpdateActivity(timer))
		}

		responseDone := func() error {
			defer timer.SetTimeout(10 * time.Second) // Hardcoded uplink timeout

			responseReader, err := ReadTCPResponse(user, conn)
			if err != nil {
				return err
			}

			return buf.Copy(responseReader, link.Writer, buf.UpdateActivity(timer))
		}

		responseDoneAndCloseWriter := task.OnSuccess(responseDone, task.Close(link.Writer))
		if err := task.Run(ctx, requestDone, responseDoneAndCloseWriter); err != nil {
			return errors.New("connection ends").Base(err)
		}

		return nil
	}

	if request.Command == protocol.RequestCommandUDP {

		requestDone := func() error {
			defer timer.SetTimeout(10 * time.Second) // Hardcoded downlink timeout

			writer := &UDPWriter{
				Writer:  conn,
				Request: request,
			}

			if err := buf.Copy(link.Reader, writer, buf.UpdateActivity(timer)); err != nil {
				return errors.New("failed to transport all UDP request").Base(err)
			}
			return nil
		}

		responseDone := func() error {
			defer timer.SetTimeout(10 * time.Second) // Hardcoded uplink timeout

			reader := &UDPReader{
				Reader: conn,
				User:   user,
			}

			if err := buf.Copy(reader, link.Writer, buf.UpdateActivity(timer)); err != nil {
				return errors.New("failed to transport all UDP response").Base(err)
			}
			return nil
		}

		responseDoneAndCloseWriter := task.OnSuccess(responseDone, task.Close(link.Writer))
		if err := task.Run(ctx, requestDone, responseDoneAndCloseWriter); err != nil {
			return errors.New("connection ends").Base(err)
		}

		return nil
	}

	return nil
}

// init removed for v2w-core
