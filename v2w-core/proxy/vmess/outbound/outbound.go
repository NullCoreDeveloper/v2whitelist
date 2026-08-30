package outbound

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"hash/crc64"
	"sync/atomic"
	"time"

	"github.com/kiktor/v2w-core/common/buf"
	"github.com/kiktor/v2w-core/common/errors"
	"github.com/kiktor/v2w-core/common/net"
	"github.com/kiktor/v2w-core/common/platform"
	"github.com/kiktor/v2w-core/common/protocol"
	"github.com/kiktor/v2w-core/common/retry"
	"github.com/kiktor/v2w-core/common/session"
	"github.com/kiktor/v2w-core/common/signal"
	"github.com/kiktor/v2w-core/common/task"
	"github.com/kiktor/v2w-core/common/xudp"
	"github.com/kiktor/v2w-core/proxy/vmess"
	"github.com/kiktor/v2w-core/proxy/vmess/encoding"
	"github.com/kiktor/v2w-core/transport"
	"github.com/kiktor/v2w-core/transport/internet"
	"github.com/kiktor/v2w-core/transport/internet/stat"
)

// Handler is an outbound connection handler for VMess protocol.
type Handler struct {
	server *protocol.ServerSpec
	cone   bool
}

// New creates a new VMess outbound handler.
func New(ctx context.Context, config *Config) (*Handler, error) {
	if config.Receiver == nil {
		return nil, errors.New(`no vnext found`)
	}
	server, err := protocol.NewServerSpecFromPB(config.Receiver)
	if err != nil {
		return nil, errors.New("failed to get server spec").Base(err)
	}

	handler := &Handler{
		server: server,
		cone:   false, // Hardcoded for scanner
	}

	return handler, nil
}

// Process implements proxy.Outbound.Process().
func (h *Handler) Process(ctx context.Context, link *transport.Link, dialer internet.Dialer, target net.Destination) error {
	rec := h.server
	var conn stat.Connection

	err := retry.ExponentialBackoff(5, 200).On(func() error {
		rawConn, err := dialer.Dial(ctx, rec.Destination)
		if err != nil {
			return err
		}
		conn = rawConn

		return nil
	})
	if err != nil {
		return errors.New("failed to find an available destination").Base(err).AtWarning()
	}
	defer conn.Close()

	errors.LogInfo(ctx, "tunneling request to ", target, " via ", rec.Destination.NetAddr())

	command := protocol.RequestCommandTCP
	if target.Network == net.Network_UDP {
		command = protocol.RequestCommandUDP
	}
	if target.Address.Family().IsDomain() && target.Address.Domain() == "v1.mux.cool" {
		command = protocol.RequestCommandMux
	}

	user := rec.User
	request := &protocol.RequestHeader{
		Version: encoding.Version,
		User:    user,
		Command: command,
		Address: target.Address,
		Port:    target.Port,
		Option:  protocol.RequestOptionChunkStream,
	}

	account := request.User.Account.(*vmess.MemoryAccount)
	request.Security = account.Security

	if request.Security == protocol.SecurityType_AES128_GCM || request.Security == protocol.SecurityType_CHACHA20_POLY1305 {
		request.Option.Set(protocol.RequestOptionChunkMasking)
	}

	if shouldEnablePadding(request.Security) && request.Option.Has(protocol.RequestOptionChunkMasking) {
		request.Option.Set(protocol.RequestOptionGlobalPadding)
	}

	if account.AuthenticatedLengthExperiment {
		request.Option.Set(protocol.RequestOptionAuthenticatedLength)
	}

	input := link.Reader
	output := link.Writer

	hashkdf := hmac.New(sha256.New, []byte("VMessBF"))
	hashkdf.Write(account.ID.Bytes())

	behaviorSeed := crc64.Checksum(hashkdf.Sum(nil), crc64.MakeTable(crc64.ISO))

	var newCtx context.Context
	var newCancel context.CancelFunc
	if session.TimeoutOnlyFromContext(ctx) {
		newCtx, newCancel = context.WithCancel(context.Background())
	}

	session := encoding.NewClientSession(ctx, int64(behaviorSeed))

	ctx, cancel := context.WithCancel(ctx)
	timer := signal.CancelAfterInactivity(ctx, func() {
		cancel()
		if newCancel != nil {
			newCancel()
		}
	}, 10*time.Second) // Hardcoded for scanner

	if request.Command == protocol.RequestCommandUDP && h.cone && request.Port != 53 && request.Port != 443 {
		request.Command = protocol.RequestCommandMux
		request.Address = net.DomainAddress("v1.mux.cool")
		request.Port = net.Port(666)
	}

	requestDone := func() error {
		defer timer.SetTimeout(10 * time.Second) // Hardcoded uplink timeout

		writer := buf.NewBufferedWriter(buf.NewWriter(conn))
		if err := session.EncodeRequestHeader(request, writer); err != nil {
			return errors.New("failed to encode request").Base(err).AtWarning()
		}

		bodyWriter, err := session.EncodeRequestBody(request, writer)
		if err != nil {
			return errors.New("failed to start encoding").Base(err)
		}
		bodyWriter2 := bodyWriter
		if request.Command == protocol.RequestCommandMux && request.Port == 666 {
			bodyWriter = xudp.NewPacketWriter(bodyWriter, target, xudp.GetGlobalID(ctx))
		}
		if err := buf.CopyOnceTimeout(input, bodyWriter, time.Millisecond*100); err != nil && err != buf.ErrNotTimeoutReader && err != buf.ErrReadTimeout {
			return errors.New("failed to write first payload").Base(err)
		}

		if err := writer.SetBuffered(false); err != nil {
			return err
		}

		if err := buf.Copy(input, bodyWriter, buf.UpdateActivity(timer)); err != nil {
			return err
		}

		if request.Option.Has(protocol.RequestOptionChunkStream) && !account.NoTerminationSignal {
			if err := bodyWriter2.WriteMultiBuffer(buf.MultiBuffer{}); err != nil {
				return err
			}
		}

		return nil
	}

	responseDone := func() error {
		defer timer.SetTimeout(10 * time.Second) // Hardcoded downlink timeout

		reader := &buf.BufferedReader{Reader: buf.NewReader(conn)}
		header, err := session.DecodeResponseHeader(reader)
		if err != nil {
			return errors.New("failed to read header").Base(err)
		}
		h.handleCommand(rec.Destination, header.Command)

		bodyReader, err := session.DecodeResponseBody(request, reader)
		if err != nil {
			return errors.New("failed to start encoding response").Base(err)
		}
		if request.Command == protocol.RequestCommandMux && request.Port == 666 {
			bodyReader = xudp.NewPacketReader(&buf.BufferedReader{Reader: bodyReader})
		}

		return buf.Copy(bodyReader, output, buf.UpdateActivity(timer))
	}

	if newCtx != nil {
		ctx = newCtx
	}

	responseDonePost := task.OnSuccess(responseDone, task.Close(output))
	if err := task.Run(ctx, requestDone, responseDonePost); err != nil {
		return errors.New("connection ends").Base(err)
	}

	return nil
}

var enablePadding atomic.Bool

func shouldEnablePadding(s protocol.SecurityType) bool {
	return enablePadding.Load() || s == protocol.SecurityType_AES128_GCM || s == protocol.SecurityType_CHACHA20_POLY1305 || s == protocol.SecurityType_AUTO
}

func reloadEnvSettings() error {
	const defaultFlagValue = "NOT_DEFINED_AT_ALL"
	paddingValue := platform.NewEnvFlag(platform.UseVmessPadding).GetValue(func() string { return defaultFlagValue })
	enablePadding.Store(paddingValue != defaultFlagValue)
	return nil
}

// init removed for v2w-core
