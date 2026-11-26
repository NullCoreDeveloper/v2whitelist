package shadowsocks_2022

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"io"
	"math"
	mrand "math/rand/v2"
	"strings"
	"time"

	"github.com/kiktor/v2w-core/common"
	"github.com/kiktor/v2w-core/common/buf"
	"github.com/kiktor/v2w-core/common/errors"
	"github.com/kiktor/v2w-core/common/net"
	"github.com/kiktor/v2w-core/common/protocol"
	"github.com/kiktor/v2w-core/common/retry"
	"github.com/kiktor/v2w-core/common/signal"
	"github.com/kiktor/v2w-core/common/task"
	"github.com/kiktor/v2w-core/transport"
	"github.com/kiktor/v2w-core/transport/internet"
	"github.com/kiktor/v2w-core/transport/internet/stat"
	"golang.org/x/crypto/chacha20poly1305"
	"lukechampine.com/blake3"
)

const (
	MethodAES128GCM        = "2022-blake3-aes-128-gcm"
	MethodAES256GCM        = "2022-blake3-aes-256-gcm"
	MethodChaCha20Poly1305 = "2022-blake3-chacha20-poly1305"

	headerTypeClient              = 0
	headerTypeServer              = 1
	maxPaddingLength              = 900
	maxPacketSize                 = 65535
	requestHeaderFixedChunkLength = 1 + 8 + 2
	streamNonceSize               = 12
	aesBlockSize                  = 16
	aeadTagSize                   = 16

	contextSessionSubKey  = "shadowsocks 2022 session subkey"
	contextIdentitySubKey = "shadowsocks 2022 identity subkey"
)

var (
	zeroPadding [maxPaddingLength]byte

	addrParser = protocol.NewAddressParser(
		protocol.AddressFamilyByte(0x01, net.AddressFamilyIPv4),
		protocol.AddressFamilyByte(0x04, net.AddressFamilyIPv6),
		protocol.AddressFamilyByte(0x03, net.AddressFamilyDomain),
		protocol.WithAddressTypeParser(func(b byte) byte {
			return b & 0x0F
		}),
	)

	methods = map[string]*CipherMethod{
		MethodAES128GCM:        {Name: MethodAES128GCM, KeySaltLength: 16, IsChaCha: false},
		MethodAES256GCM:        {Name: MethodAES256GCM, KeySaltLength: 32, IsChaCha: false},
		MethodChaCha20Poly1305: {Name: MethodChaCha20Poly1305, KeySaltLength: 32, IsChaCha: true},
	}
)

type CipherMethod struct {
	Name          string
	KeySaltLength int
	IsChaCha      bool
}

type ClientConfig struct {
	Address *net.IPOrDomain
	Port    uint32
	Method  string
	Key     string
}

func IsMethodSupported(name string) bool {
	_, ok := methods[strings.ToLower(strings.TrimSpace(name))]
	return ok
}

func getCipherMethod(name string) (*CipherMethod, error) {
	m, ok := methods[strings.ToLower(strings.TrimSpace(name))]
	if !ok {
		return nil, errors.New("unsupported shadowsocks 2022 method: ", name)
	}
	return m, nil
}

func (m *CipherMethod) newAEAD(key []byte) (cipher.AEAD, error) {
	if m.IsChaCha {
		return chacha20poly1305.New(key)
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

func (m *CipherMethod) newBlock(key []byte) (cipher.Block, error) {
	return aes.NewCipher(key)
}

func parseKey(key string, keyLength int) ([]byte, error) {
	key = strings.TrimSpace(key)
	raw, err := base64.StdEncoding.DecodeString(key)
	if err != nil {
		if rawURL, errURL := base64.RawStdEncoding.DecodeString(key); errURL == nil {
			raw = rawURL
		} else {
			raw = []byte(key)
		}
	}
	if len(raw) != keyLength {
		return nil, errors.New("bad shadowsocks 2022 key length")
	}
	return raw, nil
}

func parsePSKList(password string, keyLength int) ([][]byte, error) {
	parts := strings.Split(password, ":")
	pskList := make([][]byte, len(parts))
	for i, part := range parts {
		norm, err := parseKey(part, keyLength)
		if err != nil {
			return nil, err
		}
		pskList[i] = norm
	}
	return pskList, nil
}

func deriveSubKey(ctx string, psk, salt []byte, keyLength int) []byte {
	var keyMaterial [64]byte
	kmLen := len(psk) + len(salt)
	copy(keyMaterial[:], psk)
	copy(keyMaterial[len(psk):], salt)
	out := make([]byte, keyLength)
	blake3.DeriveKey(out, ctx, keyMaterial[:kmLen])
	return out
}

func deriveUserPSKHash(userPSK []byte) [aesBlockSize]byte {
	h := blake3.Sum512(userPSK)
	var out [aesBlockSize]byte
	copy(out[:], h[:aesBlockSize])
	return out
}

func increaseNonce(nonce []byte) {
	for i := range nonce {
		nonce[i]++
		if nonce[i] != 0 {
			return
		}
	}
}

func addrPortLength(dest net.Destination) int {
	switch dest.Address.Family() {
	case net.AddressFamilyIPv4:
		return 1 + 4 + 2
	case net.AddressFamilyDomain:
		return 1 + 1 + len(dest.Address.Domain()) + 2
	case net.AddressFamilyIPv6:
		return 1 + 16 + 2
	default:
		return 0
	}
}

type Client struct {
	server   net.Destination
	method   *CipherMethod
	pskList  [][]byte
	finalPSK []byte
}

// NewClient creates a lightweight TCP Shadowsocks-2022 client for v2w-core.
func NewClient(_ context.Context, config *ClientConfig) (*Client, error) {
	if config == nil || config.Address == nil {
		return nil, errors.New("no target server found")
	}
	method, err := getCipherMethod(config.Method)
	if err != nil {
		return nil, err
	}
	pskList, err := parsePSKList(config.Key, method.KeySaltLength)
	if err != nil {
		return nil, err
	}
	if method.IsChaCha && len(pskList) > 1 {
		return nil, errors.New("multi-key is not supported for chacha20-poly1305")
	}
	return &Client{
		server: net.Destination{
			Address: config.Address.AsAddress(),
			Port:    net.Port(config.Port),
			Network: net.Network_TCP,
		},
		method:   method,
		pskList:  pskList,
		finalPSK: pskList[len(pskList)-1],
	}, nil
}

func (c *Client) Process(ctx context.Context, link *transport.Link, dialer internet.Dialer, destination net.Destination) error {
	if destination.Network != net.Network_TCP {
		return errors.New("shadowsocks-2022 client only supports TCP")
	}

	var conn stat.Connection
	if err := retry.ExponentialBackoff(5, 100).On(func() error {
		rawConn, err := dialer.Dial(ctx, c.server)
		if err != nil {
			return err
		}
		conn = rawConn
		return nil
	}); err != nil {
		return errors.New("failed to find an available destination").AtWarning().Base(err)
	}
	defer conn.Close()

	ctx, cancel := context.WithCancel(ctx)
	timer := signal.CancelAfterInactivity(ctx, cancel, 10*time.Second)
	defer timer.SetTimeout(0)

	var clientSalt [32]byte
	clientSaltSlice := clientSalt[:c.method.KeySaltLength]
	if _, err := io.ReadFull(rand.Reader, clientSaltSlice); err != nil {
		return errors.New("failed to generate client salt").Base(err)
	}

	requestDone := func() error {
		defer timer.SetTimeout(10 * time.Second)

		var initialPayload []byte
		var firstBuf *buf.Buffer
		var remainingMB buf.MultiBuffer
		if timeoutReader, ok := link.Reader.(buf.TimeoutReader); ok {
			if mb, err := timeoutReader.ReadMultiBufferTimeout(0); err == nil && !mb.IsEmpty() {
				remainingMB, firstBuf = buf.SplitFirst(mb)
				initialPayload = firstBuf.Bytes()
			}
		}

		bodyWriter, err := writeTCPRequest(conn, c.method, c.pskList, destination, clientSaltSlice, initialPayload)
		if firstBuf != nil {
			firstBuf.Release()
		}
		if err != nil {
			buf.ReleaseMulti(remainingMB)
			return errors.New("failed to write request").Base(err)
		}
		if !remainingMB.IsEmpty() {
			if err := bodyWriter.WriteMultiBuffer(remainingMB); err != nil {
				return err
			}
		}
		return buf.Copy(link.Reader, bodyWriter, buf.UpdateActivity(timer))
	}

	responseDone := func() error {
		defer timer.SetTimeout(10 * time.Second)

		responseReader, err := readTCPResponse(conn, c.method, c.finalPSK, clientSaltSlice)
		if err != nil {
			return err
		}
		return buf.Copy(responseReader, link.Writer, buf.UpdateActivity(timer))
	}

	responseDoneAndCloseWriter := task.OnSuccess(responseDone, task.Close(link.Writer))
	if err := task.Run(ctx, requestDone, responseDoneAndCloseWriter); err != nil {
		common.Interrupt(link.Reader)
		common.Interrupt(link.Writer)
		return errors.New("connection ends").Base(err)
	}
	return nil
}

type streamWriter struct {
	writer io.Writer
	cipher cipher.AEAD
	nonce  [streamNonceSize]byte
	lenBuf [2]byte
	buf    []byte
}

func newStreamWriter(w io.Writer, c cipher.AEAD) *streamWriter {
	return &streamWriter{
		writer: w,
		cipher: c,
		buf:    make([]byte, 0, maxPacketSize+2+2*aeadTagSize),
	}
}

func (w *streamWriter) writeChunk(payload []byte) error {
	payloadLen := len(payload)
	if payloadLen == 0 {
		return nil
	}
	if payloadLen > maxPacketSize {
		return errors.New("payload exceeds maxPacketSize")
	}
	binary.BigEndian.PutUint16(w.lenBuf[:], uint16(payloadLen))
	w.buf = w.cipher.Seal(w.buf[:0], w.nonce[:], w.lenBuf[:], nil)
	increaseNonce(w.nonce[:])

	w.buf = w.cipher.Seal(w.buf, w.nonce[:], payload, nil)
	increaseNonce(w.nonce[:])

	_, err := w.writer.Write(w.buf)
	return err
}

func (w *streamWriter) WriteMultiBuffer(mb buf.MultiBuffer) error {
	defer buf.ReleaseMulti(mb)
	for _, b := range mb {
		p := b.Bytes()
		for len(p) > 0 {
			chunkSize := len(p)
			if chunkSize > maxPacketSize {
				chunkSize = maxPacketSize
			}
			if err := w.writeChunk(p[:chunkSize]); err != nil {
				return err
			}
			p = p[chunkSize:]
		}
	}
	return nil
}

type streamReader struct {
	reader io.Reader
	cipher cipher.AEAD
	nonce  [streamNonceSize]byte
	lenBuf [2 + aeadTagSize]byte
	buffer []byte
	cached int
	offset int
}

func newStreamReader(r io.Reader, c cipher.AEAD) *streamReader {
	return &streamReader{
		reader: r,
		cipher: c,
		buffer: make([]byte, maxPacketSize+aeadTagSize),
	}
}

func (r *streamReader) ReadMultiBuffer() (buf.MultiBuffer, error) {
	if r.cached > 0 {
		mb := buf.MergeBytes(nil, r.buffer[r.offset:r.offset+r.cached])
		r.cached = 0
		r.offset = 0
		return mb, nil
	}
	if _, err := io.ReadFull(r.reader, r.lenBuf[:]); err != nil {
		return nil, err
	}
	decryptedLen, err := r.cipher.Open(r.lenBuf[:0], r.nonce[:], r.lenBuf[:], nil)
	if err != nil {
		return nil, errors.New("failed to decrypt chunk length").Base(err)
	}
	increaseNonce(r.nonce[:])

	payloadLen := int(binary.BigEndian.Uint16(decryptedLen))
	if payloadLen == 0 || payloadLen > maxPacketSize {
		return nil, errors.New("invalid shadowsocks-2022 chunk length")
	}

	chunkEnd := payloadLen + aeadTagSize
	if _, err := io.ReadFull(r.reader, r.buffer[:chunkEnd]); err != nil {
		return nil, err
	}
	decryptedPayload, err := r.cipher.Open(r.buffer[:0], r.nonce[:], r.buffer[:chunkEnd], nil)
	if err != nil {
		return nil, errors.New("failed to decrypt chunk payload").Base(err)
	}
	increaseNonce(r.nonce[:])

	return buf.MergeBytes(nil, decryptedPayload), nil
}

func writeTCPRequest(w io.Writer, method *CipherMethod, pskList [][]byte, dest net.Destination, clientSalt, payload []byte) (buf.Writer, error) {
	finalPSK := pskList[len(pskList)-1]
	sessionKey := deriveSubKey(contextSessionSubKey, finalPSK, clientSalt, method.KeySaltLength)
	aead, err := method.newAEAD(sessionKey)
	if err != nil {
		return nil, err
	}
	writer := newStreamWriter(w, aead)

	payloadLen := len(payload)
	var paddingLen int
	if payloadLen < maxPaddingLength {
		paddingLen = mrand.IntN(maxPaddingLength) + 1
	}
	varHeaderLen := addrPortLength(dest) + 2 + paddingLen + payloadLen

	totalHandshakeLen := int32(method.KeySaltLength + (len(pskList)-1)*aesBlockSize + requestHeaderFixedChunkLength + aeadTagSize + varHeaderLen + aeadTagSize)
	handshakeBuf := buf.NewWithSize(totalHandshakeLen)
	defer handshakeBuf.Release()

	handshakeBuf.Write(clientSalt)
	for i, currPSK := range pskList[:len(pskList)-1] {
		identitySubkey := deriveSubKey(contextIdentitySubKey, currPSK, clientSalt, method.KeySaltLength)
		block, err := method.newBlock(identitySubkey)
		if err != nil {
			return nil, err
		}
		pskHash := deriveUserPSKHash(pskList[i+1])
		var encryptedEIH [aesBlockSize]byte
		block.Encrypt(encryptedEIH[:], pskHash[:])
		handshakeBuf.Write(encryptedEIH[:])
	}

	var fixedHeaderPlaintext [requestHeaderFixedChunkLength]byte
	fixedHeaderPlaintext[0] = headerTypeClient
	binary.BigEndian.PutUint64(fixedHeaderPlaintext[1:9], uint64(time.Now().Unix()))
	binary.BigEndian.PutUint16(fixedHeaderPlaintext[9:11], uint16(varHeaderLen))

	fixedChunk := writer.cipher.Seal(nil, writer.nonce[:], fixedHeaderPlaintext[:], nil)
	increaseNonce(writer.nonce[:])
	handshakeBuf.Write(fixedChunk)

	varHeaderBuf := buf.NewWithSize(int32(varHeaderLen))
	defer varHeaderBuf.Release()

	if err := addrParser.WriteAddressPort(varHeaderBuf, dest.Address, dest.Port); err != nil {
		return nil, err
	}
	var padLenBytes [2]byte
	binary.BigEndian.PutUint16(padLenBytes[:], uint16(paddingLen))
	varHeaderBuf.Write(padLenBytes[:])
	if paddingLen > 0 {
		varHeaderBuf.Write(zeroPadding[:paddingLen])
	}
	if payloadLen > 0 {
		varHeaderBuf.Write(payload)
	}

	varChunk := writer.cipher.Seal(nil, writer.nonce[:], varHeaderBuf.Bytes(), nil)
	increaseNonce(writer.nonce[:])
	handshakeBuf.Write(varChunk)

	if _, err := w.Write(handshakeBuf.Bytes()); err != nil {
		return nil, err
	}
	return writer, nil
}

func readTCPResponse(r io.Reader, method *CipherMethod, psk, clientSalt []byte) (buf.Reader, error) {
	fixedPlainLen := 1 + 8 + method.KeySaltLength + 2
	chunkCipherLen := fixedPlainLen + aeadTagSize
	headerLen := method.KeySaltLength + chunkCipherLen

	var headerBuf [128]byte
	headerSlice := headerBuf[:headerLen]
	if _, err := io.ReadFull(r, headerSlice); err != nil {
		return nil, errors.New("failed to read complete server response header").Base(err)
	}

	serverSaltSlice := headerSlice[:method.KeySaltLength]
	chunkSlice := headerSlice[method.KeySaltLength:headerLen]

	sessionKey := deriveSubKey(contextSessionSubKey, psk, serverSaltSlice, method.KeySaltLength)
	aead, err := method.newAEAD(sessionKey)
	if err != nil {
		return nil, err
	}
	reader := newStreamReader(r, aead)

	decryptedFixed, err := reader.cipher.Open(chunkSlice[:0], reader.nonce[:], chunkSlice, nil)
	if err != nil {
		return nil, errors.New("failed to decrypt server response header").Base(err)
	}
	increaseNonce(reader.nonce[:])

	if decryptedFixed[0] != headerTypeServer {
		return nil, errors.New("bad header type")
	}
	serverEpoch := binary.BigEndian.Uint64(decryptedFixed[1:9])
	if math.Abs(float64(time.Now().Unix()-int64(serverEpoch))) > 30 {
		return nil, errors.New("bad timestamp")
	}
	echoedSalt := decryptedFixed[9 : 9+method.KeySaltLength]
	for i := 0; i < method.KeySaltLength; i++ {
		if echoedSalt[i] != clientSalt[i] {
			return nil, errors.New("bad request salt")
		}
	}

	initialPayloadLen := int(binary.BigEndian.Uint16(decryptedFixed[9+method.KeySaltLength : 11+method.KeySaltLength]))
	if initialPayloadLen > 0 {
		initialCipherLen := initialPayloadLen + aeadTagSize
		if _, err := io.ReadFull(r, reader.buffer[:initialCipherLen]); err != nil {
			return nil, err
		}
		decryptedInitial, err := reader.cipher.Open(reader.buffer[:0], reader.nonce[:], reader.buffer[:initialCipherLen], nil)
		if err != nil {
			return nil, errors.New("failed to decrypt initial response payload").Base(err)
		}
		increaseNonce(reader.nonce[:])
		reader.cached = len(decryptedInitial)
		reader.offset = 0
	}
	return reader, nil
}
