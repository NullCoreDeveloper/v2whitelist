package internet

import (
	"context"

	"github.com/kiktor/v2w-core/common/net"
)

// ContextDialer позволяет перенаправлять исходящие системные подключения (например, через первый узел цепочки).
type ContextDialer func(ctx context.Context, dest net.Destination) (net.Conn, error)

type contextDialerKey struct{}

// ContextWithDialer привязывает пользовательский диалер к контексту (передайте nil для сброса в дочернем контексте).
func ContextWithDialer(ctx context.Context, dialer ContextDialer) context.Context {
	return context.WithValue(ctx, contextDialerKey{}, dialer)
}

// DialerFromContext возвращает ContextDialer из контекста или nil.
func DialerFromContext(ctx context.Context) ContextDialer {
	if dialer, ok := ctx.Value(contextDialerKey{}).(ContextDialer); ok {
		return dialer
	}
	return nil
}
