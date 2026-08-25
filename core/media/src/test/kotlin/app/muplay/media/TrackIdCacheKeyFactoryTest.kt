package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What this module is allowed to say out loud about a stream URL.
 *
 * A plain JVM test, and that is the reason [TrackIdCacheKeyFactory.trackIdIn] takes and returns a
 * `String`: the reduction of a credential-bearing URL to a safe diagnostic names no Android and no
 * Media3 type, so it is reachable from the fast tier and from `ci/mutation-probes.sh`. The elvis
 * around it -- `dataSpec.key ?: throw` -- is a `DataSpec` away from here and stays on the device,
 * in `MediaCacheTest`. Same split, and the same reason, as `StreamRetryPolicy` against its Media3
 * adapter.
 *
 * **Why this is worth a test at all.** `MissingCacheKeyException` is thrown inside
 * `CacheDataSource.open`, which runs inside `Loader$LoadTask.run`; that method logs it
 * (`Log.e(TAG, "Unexpected exception loading stream", e)`) and then wraps it into an
 * `ExoPlaybackException` that `ExoPlayerImplInternal` logs again. Message and cause chain reach
 * logcat, every bug report and any crash reporter ever attached. A Subsonic stream URL carries
 * `u`, `s` (the salt) and `t = md5(password + salt)`, and Navidrome tracks no salt nonce, so that
 * triple is a replayable, non-expiring password equivalent. `MediaModuleTest`'s "nothing logs on
 * the client that carries the credentials" exists to keep exactly this out of logs; an exception
 * message is a way around it.
 */
class TrackIdCacheKeyFactoryTest {

  @Test
  fun `the diagnostic for a stream url is the track id and nothing else`() {
    assertThat(TrackIdCacheKeyFactory.trackIdIn(streamUrl("track-1"))).isEqualTo("track-1")
    // Id first as well as id last: an implementation that took whichever parameter came first
    // would satisfy one of these and leak the username on the other.
    assertThat(TrackIdCacheKeyFactory.trackIdIn(idFirstStreamUrl("track-1"))).isEqualTo("track-1")
  }

  @Test
  fun `no part of the credential survives into the diagnostic`() {
    val diagnostic = TrackIdCacheKeyFactory.trackIdIn(streamUrl("track-1"))

    assertThat(diagnostic).doesNotContain(USERNAME, SALT, TOKEN)
    // Asserted through the message too, because that is the string that is actually logged: a
    // reduction that is safe on its own and an exception that pastes the URL back in beside it
    // are different facts.
    assertThat(MissingCacheKeyException(diagnostic).message)
      .contains("track-1")
      .doesNotContain(USERNAME, SALT, TOKEN)
  }

  @Test
  fun `a url with no id says so rather than falling back to the url`() {
    // The natural wrong fix -- "if the id cannot be found, at least print the URL" -- puts the
    // credential straight back into the log. There is no shape of URL for which that is the right
    // answer: the exception's type and message already say what went wrong.
    val noId = "http://navidrome.example/rest/stream?u=$USERNAME&t=$TOKEN&s=$SALT"

    val diagnostic = TrackIdCacheKeyFactory.trackIdIn(noId)

    assertThat(diagnostic).isEqualTo(TrackIdCacheKeyFactory.UNKNOWN_TRACK)
    assertThat(diagnostic).doesNotContain(USERNAME, SALT, TOKEN, "http")
  }

  @Test
  fun `a parameter that merely ends in id is not the id`() {
    // `startsWith("id=")` over a split query, not `contains("id=")` over the whole one: the second
    // matches `xid=`, `mediaid=` and a token that happens to contain the two characters, and every
    // one of those hands back a value that is not a track id.
    val decoy = "http://navidrome.example/rest/stream?xid=not-a-track&id=track-9&t=$TOKEN"

    assertThat(TrackIdCacheKeyFactory.trackIdIn(decoy)).isEqualTo("track-9")
  }

  @Test
  fun `a fragment is not part of the id`() {
    val fragmented = "http://navidrome.example/rest/stream?id=track-3#$TOKEN"

    assertThat(TrackIdCacheKeyFactory.trackIdIn(fragmented)).isEqualTo("track-3")
  }

  @Test
  fun `a url with no query at all is not mistaken for one`() {
    assertThat(TrackIdCacheKeyFactory.trackIdIn("http://navidrome.example/rest/stream"))
      .isEqualTo(TrackIdCacheKeyFactory.UNKNOWN_TRACK)
    // An empty value is an absent one, and must not become an empty track name in the message.
    assertThat(TrackIdCacheKeyFactory.trackIdIn("http://navidrome.example/rest/stream?id="))
      .isEqualTo(TrackIdCacheKeyFactory.UNKNOWN_TRACK)
  }

  /** `SubsonicClient.streamUrl`'s real shape, with the auth triple ahead of the id. */
  private fun streamUrl(trackId: String): String =
    "http://navidrome.example/rest/stream?u=$USERNAME&t=$TOKEN&s=$SALT&v=1.16.1&c=MuPlay&id=$trackId"

  private fun idFirstStreamUrl(trackId: String): String =
    "http://navidrome.example/rest/stream?id=$trackId&u=$USERNAME&t=$TOKEN&s=$SALT"

  private companion object {
    const val USERNAME = "admin"
    const val SALT = "9f8e7d6c5b4a3210"
    const val TOKEN = "1f0e2d3c4b5a69788796a5b4c3d2e1f0"
  }
}
