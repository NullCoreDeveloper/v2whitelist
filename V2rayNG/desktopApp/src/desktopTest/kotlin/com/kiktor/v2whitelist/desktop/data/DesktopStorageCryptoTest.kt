package com.kiktor.v2whitelist.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopStorageCryptoTest {

    private val fastTestParams = Argon2Params(
        memoryKiB = Argon2Params.MIN_MEMORY_KIB,
        iterations = 1,
        parallelism = 1
    )

    @Test
    fun `encrypt and decrypt roundtrip works with argon2id and aes-gcm`() {
        val secretPayload = """{"servers":[{"guid":"g1","remarks":"Test Node"}]}"""
        val password = "correct-horse-battery-staple".toCharArray()

        val envelope = DesktopStorageCrypto.encrypt(
            plaintext = secretPayload,
            password = password,
            params = fastTestParams,
            contextName = "servers.json"
        )
        assertTrue(DesktopStorageCrypto.isEncryptedContent(envelope))
        assertFalse(envelope.contains("Test Node"))

        val (decrypted, keyCtx) = DesktopStorageCrypto.decrypt(
            envelopeJson = envelope,
            password = "correct-horse-battery-staple".toCharArray(),
            contextName = "servers.json"
        )
        assertEquals(secretPayload, decrypted)

        // Быстрая перешифровка тем же derivedKey
        val updatedPayload = """{"servers":[{"guid":"g2","remarks":"Updated"}]}"""
        val reencrypted = DesktopStorageCrypto.encryptWithDerivedKey(
            plaintext = updatedPayload,
            derivedKey = keyCtx.derivedKey,
            salt = keyCtx.salt,
            params = keyCtx.params,
            contextName = "servers.json"
        )
        assertEquals(
            updatedPayload,
            DesktopStorageCrypto.decryptWithDerivedKey(reencrypted, keyCtx.derivedKey, "servers.json")
        )
        keyCtx.wipe()
    }

    @Test
    fun `decrypt rejects wrong password, wrong context AAD, and tampered ciphertext`() {
        val envelope = DesktopStorageCrypto.encrypt(
            plaintext = """{"socksPort":10808}""",
            password = "master-password".toCharArray(),
            params = fastTestParams,
            contextName = "settings.json"
        )

        assertFailsWith<IllegalArgumentException> {
            DesktopStorageCrypto.decrypt(envelope, "wrong-password".toCharArray(), "settings.json")
        }

        // Подмена контекста (файл settings.json пытаются подсунуть как servers.json)
        assertFailsWith<IllegalArgumentException> {
            DesktopStorageCrypto.decrypt(envelope, "master-password".toCharArray(), "servers.json")
        }

        // Подмена параметра KDF (несовпадение AAD)
        val tamperedParam = envelope.replace("\"iterations\":1", "\"iterations\":2")
        assertFailsWith<IllegalArgumentException> {
            DesktopStorageCrypto.decrypt(tamperedParam, "master-password".toCharArray(), "settings.json")
        }
    }

    @Test
    fun `Argon2Params validated clamps out of bounds values`() {
        val clampedLow = Argon2Params(memoryKiB = 100, iterations = 0, parallelism = 0).validated()
        assertEquals(Argon2Params.MIN_MEMORY_KIB, clampedLow.memoryKiB)
        assertEquals(Argon2Params.MIN_ITERATIONS, clampedLow.iterations)
        assertEquals(Argon2Params.MIN_PARALLELISM, clampedLow.parallelism)

        val clampedHigh = Argon2Params(memoryKiB = 9999999, iterations = 99, parallelism = 64).validated()
        assertEquals(Argon2Params.MAX_MEMORY_KIB, clampedHigh.memoryKiB)
        assertEquals(Argon2Params.MAX_ITERATIONS, clampedHigh.iterations)
        assertEquals(Argon2Params.MAX_PARALLELISM, clampedHigh.parallelism)
    }
}
