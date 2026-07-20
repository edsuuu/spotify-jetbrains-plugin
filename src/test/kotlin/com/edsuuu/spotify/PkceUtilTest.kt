package com.edsuuu.spotify

import com.edsuuu.spotify.api.PkceUtil
import com.edsuuu.spotify.api.PlaybackState
import com.edsuuu.spotify.api.QueueResponse
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceUtilTest {

    @Test
    fun challengeMatchesRfcVector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expected, PkceUtil.challengeFor(verifier))
    }

    @Test
    fun generatedVerifierIsValid() {
        val v = PkceUtil.generateCodeVerifier()
        assertEquals(64, v.length)
        assertTrue("only unreserved chars", v.all { it.isLetterOrDigit() || it in "-._~" })
        assertTrue(!PkceUtil.challengeFor(v).contains("="))
    }

    @Test
    fun parsesQueueJson() {
        val json = """
            {
              "currently_playing": {
                "id": "abc", "uri": "spotify:track:abc", "name": "Song A",
                "duration_ms": 210000,
                "artists": [{"name": "Artist One"}, {"name": "Artist Two"}],
                "album": {"name": "Album", "images": [
                  {"url": "https://img/large", "width": 640, "height": 640},
                  {"url": "https://img/small", "width": 64, "height": 64}
                ]}
              },
              "queue": [
                {"id": "d1", "name": "Next", "artists": [{"name": "Someone"}], "duration_ms": 180000}
              ]
            }
        """.trimIndent()
        val q = Gson().fromJson(json, QueueResponse::class.java)
        assertEquals("Song A", q.currentlyPlaying?.name)
        assertEquals(210000L, q.currentlyPlaying?.durationMs)
        assertEquals("Artist One, Artist Two", q.currentlyPlaying?.artistNames())
        assertEquals("https://img/small", q.currentlyPlaying?.thumbnailUrl())
        assertEquals(1, q.queue?.size)
        assertEquals("Next", q.queue?.first()?.name)
    }

    @Test
    fun parsesPlayerStateJson() {
        val json = """
            {
              "shuffle_state": true,
              "smart_shuffle": true,
              "is_playing": true,
              "progress_ms": 12345,
              "context": {"uri": "spotify:playlist:xyz", "type": "playlist"},
              "item": {"id": "abc", "name": "Song A", "duration_ms": 210000}
            }
        """.trimIndent()
        val s = Gson().fromJson(json, PlaybackState::class.java)
        assertEquals(true, s.shuffleState)
        assertEquals(true, s.smartShuffle)
        assertEquals(true, s.isPlaying)
        assertEquals(12345L, s.progressMs)
        assertEquals("spotify:playlist:xyz", s.context?.uri)
        assertEquals("Song A", s.item?.name)
    }
}
