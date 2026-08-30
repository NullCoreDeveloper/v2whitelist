package websocket

import (
	"net/http"

	"github.com/kiktor/v2w-core/common"
	"github.com/kiktor/v2w-core/common/utils"
	"github.com/kiktor/v2w-core/transport/internet"
)

func (c *Config) GetNormalizedPath() string {
	path := c.Path
	if path == "" {
		return "/"
	}
	if path[0] != '/' {
		return "/" + path
	}
	return path
}

func (c *Config) GetRequestHeader() http.Header {
	header := http.Header{}
	for k, v := range c.Header {
		header.Add(k, v)
	}
	utils.TryDefaultHeadersWith(header, "ws")
	return header
}

func init() {
	common.Must(internet.RegisterProtocolConfigCreator(protocolName, func() interface{} {
		return new(Config)
	}))
}
