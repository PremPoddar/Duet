package com.prempoddar.duet.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CodeTest {
    @Test fun wordListIsBip39() {
        assertThat(Words.list).hasSize(2048)
        assertThat(Words.list.first()).isEqualTo("abandon")
        assertThat(Words.list.last()).isEqualTo("zoo")
    }

    @Test fun generatedCodesAreSixKnownWordsAndDiffer() {
        val a = SessionCode.generate()
        assertThat(a.words).hasSize(6)
        assertThat(Words.list).containsAtLeastElementsIn(a.words)
        assertThat(SessionCode.generate()).isNotEqualTo(a)
    }

    @Test fun parsingIsForgivingAboutCaseSeparatorsAndPrefixes() {
        val code = SessionCode(listOf("abandon", "ability", "able", "about", "above", "absent"))
        assertThat(SessionCode.parse("Abandon-ability  able,about.above absent")).isEqualTo(code)
        // BIP-39 words are unique by their first four letters.
        assertThat(SessionCode.parse("aban abil able abou abov abse")).isEqualTo(code)
        assertThat(SessionCode.parse("abandon ability able")).isNull()
        assertThat(SessionCode.parse("abandon ability able about above xyzzy")).isNull()
    }

    @Test fun linkRoundTrips() {
        val code = SessionCode.generate()
        assertThat(code.link()).startsWith("duet://join?c=")
        assertThat(SessionCode.fromLink(code.link())).isEqualTo(code)
        assertThat(SessionCode.fromLink("https://example.com")).isNull()
    }

    @Test fun keysAreStableAndSeparate() {
        val code = SessionCode(listOf("abandon", "ability", "able", "about", "above", "absent"))
        val k1 = code.keys
        val k2 = SessionCode.parse(code.toString())!!.keys
        assertThat(k1.topic).isEqualTo(k2.topic)
        assertThat(k1.topic).hasLength(32)
        assertThat(k1.key).isEqualTo(k2.key)
        assertThat(SessionCode.generate().keys.topic).isNotEqualTo(k1.topic)
    }

    @Test fun sealerRoundTripsAndRejectsTampering() {
        val key = ByteArray(32) { it.toByte() }
        val sealed = Sealer.seal(key, "offer".toByteArray())
        assertThat(Sealer.open(key, sealed)!!.decodeToString()).isEqualTo("offer")
        sealed[sealed.size - 1] = (sealed.last().toInt() xor 1).toByte()
        assertThat(Sealer.open(key, sealed)).isNull()
        assertThat(Sealer.open(ByteArray(32), Sealer.seal(key, byteArrayOf(1)))).isNull()
        assertThat(Sealer.open(key, ByteArray(3))).isNull()
    }

    @Test fun pairingHandsTheCodeOnlyToTheRequester() {
        val guest = Pairing.Request()
        val code = SessionCode.generate()
        val approval = Pairing.approve(guest.publicKey, code)
        assertThat(guest.accept(approval)).isEqualTo(code)
        val eavesdropper = Pairing.Request()
        assertThat(eavesdropper.accept(approval)).isNull()
    }
}
