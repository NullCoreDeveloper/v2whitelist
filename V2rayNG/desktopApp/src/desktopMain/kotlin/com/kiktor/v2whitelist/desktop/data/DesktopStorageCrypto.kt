package com.kiktor.v2whitelist.desktop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class Argon2Params(
    val memoryKiB: Int = 65536,
    val iterations: Int = 3,
    val parallelism: Int = 1
) {
    fun validated(): Argon2Params = Argon2Params(
        memoryKiB = memoryKiB.coerceIn(MIN_MEMORY_KIB, MAX_MEMORY_KIB),
        iterations = iterations.coerceIn(MIN_ITERATIONS, MAX_ITERATIONS),
        parallelism = parallelism.coerceIn(MIN_PARALLELISM, MAX_PARALLELISM)
    )

    companion object {
        const val MIN_MEMORY_KIB = 16384
        const val MAX_MEMORY_KIB = 524288
        const val MIN_ITERATIONS = 1
        const val MAX_ITERATIONS = 10
        const val MIN_PARALLELISM = 1
        const val MAX_PARALLELISM = 4

        val FAST = Argon2Params(memoryKiB = 32768, iterations = 2, parallelism = 1)
        val BALANCED = Argon2Params(memoryKiB = 65536, iterations = 3, parallelism = 1)
        val STRONG = Argon2Params(memoryKiB = 131072, iterations = 4, parallelism = 2)
    }
}

@Serializable
data class EncryptedEnvelope(
    val magic: String = MAGIC,
    val version: Int = 1,
    val kdf: String = "argon2id",
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val saltBase64: String,
    val nonceBase64: String,
    val ciphertextBase64: String
) {
    companion object {
        const val MAGIC = "V2WENC1"
    }
}

/**
 * Криптографический движок для опционального шифрования локальных файлов Desktop-версии
 * с помощью Argon2id (RFC 9106) и AES-256-GCM.
 */
object DesktopStorageCrypto {

    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val KEY_LEN = 32
    private const val GCM_TAG_BITS = 128

    private val secureRandom = SecureRandom()
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    class DecryptedKeyContext(
        val derivedKey: ByteArray,
        val salt: ByteArray,
        val params: Argon2Params
    ) {
        fun wipe() {
            derivedKey.fill(0)
        }
    }

    fun isEncryptedContent(rawText: String): Boolean {
        val trimmed = rawText.trim()
        if (!trimmed.startsWith("{") || !trimmed.contains(EncryptedEnvelope.MAGIC)) return false
        return runCatching {
            val env = json.decodeFromString<EncryptedEnvelope>(trimmed)
            env.magic == EncryptedEnvelope.MAGIC && env.kdf == "argon2id"
        }.getOrDefault(false)
    }

    fun generateSalt(): ByteArray = ByteArray(SALT_LEN).also { secureRandom.nextBytes(it) }

    fun deriveKey(password: CharArray, salt: ByteArray, params: Argon2Params): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)

        val gen = Argon2BytesGenerator()
        gen.init(builder.build())
        val out = ByteArray(KEY_LEN)
        val pwdBytes = java.nio.CharBuffer.wrap(password)
        val encoded = Charsets.UTF_8.encode(pwdBytes)
        val pwdArray = ByteArray(encoded.remaining()).apply { encoded.get(this) }
        try {
            gen.generateBytes(pwdArray, out, 0, out.size)
        } finally {
            pwdArray.fill(0)
            if (encoded.hasArray()) encoded.array().fill(0)
        }
        return out
    }

    fun encrypt(
        plaintext: String,
        password: CharArray,
        params: Argon2Params,
        contextName: String
    ): String {
        val safeParams = params.validated()
        val salt = generateSalt()
        val key = deriveKey(password, salt, safeParams)
        try {
            return encryptWithDerivedKey(plaintext, key, salt, safeParams, contextName)
        } finally {
            key.fill(0)
        }
    }

    fun encryptWithDerivedKey(
        plaintext: String,
        derivedKey: ByteArray,
        salt: ByteArray,
        params: Argon2Params,
        contextName: String
    ): String {
        val safeParams = params.validated()
        val nonce = ByteArray(NONCE_LEN).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(derivedKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        cipher.updateAAD(buildAad(contextName, safeParams))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val envelope = EncryptedEnvelope(
            memoryKiB = safeParams.memoryKiB,
            iterations = safeParams.iterations,
            parallelism = safeParams.parallelism,
            saltBase64 = Base64.getEncoder().encodeToString(salt),
            nonceBase64 = Base64.getEncoder().encodeToString(nonce),
            ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext)
        )
        return json.encodeToString(envelope)
    }

    fun decrypt(
        envelopeJson: String,
        password: CharArray,
        contextName: String
    ): Pair<String, DecryptedKeyContext> {
        val envelope = parseAndValidateEnvelope(envelopeJson)
        val salt = Base64.getDecoder().decode(envelope.saltBase64)
        val params = Argon2Params(
            memoryKiB = envelope.memoryKiB,
            iterations = envelope.iterations,
            parallelism = envelope.parallelism
        )
        val derivedKey = deriveKey(password, salt, params)
        try {
            val plaintext = decryptEnvelopeWithKey(envelope, derivedKey, contextName)
            return plaintext to DecryptedKeyContext(derivedKey, salt, params)
        } catch (e: GeneralSecurityException) {
            derivedKey.fill(0)
            throw IllegalArgumentException("Неверный мастер-пароль или повреждённые данные", e)
        } catch (t: Throwable) {
            derivedKey.fill(0)
            throw t
        }
    }

    fun decryptWithDerivedKey(
        envelopeJson: String,
        derivedKey: ByteArray,
        contextName: String
    ): String {
        val envelope = parseAndValidateEnvelope(envelopeJson)
        return try {
            decryptEnvelopeWithKey(envelope, derivedKey, contextName)
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("Не удалось расшифровать хранилище ($contextName)", e)
        }
    }

    private fun parseAndValidateEnvelope(envelopeJson: String): EncryptedEnvelope {
        val env = json.decodeFromString<EncryptedEnvelope>(envelopeJson.trim())
        require(env.magic == EncryptedEnvelope.MAGIC) { "Unsupported envelope magic" }
        require(env.version == 1) { "Unsupported envelope version" }
        require(env.kdf == "argon2id") { "Unsupported KDF: ${env.kdf}" }
        require(env.memoryKiB in Argon2Params.MIN_MEMORY_KIB..Argon2Params.MAX_MEMORY_KIB) {
            "Unsafe Argon2 memory parameter: ${env.memoryKiB}"
        }
        require(env.iterations in 1..20) { "Unsafe Argon2 iterations: ${env.iterations}" }
        require(env.parallelism in 1..8) { "Unsafe Argon2 parallelism: ${env.parallelism}" }
        return env
    }

    private fun decryptEnvelopeWithKey(
        envelope: EncryptedEnvelope,
        derivedKey: ByteArray,
        contextName: String
    ): String {
        val nonce = Base64.getDecoder().decode(envelope.nonceBase64)
        require(nonce.size == NONCE_LEN) { "Invalid GCM nonce length" }
        val ciphertext = Base64.getDecoder().decode(envelope.ciphertextBase64)
        val params = Argon2Params(
            memoryKiB = envelope.memoryKiB,
            iterations = envelope.iterations,
            parallelism = envelope.parallelism
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(derivedKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        cipher.updateAAD(buildAad(contextName, params))
        val plainBytes = cipher.doFinal(ciphertext)
        return String(plainBytes, Charsets.UTF_8)
    }

    private fun buildAad(contextName: String, params: Argon2Params): ByteArray =
        "${EncryptedEnvelope.MAGIC}|$contextName|${params.memoryKiB}|${params.iterations}|${params.parallelism}"
            .toByteArray(Charsets.UTF_8)
}
