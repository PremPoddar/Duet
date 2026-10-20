package com.prempoddar.duet.core

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class YouTubeTest {
    @Test fun findsTheVideoInEveryKindOfLink() {
        val id = "dQw4w9WgXcQ"
        listOf(
            "https://www.youtube.com/watch?v=$id", "https://youtube.com/watch?feature=share&v=$id&t=42",
            "https://m.youtube.com/watch?v=$id", "https://youtu.be/$id", "https://youtu.be/$id?si=abc",
            "https://www.youtube.com/shorts/$id", "https://music.youtube.com/watch?v=$id&list=RD",
            "https://www.youtube.com/embed/$id", "https://www.youtube.com/live/$id?feature=share",
            "youtube.com/watch?v=$id", "  $id  ", "youtu. be/$id",
        ).forEach { assertWithMessage(it).that(YouTube.videoId(it)).isEqualTo(id) }
    }

    @Test fun ignoresOtherLinks() {
        listOf("https://vimeo.com/123", "https://www.youtube.com/channel/UCabc", "https://youtu.be/short", "hello", "").forEach {
            assertWithMessage(it).that(YouTube.videoId(it)).isNull()
        }
    }

    @Test fun aStreamNeedsNoFileAndHasNoLimitUntilItsLengthIsKnown() {
        val t = YouTube.track("dQw4w9WgXcQ", "Song", "Channel", "me")
        assertThat(t.isStream).isTrue()
        assertThat(t.id).hasLength(16)
        assertThat(YouTube.track("dQw4w9WgXcQ", "Other", "", "you").id).isEqualTo(t.id)
        val state = PlaybackState(queue = listOf(t), index = 0, positionMs = 0, anchor = 0, playing = true)
        assertThat(state.positionAt(600_000)).isEqualTo(600_000)
    }
}
