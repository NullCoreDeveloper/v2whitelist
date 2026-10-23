package com.kiktor.v2whitelist.core.subscription

import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.subscription.endpoint.CustomEndpointManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CustomEndpointManagerTest {

    @Test
    fun testGetDisplayName() {
        val profileWithRemarks = ProfileItem.create(EConfigType.VLESS).apply {
            server = "1.2.3.4"
            serverPort = "443"
            remarks = "Нидерланды Личный"
        }
        assertEquals("Нидерланды Личный", CustomEndpointManager.getDisplayName(profileWithRemarks))

        val profileWithoutRemarks = ProfileItem.create(EConfigType.TROJAN).apply {
            server = "5.6.7.8"
            serverPort = "8443"
            remarks = ""
        }
        assertEquals("TROJAN — 5.6.7.8:8443", CustomEndpointManager.getDisplayName(profileWithoutRemarks))
    }

    @Test
    fun testProfileToUri() {
        val vless = ProfileItem.create(EConfigType.VLESS).apply {
            server = "myserver.com"
            serverPort = "443"
            password = "uuid-1234-5678"
            network = "tcp"
            security = "reality"
            remarks = "Custom-Endpoint"
        }
        val uri = CustomEndpointManager.profileToUri(vless)
        assertNotNull(uri)
        assertTrue(uri.startsWith("vless://"))
        assertTrue(uri.contains("myserver.com:443"))
        assertTrue(uri.contains("Custom-Endpoint"))
    }
}
