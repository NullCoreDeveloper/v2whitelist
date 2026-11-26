package com.kiktor.v2whitelist.util

import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ZipUtil {
    private const val BUFFER_SIZE = 4096
    const val MAX_UNZIP_ENTRIES = 256
    const val MAX_UNZIP_TOTAL_BYTES = 32L * 1024 * 1024

    /**
     * Zip the contents of a folder.
     *
     * @param folderPath The path to the folder to zip.
     * @param outputZipFilePath The path to the output zip file.
     * @return True if the operation is successful, false otherwise.
     * @throws IOException If an I/O error occurs.
     */
    @Throws(IOException::class)
    fun zipFromFolder(folderPath: String, outputZipFilePath: String): Boolean {
        val buffer = ByteArray(BUFFER_SIZE)

        try {
            if (folderPath.isEmpty() || outputZipFilePath.isEmpty()) {
                return false
            }

            val filesToCompress = ArrayList<String>()
            val directory = File(folderPath)
            if (directory.isDirectory) {
                directory.listFiles()?.forEach {
                    if (it.isFile) {
                        filesToCompress.add(it.absolutePath)
                    }
                }
            }
            if (filesToCompress.isEmpty()) {
                return false
            }

            val zos = ZipOutputStream(FileOutputStream(outputZipFilePath))

            filesToCompress.forEach { file ->
                val ze = ZipEntry(File(file).name)
                zos.putNextEntry(ze)
                val inputStream = FileInputStream(file)
                while (true) {
                    val len = inputStream.read(buffer)
                    if (len <= 0) break
                    zos.write(buffer, 0, len)
                }

                inputStream.close()
            }

            zos.closeEntry()
            zos.close()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to zip folder", e)
            return false
        }
        return true
    }

    /**
     * Unzip the contents of a zip file to a folder.
     *
     * @param zipFile The zip file to unzip.
     * @param destDirectory The destination directory.
     * @return True if the operation is successful, false otherwise.
     * @throws IOException If an I/O error occurs.
     */
    @Throws(IOException::class)
    fun unzipToFolder(zipFile: File, destDirectory: String): Boolean {
        val destDir = File(destDirectory)
        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        val canonicalDest = destDir.canonicalFile
        val canonicalDestPrefix = canonicalDest.path + File.separator
        var entryCount = 0
        var totalBytes = 0L

        try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (++entryCount > MAX_UNZIP_ENTRIES) {
                        throw SecurityException("Too many entries in archive (limit=$MAX_UNZIP_ENTRIES)")
                    }
                    val outFile = File(canonicalDest, entry.name).canonicalFile
                    if (outFile != canonicalDest && !outFile.path.startsWith(canonicalDestPrefix)) {
                        throw SecurityException("Blocked path traversal zip entry: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        if (!outFile.exists() && !outFile.mkdirs()) {
                            throw IOException("Failed to create directory: ${outFile.name}")
                        }
                    } else {
                        outFile.parentFile?.let { parent ->
                            if (!parent.exists() && !parent.mkdirs()) {
                                throw IOException("Failed to create parent directory: ${parent.name}")
                            }
                        }
                        zip.getInputStream(entry).use { input ->
                            totalBytes = extractFileWithLimit(input, outFile, totalBytes)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to unzip file", e)
            return false
        }
        return true
    }

    /**
     * Extract a file from an input stream with cumulative uncompressed size limit.
     */
    @Throws(IOException::class)
    private fun extractFileWithLimit(inputStream: InputStream, destFile: File, currentTotalBytes: Long): Long {
        var total = currentTotalBytes
        BufferedOutputStream(FileOutputStream(destFile)).use { bos ->
            val bytesIn = ByteArray(BUFFER_SIZE)
            var read: Int
            while (inputStream.read(bytesIn).also { read = it } != -1) {
                total += read
                if (total > MAX_UNZIP_TOTAL_BYTES) {
                    runCatching { destFile.delete() }
                    throw SecurityException("Archive exceeds maximum uncompressed size ($MAX_UNZIP_TOTAL_BYTES bytes)")
                }
                bos.write(bytesIn, 0, read)
            }
        }
        return total
    }
}