package wireguard

import (
	"context"

	"github.com/kiktor/v2w-core/common"
)

func init() {
	common.Must(common.RegisterConfig((*DeviceConfig)(nil), func(ctx context.Context, config interface{}) (interface{}, error) {
		return NewClient(ctx, config.(*DeviceConfig))
	}))
}
