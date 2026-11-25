package com.kiktor.v2whitelist.desktop

import com.kiktor.v2whitelist.desktop.engine.DesktopGeoAssetsUpdater
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopGeoAssetsUpdaterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testIsUpdateDueRespectsIntervalAndDisabled() {
        val dayMs = 24L * 60L * 60L * 1000L
        val now = 1_750_000_000_000L

        assertFalse(DesktopGeoAssetsUpdater.isUpdateDue(intervalDays = 0, lastUpdatedMs = 0L, nowMs = now))
        assertTrue(DesktopGeoAssetsUpdater.isUpdateDue(intervalDays = 7, lastUpdatedMs = 0L, nowMs = now))
        assertFalse(DesktopGeoAssetsUpdater.isUpdateDue(intervalDays = 7, lastUpdatedMs = now - 3 * dayMs, nowMs = now))
        assertTrue(DesktopGeoAssetsUpdater.isUpdateDue(intervalDays = 7, lastUpdatedMs = now - 8 * dayMs, nowMs = now))
    }

    @Test
    fun testIsValidGeoFileRejectsHtmlAndSmallFiles() {
        val smallFile = tempFolder.newFile("small.dat")
        smallFile.writeBytes(ByteArray(1024))
        assertFalse(DesktopGeoAssetsUpdater.isValidGeoFile(smallFile))

        val htmlFile = tempFolder.newFile("blocked.dat")
        val html = "<!DOCTYPE html><html><body>Access denied</body></html>" + " ".repeat(120_000)
        htmlFile.writeText(html)
        assertFalse(DesktopGeoAssetsUpdater.isValidGeoFile(htmlFile))

        val validFile = tempFolder.newFile("valid.dat")
        validFile.writeBytes(ByteArray(120_000) { 0x0A })
        assertTrue(DesktopGeoAssetsUpdater.isValidGeoFile(validFile))
    }
}
