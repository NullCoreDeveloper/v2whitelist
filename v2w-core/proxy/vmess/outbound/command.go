package outbound

import (
	"github.com/kiktor/v2w-core/common/net"
	"github.com/kiktor/v2w-core/common/protocol"
)

// As a stub command consumer.
func (h *Handler) handleCommand(dest net.Destination, cmd protocol.ResponseCommand) {
	switch cmd.(type) {
	default:
	}
}
