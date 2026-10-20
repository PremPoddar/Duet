package com.prempoddar.duet.data

import com.google.common.truth.Truth.assertThat
import com.prempoddar.duet.core.TrackMeta
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.webkit.MimeTypeMap

@RunWith(RobolectricTestRunner::class)
class SaveToPhoneTest {
    private fun meta(title: String, artist: String = "", mime: String = "audio/mpeg") =
        TrackMeta(id = "0123456789abcdef", title = title, artist = artist, durationMs = 1000, mime = mime, size = 1, owner = "x", digest = "")

    @Test fun namesSongsByArtistAndTitle() {
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("mp3", "audio/mpeg")
        assertThat(SaveToPhone.fileName(meta("Midnight City", "M83"))).isEqualTo("M83 - Midnight City.mp3")
        assertThat(SaveToPhone.fileName(meta("Solo"))).isEqualTo("Solo.mp3")
    }

    @Test fun leavesOutCharactersStorageRejects() {
        assertThat(SaveToPhone.fileName(meta("What/If: Now?", "A|B"))).startsWith("A_B - What_If_ Now_.")
    }

    @Test fun fallsBackWhenTheTypeIsUnknown() {
        assertThat(SaveToPhone.fileName(meta("Track", mime = "audio/x-unknown"))).isEqualTo("Track.mp3")
    }
}
