package com.kiktor.v2whitelist.core.domain.security

import java.net.Authenticator
import java.net.PasswordAuthentication

/**
 * Безопасный глобальный Authenticator, передающий учётные данные прокси
 * строго локальному loopback-интерфейсу.
 */
object LoopbackProxyAuthenticator {
    fun install(
        userProvider: () -> String,
        passProvider: () -> String,
        allowedPortsProvider: () -> Set<Int> = { emptySet() }
    ) {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val allowed = LoopbackProxyAuthPolicy.shouldAuthenticate(
                    protocol = requestingProtocol,
                    host = requestingHost,
                    siteIsLoopback = requestingSite?.isLoopbackAddress == true,
                    port = requestingPort,
                    allowedPorts = allowedPortsProvider()
                )
                if (!allowed) return null
                val user = userProvider()
                val pass = passProvider()
                if (user.isEmpty() || pass.isEmpty()) return null
                return PasswordAuthentication(user, pass.toCharArray())
            }
        })
    }
}
