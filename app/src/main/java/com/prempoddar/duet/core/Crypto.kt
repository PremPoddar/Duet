package com.prempoddar.duet.core

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM with a random nonce: `nonce ‖ ciphertext ‖ tag`. */
object Sealer {
    private const val NONCE = 12
    private val random = SecureRandom()
    private val AD = "duet-v1".toByteArray()

    fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(AD)
        return nonce + c.doFinal(plaintext)
    }

    fun open(key: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < NONCE + 16) return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, NONCE))
            c.updateAAD(AD)
            c.doFinal(sealed, NONCE, sealed.size - NONCE)
        }.getOrNull()
    }
}

/**
 * Same-room joining without typing the code: the guest sends a fresh X25519 public key, the host
 * approves on screen and returns the session code sealed to that key.
 */
object Pairing {
    private val random = SecureRandom()

    class Request {
        private val private = ByteArray(32).also(random::nextBytes)
        val publicKey = ByteArray(32).also { X25519.scalarMultBase(private, 0, it, 0) }

        /** The session code from the host's approval, or null if it wasn't meant for us. */
        fun accept(approval: Approval): SessionCode? {
            val key = shared(private, approval.hostKey) ?: return null
            val plain = Sealer.open(key, approval.sealedCode) ?: return null
            return SessionCode.parse(plain.decodeToString())
        }
    }

    class Approval(val hostKey: ByteArray, val sealedCode: ByteArray)

    fun approve(guestKey: ByteArray, code: SessionCode): Approval {
        val private = ByteArray(32).also(random::nextBytes)
        val public = ByteArray(32).also { X25519.scalarMultBase(private, 0, it, 0) }
        val key = shared(private, guestKey) ?: error("bad guest key")
        return Approval(public, Sealer.seal(key, code.toString().toByteArray()))
    }

    private fun shared(private: ByteArray, public: ByteArray): ByteArray? {
        if (public.size != 32) return null
        val dh = ByteArray(32)
        if (!X25519.calculateAgreement(private, 0, public, 0, dh, 0)) return null
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(dh, null, "duet-pairing".toByteArray()))
        return ByteArray(32).also { gen.generateBytes(it, 0, 32) }
    }
}
