package com.kiktor.v2whitelist.handler

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.tencent.mmkv.MMKV
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Управляет ключом шифрования MMKV, защищённым через AndroidKeyStore (AES-256-GCM),
 * и безопасной однократной миграцией существующих открытых MMKV-хранилищ.
 */
object MmkvKeyManager {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "probel_mmkv_master_key"
    private const val KEY_FILE_NAME = "mmkv_key.enc"
    private const val MIGRATION_MARKER_NAME = "mmkv_migrated.v1"
    private const val LOCK_FILE_NAME = ".mmkv_key.lock"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val MMKV_CRYPT_KEY_LEN = 16
    private const val KEY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
    private val WRAP_AAD = "probel-mmkv-wrap-v1".toByteArray(Charsets.UTF_8)

    private val secureRandom = SecureRandom()

    fun getOrCreateCryptKey(context: Context): String? {
        val appContext = context.applicationContext
        val baseDir = appContext.noBackupFilesDir
        if (!baseDir.exists()) baseDir.mkdirs()

        return try {
            withInterprocessLock(baseDir) {
                val keyFile = File(baseDir, KEY_FILE_NAME)
                val masterKey = getOrCreateMasterKey()
                if (keyFile.isFile) {
                    val wrapped = keyFile.readBytes()
                    val rawKey = unwrapKey(masterKey, wrapped, WRAP_AAD)
                    require(rawKey.size == MMKV_CRYPT_KEY_LEN) { "Invalid MMKV cryptKey length" }
                    String(rawKey, Charsets.US_ASCII)
                } else {
                    val cryptKey = generateMmkvCryptKey()
                    val wrapped = wrapKey(masterKey, cryptKey.toByteArray(Charsets.US_ASCII), WRAP_AAD)
                    writeAtomicPrivateBytes(keyFile, wrapped)
                    cryptKey
                }
            }
        } catch (t: Throwable) {
            Log.e(AppConfig.TAG, "MmkvKeyManager: Keystore unavailable, fallback to unencrypted MMKV", t)
            null
        }
    }

    fun migratePlainStoragesIfNeeded(context: Context, cryptKey: String, storageIds: List<String>) {
        val appContext = context.applicationContext
        val baseDir = appContext.noBackupFilesDir
        if (!baseDir.exists()) baseDir.mkdirs()
        val markerFile = File(baseDir, MIGRATION_MARKER_NAME)
        if (markerFile.exists()) return

        try {
            withInterprocessLock(baseDir) {
                if (markerFile.exists()) return@withInterprocessLock
                for (id in storageIds) {
                    runCatching {
                        val plainKv = MMKV.mmkvWithID(id, MMKV.MULTI_PROCESS_MODE, null)
                        if (plainKv.totalSize() > 0L || (plainKv.allKeys()?.isNotEmpty() == true)) {
                            plainKv.reKey(cryptKey)
                            plainKv.sync()
                        } else {
                            // Если хранилище ещё пустое, сразу инициализируем его с ключом
                            MMKV.mmkvWithID(id, MMKV.MULTI_PROCESS_MODE, cryptKey)
                        }
                    }.onFailure { e ->
                        Log.w(AppConfig.TAG, "MmkvKeyManager: failed migrating storage $id: ${e.message}")
                    }
                }
                writeAtomicPrivateBytes(markerFile, "1".toByteArray(Charsets.US_ASCII))
            }
        } catch (t: Throwable) {
            Log.e(AppConfig.TAG, "MmkvKeyManager: migration lock failed", t)
        }
    }

    internal fun generateMmkvCryptKey(): String {
        val chars = CharArray(MMKV_CRYPT_KEY_LEN)
        for (i in chars.indices) {
            chars[i] = KEY_ALPHABET[secureRandom.nextInt(KEY_ALPHABET.length)]
        }
        return String(chars)
    }

    internal fun wrapKey(masterKey: SecretKey, rawKey: ByteArray, aad: ByteArray = WRAP_AAD): ByteArray {
        val iv = ByteArray(GCM_IV_LEN)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(rawKey)
        return iv + encrypted
    }

    internal fun unwrapKey(masterKey: SecretKey, wrapped: ByteArray, aad: ByteArray = WRAP_AAD): ByteArray {
        val minSize = GCM_IV_LEN + (GCM_TAG_BITS / 8) + 1
        require(wrapped.size >= minSize) { "Wrapped key payload is too short" }
        val iv = wrapped.copyOfRange(0, GCM_IV_LEN)
        val ciphertext = wrapped.copyOfRange(GCM_IV_LEN, wrapped.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private inline fun <T> withInterprocessLock(dir: File, block: () -> T): T {
        val lockFile = File(dir, LOCK_FILE_NAME)
        RandomAccessFile(lockFile, "rw").use { raf ->
            raf.channel.lock().use {
                return block()
            }
        }
    }

    private fun writeAtomicPrivateBytes(target: File, data: ByteArray) {
        val parent = target.parentFile ?: File(".")
        if (!parent.exists()) parent.mkdirs()
        val tmp = File(parent, "${target.name}.tmp")
        tmp.writeBytes(data)
        runCatching {
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(false, false)
            tmp.setWritable(true, true)
        }
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            if (!tmp.renameTo(target)) {
                target.writeBytes(data)
                runCatching { tmp.delete() }
            }
        }
    }
}
