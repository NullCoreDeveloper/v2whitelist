//go:build windows
// +build windows

package tls

import (
	"crypto/x509"

	"github.com/kiktor/v2w-core/common/errors"
)

func (c *Config) getCertPool() (*x509.CertPool, error) {
	if c.DisableSystemRoot {
		return c.loadSelfCertPool()
	}

	if len(c.Certificate) == 0 {
		return loadCA(false), nil
	}

	pool := loadCA(false).Clone()
	for _, cert := range c.Certificate {
		if !pool.AppendCertsFromPEM(cert.Certificate) {
			return nil, errors.New("append cert to root")
		}
	}
	return pool, nil
}

