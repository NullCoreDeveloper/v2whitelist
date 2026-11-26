package com.kiktor.v2whitelist.handler

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Управляет криптографическим токеном авторизации для внешнего управления (Tasker / Broadcast).
 * На устройстве хранится только SHA-256 дайджест токена в закрытом каталоге noBackupFilesDir,
 * сам секретный токен выдаётся только в конфигурационный Bundle при настройке внешнего триггера.
 */
object ExternalControlAuth {

    private const val TOKEN_HASH_FILE_NAME = "external-control.sha256"
    private const val TOKEN_BYTES = 32
    private val secureRandom = SecureRandom()

    fun tokenHashFile(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, TOKEN_HASH_FILE_NAME)

    fun issue(context: Context): String = issueToFile(tokenHashFile(context))

    fun hasToken(context: Context): Boolean = hasTokenFile(tokenHashFile(context))

    fun verify(context: Context, candidate: String?): Boolean =
        verifyAgainstFile(tokenHashFile(context), candidate)

    fun revoke(context: Context) {
        revokeFile(tokenHashFile(context))
    }

    internal fun issueToFile(hashFile: File): String {
        val raw = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(raw)
        val token = toHex(raw)
        val digestHex = sha256Hex(token)
        writeAtomicPrivateFile(hashFile, digestHex)
        return token
    }

    internal fun hasTokenFile(hashFile: File): Boolean =
        hashFile.isFile && runCatching { hashFile.readText().trim().length == 64 }.getOrDefault(false)

    internal fun verifyAgainstFile(hashFile: File, candidate: String?): Boolean {
        val trimmed = candidate?.trim().orEmpty()
        if (trimmed.isEmpty() || !hashFile.isFile) return false
        val expectedHex = runCatching { hashFile.readText().trim() }.getOrNull() ?: return false
        if (expectedHex.length != 64) return false
        val actualHex = sha256Hex(trimmed)
        return MessageDigest.isEqual(
            expectedHex.toByteArray(Charsets.US_ASCII),
            actualHex.toByteArray(Charsets.US_ASCII)
        )
    }

    internal fun revokeFile(hashFile: File) {
        runCatching { if (hashFile.exists()) hashFile.delete() }
    }

    internal fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return toHex(digest)
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    private fun writeAtomicPrivateFile(target: File, content: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(parent ?: File("."), "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
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
                target.writeText(content, Charsets.UTF_8)
                runCatching { tmp.delete() }
            }
        }
    }
}
