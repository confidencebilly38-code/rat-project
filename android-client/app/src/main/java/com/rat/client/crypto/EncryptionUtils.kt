package com.rat.client.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rat.client.RatApplication
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class EncryptionUtils private constructor() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "EncryptionUtils"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rat_master_key"
        private const val AES_KEY_SIZE = 256
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 16
        private const val PBKDF2_ITERATIONS = 100000
        private const val SALT_LENGTH = 32
        private const val RSA_KEY_SIZE = 2048
        private const val RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val HMAC_ALGORITHM = "HmacSHA256"

        private var masterKey: SecretKey? = null
        private var initialized = false

        
        fun init(context: Context) {
            if (initialized) return
            try {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).apply {
                    setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    setKeySize(AES_KEY_SIZE)
                    setUserAuthenticationRequired(false)
                }.build()
                keyGen.init(spec)
                masterKey = keyGen.generateKey()
                initialized = true
            } catch (e: Exception) {
                // Fallback to derived key
                masterKey = deriveKeyFromPassword(context)
            }
        }

        private fun deriveKeyFromPassword(context: Context): SecretKey {
            val password = "RAT_MASTER_KEY_${context.packageName}_${BuildConfig.VERSION_CODE}"
            val salt = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_SIZE)
            val secret = factory.generateSecret(spec)
            return SecretKeySpec(secret.encoded, "AES")
        }

        // ===== Symmetric Encryption (AES-GCM) =====

        
        fun encrypt(plaintext: String): String {
            return encrypt(plaintext.toByteArray(StandardCharsets.UTF_8))
        }

        
        fun encrypt(plaintext: ByteArray): String {
            val key = masterKey ?: throw IllegalStateException("Encryption not initialized")
            val iv = generateIv()
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)

            val ciphertext = cipher.doFinal(plaintext)

            // Combine IV + ciphertext
            val result = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, result, 0, iv.size)
            System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)

            return Base64.encodeToString(result, Base64.NO_WRAP)
        }

        
        fun decrypt(ciphertextB64: String): String {
            val decrypted = decrypt(Base64.decode(ciphertextB64, Base64.NO_WRAP))
            return String(decrypted, StandardCharsets.UTF_8)
        }

        
        fun decrypt(ciphertext: ByteArray): ByteArray {
            val key = masterKey ?: throw IllegalStateException("Encryption not initialized")
            if (ciphertext.size < GCM_IV_LENGTH + GCM_TAG_LENGTH) {
                throw IllegalArgumentException("Invalid ciphertext")
            }

            val iv = ciphertext.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = ciphertext.copyOfRange(GCM_IV_LENGTH, ciphertext.size)

            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            return cipher.doFinal(encrypted)
        }

        private fun generateIv(): ByteArray {
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)
            return iv
        }

        // ===== Asymmetric Encryption (RSA) =====

        
        fun generateKeyPair(): KeyPair {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(RSA_KEY_SIZE)
            return kpg.generateKeyPair()
        }

        
        fun encryptRSA(plaintext: String, publicKey: String): String {
            val key = decodePublicKey(publicKey)
            val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            return Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }

        
        fun decryptRSA(ciphertextB64: String, privateKey: String): String {
            val key = decodePrivateKey(privateKey)
            val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key)
            val decrypted = cipher.doFinal(Base64.decode(ciphertextB64, Base64.NO_WRAP))
            return String(decrypted, StandardCharsets.UTF_8)
        }

        
        fun publicKeyToString(publicKey: java.security.PublicKey): String {
            return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        }

        
        fun privateKeyToString(privateKey: java.security.PrivateKey): String {
            return Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP)
        }

        private fun decodePublicKey(keyString: String): java.security.PublicKey {
            val encoded = Base64.decode(keyString, Base64.NO_WRAP)
            val spec = X509EncodedKeySpec(encoded)
            val kf = KeyFactory.getInstance("RSA")
            return kf.generatePublic(spec)
        }

        private fun decodePrivateKey(keyString: String): java.security.PrivateKey {
            val encoded = Base64.decode(keyString, Base64.NO_WRAP)
            val spec = PKCS8EncodedKeySpec(encoded)
            val kf = KeyFactory.getInstance("RSA")
            return kf.generatePrivate(spec)
        }

        // ===== Hashing =====

        
        fun sha256(data: String): String {
            return sha256(data.toByteArray(StandardCharsets.UTF_8))
        }

        
        fun sha256(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(data)
            return hash.joinToString("") { "%02x".format(it) }
        }

        
        fun sha512(data: String): String {
            val digest = MessageDigest.getInstance("SHA-512")
            val hash = digest.digest(data.toByteArray(StandardCharsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }

        
        fun md5(data: String): String {
            val digest = MessageDigest.getInstance("MD5")
            val hash = digest.digest(data.toByteArray(StandardCharsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }

        // ===== HMAC =====

        
        fun hmacSha256(data: String, key: String): String {
            return hmacSha256(data.toByteArray(StandardCharsets.UTF_8), key.toByteArray(StandardCharsets.UTF_8))
        }

        
        fun hmacSha256(data: ByteArray, key: ByteArray): String {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            val secretKey = SecretKeySpec(key, HMAC_ALGORITHM)
            mac.init(secretKey)
            val hmac = mac.doFinal(data)
            return hmac.joinToString("") { "%02x".format(it) }
        }

        // ===== Secure SharedPreferences =====

        
        fun getEncryptedPrefs(context: Context): androidx.security.crypto.EncryptedSharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                "rat_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        // ===== File Encryption =====

        
        fun encryptFile(inputFile: java.io.File, outputFile: java.io.File): Boolean {
            return try {
                val key = masterKey ?: throw IllegalStateException("Encryption not initialized")
                val iv = generateIv()
                val cipher = Cipher.getInstance(AES_TRANSFORMATION)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
                cipher.init(Cipher.ENCRYPT_MODE, key, spec)

                val inputStream = java.io.FileInputStream(inputFile)
                val outputStream = java.io.FileOutputStream(outputFile)

                // Write IV first
                outputStream.write(iv)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                val cipherOutputStream = javax.crypto.CipherOutputStream(outputStream, cipher)
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    cipherOutputStream.write(buffer, 0, bytesRead)
                }
                cipherOutputStream.flush()
                cipherOutputStream.close()
                inputStream.close()
                true
            } catch (e: Exception) {
                false
            }
        }

        
        fun decryptFile(inputFile: java.io.File, outputFile: java.io.File): Boolean {
            return try {
                val key = masterKey ?: throw IllegalStateException("Encryption not initialized")
                val inputStream = java.io.FileInputStream(inputFile)
                val outputStream = java.io.FileOutputStream(outputFile)

                // Read IV
                val iv = ByteArray(GCM_IV_LENGTH)
                inputStream.read(iv)

                val cipher = Cipher.getInstance(AES_TRANSFORMATION)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
                cipher.init(Cipher.DECRYPT_MODE, key, spec)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                val cipherInputStream = javax.crypto.CipherInputStream(inputStream, cipher)
                while (cipherInputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.flush()
                outputStream.close()
                cipherInputStream.close()
                true
            } catch (e: Exception) {
                false
            }
        }

        // ===== Random Generation =====

        
        fun generateRandomBytes(length: Int): ByteArray {
            val bytes = ByteArray(length)
            SecureRandom().nextBytes(bytes)
            return bytes
        }

        
        fun generateRandomString(length: Int): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val random = SecureRandom()
            return (0 until length).map { chars[random.nextInt(chars.length)] }.joinToString("")
        }

        
        fun generateToken(): String {
            val bytes = generateRandomBytes(32)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
        }

        // ===== Data Integrity =====

        
        fun signData(data: String, privateKey: String): String {
            return signData(data.toByteArray(StandardCharsets.UTF_8), privateKey)
        }

        
        fun signData(data: ByteArray, privateKey: String): String {
            val key = decodePrivateKey(privateKey)
            val signature = java.security.Signature.getInstance("SHA256withRSA")
            signature.initSign(key)
            signature.update(data)
            val signed = signature.sign()
            return Base64.encodeToString(signed, Base64.NO_WRAP)
        }

        
        fun verifySignature(data: String, signatureB64: String, publicKey: String): Boolean {
            return verifySignature(data.toByteArray(StandardCharsets.UTF_8), signatureB64, publicKey)
        }

        
        fun verifySignature(data: ByteArray, signatureB64: String, publicKey: String): Boolean {
            try {
                val key = decodePublicKey(publicKey)
                val signature = java.security.Signature.getInstance("SHA256withRSA")
                signature.initVerify(key)
                signature.update(data)
                return signature.verify(Base64.decode(signatureB64, Base64.NO_WRAP))
            } catch (e: Exception) {
                return false
            }
        }

        data class KeyPair(
            val publicKey: java.security.PublicKey,
            val privateKey: java.security.PrivateKey
        )
    }
}