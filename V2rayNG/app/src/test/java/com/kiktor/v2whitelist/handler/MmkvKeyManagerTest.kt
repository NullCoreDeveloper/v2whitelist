package com.kiktor.v2whitelist.handler

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

class MmkvKeyManagerTest {

    @Test
    fun `generateMmkvCryptKey produces 16 ASCII bytes and unique keys`() {
        val k1 = MmkvKeyManager.generateMmkvCryptKey()
        val k2 = MmkvKeyManager.generateMmkvCryptKey()
        assertEquals(16, k1.toByteArray(Charsets.US_ASCII).size)
        assertEquals(16, k2.toByteArray(Charsets.US_ASCII).size)
        assertNotEquals(k1, k2)
    }

    @Test
    fun `wrapKey and unwrapKey roundtrip succeeds and detects tampering`() {
        val rawMaster = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val masterKey = SecretKeySpec(rawMaster, "AES")
        val rawMmkvKey = MmkvKeyManager.generateMmkvCryptKey().toByteArray(Charsets.US_ASCII)
        val aad = "test-aad".toByteArray(Charsets.UTF_8)

        val wrapped = MmkvKeyManager.wrapKey(masterKey, rawMmkvKey, aad)
        val unwrapped = MmkvKeyManager.unwrapKey(masterKey, wrapped, aad)
        assertArrayEquals(rawMmkvKey, unwrapped)

        // Подмена AAD должна приводить к ошибке аутентификации GCM
        var failed = false
        try {
            MmkvKeyManager.unwrapKey(masterKey, wrapped, "wrong-aad".toByteArray(Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            failed = true
        }
        assertEquals(true, failed)

        // Повреждение шифротекста должно отклоняться
        val tampered = wrapped.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0xFF).toByte()
        var tamperFailed = false
        try {
            MmkvKeyManager.unwrapKey(masterKey, tampered, aad)
        } catch (_: GeneralSecurityException) {
            tamperFailed = true
        }
        assertEquals(true, tamperFailed)

        // Другой мастер-ключ не должен расшифровывать
        val otherMaster = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        var wrongKeyFailed = false
        try {
            MmkvKeyManager.unwrapKey(otherMaster, wrapped, aad)
        } catch (_: GeneralSecurityException) {
            wrongKeyFailed = true
        }
        assertEquals(true, wrongKeyFailed)
    }
}
