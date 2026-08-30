package tcp

import (
	"github.com/kiktor/v2w-core/common"
	"github.com/kiktor/v2w-core/transport/internet"
)

func init() {
	common.Must(internet.RegisterProtocolConfigCreator(protocolName, func() interface{} {
		return new(Config)
	}))
}
