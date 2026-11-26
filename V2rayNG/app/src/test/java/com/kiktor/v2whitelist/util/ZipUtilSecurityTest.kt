package com.kiktor.v2whitelist.util

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipUtilSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun unzipRejectsZipSlipTraversal() {
        val zipFile = tempFolder.newFile("evil.zip")
        val destDir = tempFolder.newFolder("extract")
        val evilTarget = File(destDir.parentFile, "escaped.txt")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("../escaped.txt"))
            zos.write("pwned".toByteArray())
            zos.closeEntry()
        }

        Mockito.mockStatic(Log::class.java).use {
            val result = ZipUtil.unzipToFolder(zipFile, destDir.absolutePath)
            assertFalse(result)
        }
        assertFalse(evilTarget.exists())
    }

    @Test
    fun unzipRejectsTooManyEntries() {
        val zipFile = tempFolder.newFile("many.zip")
        val destDir = tempFolder.newFolder("extract_many")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for (i in 0..ZipUtil.MAX_UNZIP_ENTRIES) {
                zos.putNextEntry(ZipEntry("file_$i.txt"))
                zos.write("x".toByteArray())
                zos.closeEntry()
            }
        }

        Mockito.mockStatic(Log::class.java).use {
            val result = ZipUtil.unzipToFolder(zipFile, destDir.absolutePath)
            assertFalse(result)
        }
    }

    @Test
    fun unzipExtractsValidNestedArchive() {
        val zipFile = tempFolder.newFile("valid.zip")
        val destDir = tempFolder.newFolder("extract_ok")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("nested/config.json"))
            zos.write("""{"ok":true}""".toByteArray())
            zos.closeEntry()
        }

        val result = ZipUtil.unzipToFolder(zipFile, destDir.absolutePath)
        assertTrue(result)
        val extracted = File(destDir, "nested/config.json")
        assertTrue(extracted.exists())
        assertEquals("""{"ok":true}""", extracted.readText())
    }
}
