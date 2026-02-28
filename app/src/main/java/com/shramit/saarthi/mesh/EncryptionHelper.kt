package com.shramit.saarthi.mesh

import android.util.Log
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption helper for SOS packets.
 * Uses X25519 key exchange + AES-256-GCM (same as BitChat).
 *
 * For the hackathon MVP, encryption is available but optional.
 * SOS packets work without encryption to ensure maximum reliability.
 */
object EncryptionHelper {

    private const val TAG = "EncryptionHelper"
    private const val AES_GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    private var privateKey: X25519PrivateKeyParameters? = null
    private var publicKey: X25519PublicKeyParameters? = null

    /**
     * Generate a new X25519 key pair for this session.
     */
    fun generateKeyPair() {
        try {
            val generator = X25519KeyPairGenerator()
            generator.init(X25519KeyGenerationParameters(SecureRandom()))
            val keyPair = generator.generateKeyPair()

            privateKey = keyPair.private as X25519PrivateKeyParameters
            publicKey = keyPair.public as X25519PublicKeyParameters

            Log.d(TAG, "Generated X25519 key pair")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate key pair: ${e.message}")
        }
    }

    /**
     * Get our public key bytes (to share with peers).
     */
    fun getPublicKeyBytes(): ByteArray? {
        return publicKey?.encoded
    }

    /**
     * Perform X25519 key agreement with a peer's public key to derive a shared secret.
     */
    fun deriveSharedSecret(peerPublicKeyBytes: ByteArray): ByteArray? {
        return try {
            val peerPublicKey = X25519PublicKeyParameters(peerPublicKeyBytes, 0)
            val agreement = X25519Agreement()
            agreement.init(privateKey)

            val secret = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(peerPublicKey, secret, 0)

            secret
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive shared secret: ${e.message}")
            null
        }
    }

    /**
     * Encrypt data using AES-256-GCM with the given key.
     */
    fun encrypt(data: ByteArray, key: ByteArray): ByteArray? {
        return try {
            val random = SecureRandom()
            val iv = ByteArray(GCM_IV_LENGTH)
            random.nextBytes(iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(key.copyOf(32), "AES")  // First 32 bytes of key
            val gcmSpec = GCMParameterSpec(AES_GCM_TAG_LENGTH, iv)

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            val encrypted = cipher.doFinal(data)

            // Prepend IV to encrypted data: [12B IV][encrypted data + tag]
            iv + encrypted
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed: ${e.message}")
            null
        }
    }

    /**
     * Decrypt data using AES-256-GCM with the given key.
     */
    fun decrypt(encryptedData: ByteArray, key: ByteArray): ByteArray? {
        return try {
            if (encryptedData.size < GCM_IV_LENGTH) return null

            val iv = encryptedData.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = encryptedData.copyOfRange(GCM_IV_LENGTH, encryptedData.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(key.copyOf(32), "AES")
            val gcmSpec = GCMParameterSpec(AES_GCM_TAG_LENGTH, iv)

            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed: ${e.message}")
            null
        }
    }

    /**
     * Simple hash for quick packet verification.
     */
    fun quickHash(data: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }
}
