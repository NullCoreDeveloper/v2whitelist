package com.kiktor.v2whitelist.core.config

import com.kiktor.v2whitelist.core.config.xray.XrayConfigGenerator
import com.kiktor.v2whitelist.core.config.xray.XrayGeneratorOptions
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class XrayConfigGeneratorSecurityTest {

    private fun sampleProfile() = ProfileItem(
        configType = EConfigType.VLESS,
        server = "example.com",
        serverPort = "443",
        password = "11111111-1111-1111-1111-111111111111",
        security = "tls"
    )

    @Test
    fun httpInboundRequiresPasswordWhenCredentialsProvided() {
        val cfg = XrayConfigGenerator.buildConfig(
            sampleProfile(),
            XrayGeneratorOptions(
                socksPort = 10808,
                httpPort = 10809,
                socksUsername = "u_local",
                socksPassword = "secret_password"
            )
        )
        val socksIn = cfg.inbounds.first { it.tag == "socks-in" }
        val httpIn = cfg.inbounds.first { it.tag == "http-in" }

        assertEquals("password", socksIn.settings?.auth)
        assertEquals("password", httpIn.settings?.auth)
        assertNotNull(httpIn.settings?.accounts)
        assertEquals("u_local", httpIn.settings?.accounts?.first()?.user)
        assertEquals("secret_password", httpIn.settings?.accounts?.first()?.pass)
    }

    @Test
    fun httpInboundLeavesNoAuthWhenCredentialsOmittedForTun() {
        val cfg = XrayConfigGenerator.buildConfig(
            sampleProfile(),
            XrayGeneratorOptions(
                socksPort = 10808,
                httpPort = 10809,
                socksUsername = null,
                socksPassword = null
            )
        )
        val socksIn = cfg.inbounds.first { it.tag == "socks-in" }
        val httpIn = cfg.inbounds.first { it.tag == "http-in" }

        assertEquals("noauth", socksIn.settings?.auth)
        assertNull(httpIn.settings)
    }
}
