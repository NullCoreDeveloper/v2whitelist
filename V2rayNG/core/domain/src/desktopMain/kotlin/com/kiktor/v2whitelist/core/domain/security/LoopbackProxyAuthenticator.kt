package com.kiktor.v2whitelist.core.domain.security

import java.net.Authenticator
import java.net.PasswordAuthentication
import java.util.concurrent.ConcurrentHashMap

/**
 * Безопасный глобальный Authenticator, передающий учётные данные прокси
 * строго локальному loopback-интерфейсу.
 */
object LoopbackProxyAuthenticator {
    private val extraAllowedPorts = ConcurrentHashMap.newKeySet<Int>()

    fun allowPort(port: Int) {
        if (port > 0) extraAllowedPorts.add(port)
    }

    fun disallowPort(port: Int) {
        extraAllowedPorts.remove(port)
    }

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
                    allowedPorts = allowedPortsProvider() + extraAllowedPorts
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
