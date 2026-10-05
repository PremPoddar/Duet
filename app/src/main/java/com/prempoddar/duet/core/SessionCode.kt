package com.prempoddar.duet.core

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The BIP-39 English list: 2048 common words, each unique by its first four letters. */
object Words {
    val list: List<String> by lazy {
        val stream = Words::class.java.getResourceAsStream("/bip39_english.txt") ?: error("word list missing")
        stream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }
    private val set by lazy { list.toHashSet() }

    /** The word [token] stands for: the word itself, or a word it is a unique 4+ letter prefix of. */
    fun resolve(token: String): String? {
        if (token in set) return token
        if (token.length < 4) return null
        return list.filter { it.startsWith(token) }.singleOrNull()
    }
}

class SessionKeys(
    /** Public name for the session's signalling topic; reveals nothing about the key. */
    val topic: String,
    val key: ByteArray,
)

/** Six words that both find a session and unlock it. 66 bits; stretched with PBKDF2. */
data class SessionCode(val words: List<String>) {
    override fun toString() = words.joinToString(" ")

    fun link(): String = "duet://join?c=" + URLEncoder.encode(words.joinToString("-"), "UTF-8")

    val keys: SessionKeys by lazy {
        val spec = PBEKeySpec(toString().toCharArray(), "duet-v1".toByteArray(), ROUNDS, 48 * 8)
        val out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        SessionKeys(out.copyOfRange(0, 16).joinToString("") { "%02x".format(it) }, out.copyOfRange(16, 48))
    }

    companion object {
        const val SIZE = 6
        private const val ROUNDS = 100_000
        private val random = SecureRandom()

        fun generate() = SessionCode(List(SIZE) { Words.list[random.nextInt(Words.list.size)] })

        /** Accepts any case and separators, and 4-letter prefixes. Null unless it's exactly six words. */
        fun parse(text: String): SessionCode? {
            val tokens = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
            if (tokens.size != SIZE) return null
            val words = tokens.map { Words.resolve(it) ?: return null }
            return SessionCode(words)
        }

        fun fromLink(link: String): SessionCode? {
            if (!link.startsWith("duet://join")) return null
            val value = link.substringAfter("c=", "").substringBefore('&')
            return parse(URLDecoder.decode(value, "UTF-8"))
        }
    }
}
