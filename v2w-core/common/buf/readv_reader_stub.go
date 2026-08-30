//go:build wasm || openbsd
// +build wasm openbsd

package buf

import (
	"io"
	"syscall"
)

func useReadV() bool {
	return false
}

func NewReadVReader(reader io.Reader, rawConn syscall.RawConn, counter any) Reader {
	panic("not implemented")
}
