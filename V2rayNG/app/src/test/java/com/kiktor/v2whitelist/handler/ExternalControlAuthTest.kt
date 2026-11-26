package com.kiktor.v2whitelist.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExternalControlAuthTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `issue persists sha256 digest and verifies issued token only`() {
        val hashFile = File(tempFolder.root, "external-control.sha256")
        assertFalse(ExternalControlAuth.hasTokenFile(hashFile))

        val token = ExternalControlAuth.issueToFile(hashFile)
        assertEquals(64, token.length)
        assertTrue(ExternalControlAuth.hasTokenFile(hashFile))

        // На диске хранится именно SHA-256, а не открытый токен
        val storedDigest = hashFile.readText().trim()
        assertEquals(64, storedDigest.length)
        assertNotEquals(token, storedDigest)
        assertEquals(ExternalControlAuth.sha256Hex(token), storedDigest)

        assertTrue(ExternalControlAuth.verifyAgainstFile(hashFile, token))
        assertTrue(ExternalControlAuth.verifyAgainstFile(hashFile, "  $token\n"))
        assertFalse(ExternalControlAuth.verifyAgainstFile(hashFile, null))
        assertFalse(ExternalControlAuth.verifyAgainstFile(hashFile, ""))
        assertFalse(ExternalControlAuth.verifyAgainstFile(hashFile, token.reversed()))
        assertFalse(ExternalControlAuth.verifyAgainstFile(hashFile, token.dropLast(1) + if (token.last() == 'a') "b" else "a"))
    }

    @Test
    fun `revoke removes persisted hash and invalidates existing token`() {
        val hashFile = File(tempFolder.root, "external-control.sha256")
        val token = ExternalControlAuth.issueToFile(hashFile)
        assertTrue(ExternalControlAuth.verifyAgainstFile(hashFile, token))

        ExternalControlAuth.revokeFile(hashFile)
        assertFalse(ExternalControlAuth.hasTokenFile(hashFile))
        assertFalse(ExternalControlAuth.verifyAgainstFile(hashFile, token))
    }

    @Test
    fun `reissue invalidates previous token`() {
        val hashFile = File(tempFolder.root, "external-control.sha256")
        val first = ExternalControlAuth.issueToFile(hashFile)
        val second = ExternalControlAuth.issueToFile(hashFile)
        assertNotEquals(first, second)

        assertFalse(ExternalControlAuth.verifyAgainstFile(hashFile, first))
        assertTrue(ExternalControlAuth.verifyAgainstFile(hashFile, second))
    }
}
