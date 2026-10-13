package com.kiktor.v2whitelist.core.repository

import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRepositoryTest {

    private fun createProfile(remarks: String) = ProfileItem(
        remarks = remarks,
        configType = EConfigType.VLESS,
        server = "1.2.3.4",
        serverPort = "443"
    )

    @Test
    fun testServerCrudAndFlow() {
        val repo = InMemoryServerRepository()
        assertEquals(0, repo.getAllServerGuids().size)
        assertTrue(repo.serversFlow.value.isEmpty())

        // 1. Сохранение
        val p1 = createProfile("Server 1")
        repo.saveServerConfig("guid1", p1)
        assertEquals(1, repo.getAllServerGuids().size)
        assertEquals("Server 1", repo.getServerConfig("guid1")?.remarks)
        assertEquals(1, repo.serversFlow.value.size)
        assertEquals("guid1", repo.getSelectedServerGuid())

        // 2. Добавление второго сервера и выбор
        val p2 = createProfile("Server 2")
        repo.saveServerConfig("guid2", p2)
        assertEquals(2, repo.getAllServerGuids().size)
        repo.setSelectedServerGuid("guid2")
        assertEquals("guid2", repo.getSelectedServerGuid())

        // 3. VIP кэш
        repo.addVipServer("guid2")
        assertTrue(repo.getVipServerGuids().contains("guid2"))
        repo.removeVipServer("guid2")
        assertTrue(repo.getVipServerGuids().isEmpty())

        // 4. Удаление выбранного сервера -> автоматическое переключение
        repo.setSelectedServerGuid("guid1")
        repo.removeServer("guid1")
        assertEquals(1, repo.getAllServerGuids().size)
        assertEquals("guid2", repo.getSelectedServerGuid())

        // 5. Полная очистка
        repo.clearAllServers()
        assertTrue(repo.getAllServerGuids().isEmpty())
        assertNull(repo.getSelectedServerGuid())
    }

    @Test
    fun testSettingsRepository() {
        val settings = InMemorySettingsRepository()
        assertEquals(AppScenario.AUTO, settings.getAppScenario())
        assertEquals(10808, settings.getSocksPort())

        settings.setAppScenario(AppScenario.WHITELIST)
        assertEquals(AppScenario.WHITELIST, settings.getAppScenario())

        settings.setSocksPort(20808)
        assertEquals(20808, settings.getSocksPort())

        settings.setString("custom_key", "custom_val")
        assertEquals("custom_val", settings.getString("custom_key"))
    }
}
