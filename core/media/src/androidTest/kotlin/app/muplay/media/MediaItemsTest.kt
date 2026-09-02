package app.muplay.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.ReplayGain
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `Song` → `MediaItem` mapping, field by field.
 *
 * **Every field is observed at two different values**, in one assertion per field, by mapping two
 * deliberately dissimilar songs and asserting the resulting *pair*. That shape is not stylistic:
 * a mapped field replaced by a hardcoded constant is the defect this project found four times in
 * a row on Plan 2 Task 3, and it survives any test that looks at one input.
 *
 * Instrumented rather than JVM because `MediaItem` is built on `android.net.Uri`, which throws
 * off-device, and Robolectric is banned. The rigour moves with the test; it does not get dropped.
 */
@RunWith(AndroidJUnit4::class)
class MediaItemsTest {

  private val first = Song(
    id = "song-1",
    libraryId = 1,
    title = "Track 1",
    albumId = "album-1",
    albumName = "Test Album",
    artistId = "artist-1",
    artistName = "Test Artist",
    trackNumber = 1,
    discNumber = 1,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = "art-1",
    replayGain = ReplayGain(trackGainDb = -6.5f, albumGainDb = -3.25f, peakAmplitude = 0.5f),
  )

  private val second = Song(
    id = "chapter-14",
    libraryId = 2,
    title = "Chapter 14",
    albumId = "album-2",
    albumName = "Test Book",
    artistId = "artist-2",
    artistName = "Test Author",
    trackNumber = 14,
    discNumber = 2,
    durationSeconds = 900,
    suffix = "m4b",
    coverArtId = "art-2",
    replayGain = ReplayGain(trackGainDb = 2.75f, albumGainDb = -11.5f, peakAmplitude = 0.875f),
  )

  private val firstItem =
    MediaItems.of(first, "https://host/rest/stream?id=song-1&s=aaa", "art-1", isAudiobook = false, format = StreamFormat.Raw)
  private val secondItem =
    MediaItems.of(second, "https://host/rest/stream?id=chapter-14&s=bbb", "art-2", isAudiobook = false, format = StreamFormat.Raw)

  private fun <T> pair(select: (MediaItem) -> T): List<T> = listOf(select(firstItem), select(secondItem))

  /**
   * The ReplayGain extras, at two values each, the same way every other field in this file is
   * observed twice.
   *
   * The **track** gain is what appears, not the album gain -- both songs above carry both, and the
   * two are disjoint, so a `MediaItems` that wrote `albumGainDb` would produce -3.25/-11.5 here and
   * fail. That is `ReplayGainPolicy.gainDbFor`'s decision, made once at this layer so nothing
   * downstream re-derives it.
   */
  @Test
  fun theReplayGainExtrasCarryTheTrackGainAndThePeak() {
    assertThat(pair { it.mediaMetadata.extras!!.getFloat(MediaItems.KEY_REPLAY_GAIN_DB) })
      .containsExactly(-6.5f, 2.75f)
    assertThat(pair { it.mediaMetadata.extras!!.getFloat(MediaItems.KEY_REPLAY_GAIN_PEAK) })
      .containsExactly(0.5f, 0.875f)
  }

  /**
   * An untagged song leaves the keys **absent**, not present at a sentinel.
   *
   * This is the encoding the whole feature's "no decision" case rests on: any float value would be
   * a number `ReplayGainPolicy.linearGain` would happily clamp and apply, so "the file said
   * nothing" can only live in the absence of the key. `ReplayGainController` and `ProgressWriter`
   * both read it with `containsKey` for that reason, and this is what stops a well-meaning
   * `putFloat(KEY, 0f)` from being added here.
   */
  @Test
  fun anUntaggedSongCarriesNoReplayGainKeysAtAll() {
    val item = MediaItems.of(
      first.copy(replayGain = null),
      "https://host/s",
      null,
      isAudiobook = false,
      format = StreamFormat.Raw,
    )

    val extras = item.mediaMetadata.extras
    // The bundle itself is present -- one question for every reader, not two.
    assertThat(extras).isNotNull
    assertThat(extras!!.containsKey(MediaItems.KEY_REPLAY_GAIN_DB)).isFalse
    assertThat(extras.containsKey(MediaItems.KEY_REPLAY_GAIN_PEAK)).isFalse
  }

  /**
   * A file tagged by an album-oriented tool: album gain, no track gain. The gain key appears,
   * carrying the album value -- the fallback that `gainDbFor` exists for.
   */
  @Test
  fun aSongWithOnlyAnAlbumGainStillCarriesADecision() {
    val item = MediaItems.of(
      first.copy(replayGain = ReplayGain(null, -7.5f, null)),
      "https://host/s",
      null,
      isAudiobook = false,
      format = StreamFormat.Raw,
    )

    val extras = item.mediaMetadata.extras!!
    assertThat(extras.getFloat(MediaItems.KEY_REPLAY_GAIN_DB)).isEqualTo(-7.5f)
    // ...and no peak was invented for a file that reported none.
    assertThat(extras.containsKey(MediaItems.KEY_REPLAY_GAIN_PEAK)).isFalse
  }

  /**
   * The peak is independent of the gain: a file may report one and not the other, in either
   * direction, and neither may be manufactured from the other's presence.
   */
  @Test
  fun aPeakWithNoGainIsNoDecisionAndAGainWithNoPeakIsStillADecision() {
    val peakOnly = MediaItems.of(
      first.copy(replayGain = ReplayGain(null, null, 0.4f)),
      "https://host/s",
      null,
      isAudiobook = false,
      format = StreamFormat.Raw,
    ).mediaMetadata.extras!!
    val gainOnly = MediaItems.of(
      first.copy(replayGain = ReplayGain(-2.0f, null, null)),
      "https://host/s",
      null,
      isAudiobook = false,
      format = StreamFormat.Raw,
    ).mediaMetadata.extras!!

    assertThat(peakOnly.containsKey(MediaItems.KEY_REPLAY_GAIN_DB)).isFalse
    assertThat(peakOnly.getFloat(MediaItems.KEY_REPLAY_GAIN_PEAK)).isEqualTo(0.4f)
    assertThat(gainOnly.getFloat(MediaItems.KEY_REPLAY_GAIN_DB)).isEqualTo(-2.0f)
    assertThat(gainOnly.containsKey(MediaItems.KEY_REPLAY_GAIN_PEAK)).isFalse
  }

  @Test
  fun theMediaIdIsTheSongId() {
    // The single most important field in the app: `media_progress` is keyed on it, so a constant
    // here would make every book share one position row.
    assertThat(pair { it.mediaId }).containsExactly("song-1", "chapter-14")
  }

  @Test
  fun theUriIsTheStreamUrlItWasGiven() {
    assertThat(pair { it.localConfiguration?.uri?.toString() })
      .containsExactly("https://host/rest/stream?id=song-1&s=aaa", "https://host/rest/stream?id=chapter-14&s=bbb")
  }

  /**
   * The cache key is the **track id**, never the URI — spec section 4, and the defect Tempo ships.
   * This client's stream URLs carry a fresh auth salt per call (`StreamUrlTest` pins that), so a
   * URL-derived key produces a cache with a 0% hit rate. `TrackIdCacheKeyFactory` refuses a
   * `DataSpec` with no key at all; this is where the key is actually put on.
   */
  @Test
  fun theCustomCacheKeyIsTheSongIdAndNotTheUri() {
    assertThat(pair { it.localConfiguration?.customCacheKey }).containsExactly("song-1", "chapter-14")
  }

  @Test
  fun theTitleIsTheSongTitle() {
    assertThat(pair { it.mediaMetadata.title?.toString() }).containsExactly("Track 1", "Chapter 14")
  }

  @Test
  fun theArtistIsTheSongArtist() {
    assertThat(pair { it.mediaMetadata.artist?.toString() }).containsExactly("Test Artist", "Test Author")
  }

  @Test
  fun theAlbumTitleIsTheSongAlbum() {
    assertThat(pair { it.mediaMetadata.albumTitle?.toString() }).containsExactly("Test Album", "Test Book")
  }

  @Test
  fun theTrackNumberIsTheSongTrackNumber() {
    assertThat(pair { it.mediaMetadata.trackNumber }).containsExactly(1, 14)
  }

  @Test
  fun theDiscNumberIsTheSongDiscNumber() {
    assertThat(pair { it.mediaMetadata.discNumber }).containsExactly(1, 2)
  }

  /**
   * The mirror's duration, in milliseconds, on the metadata.
   *
   * Not decoration. For an Ogg/Opus source `StreamFormat.forSuffix` forces a **live** transcode,
   * a live transcode carries no `Content-Length`, and ExoPlayer therefore reports
   * `C.TIME_UNSET` -- at which point `LegacyConversions` falls back to exactly this field for the
   * platform session. Unset, that fallback is null and every such track is unknown-length on the
   * lock screen, in Auto and on Wear.
   *
   * Two observations at two values, like every other mapped field here: 5 s and 900 s. A
   * `setDurationMs(0L)`, a `setDurationMs(song.durationSeconds.toLong())` (seconds, not
   * milliseconds) and a deleted call all fail this.
   */
  @Test
  fun theDurationIsTheSongDurationInMilliseconds() {
    assertThat(pair { it.mediaMetadata.durationMs }).containsExactly(5_000L, 900_000L)
  }

  @Test
  fun theArtworkUriIsTheOneItWasGiven() {
    // The cover-art **id**, wrapped in this app's own scheme -- never a URL, and never the
    // authenticated one this used to carry. See `ArtworkUri` for the surface that made the
    // difference matter, and `PlatformSessionCredentialTest` for the rule over the whole item.
    assertThat(pair { it.mediaMetadata.artworkUri?.toString() })
      .containsExactly("muplay-art:art-1", "muplay-art:art-2")
  }

  @Test
  fun aSongWithNoArtworkGetsNoArtworkUriRatherThanAPlaceholder() {
    val item = MediaItems.of(
      first.copy(coverArtId = null),
      "https://host/stream",
      artworkId = null,
      isAudiobook = false,
      format = StreamFormat.Raw,
    )

    assertThat(item.mediaMetadata.artworkUri).isNull()
    // ...and the rest of the mapping is unaffected, so "no artwork" is not silently "no metadata".
    assertThat(item.mediaMetadata.title?.toString()).isEqualTo("Track 1")
  }

  @Test
  fun absentTrackAndDiscNumbersStayAbsent() {
    // Navidrome omits these for a single-file audiobook. Mapping a missing number to 0 would put
    // "0" on a lock screen and sort a book above every real track.
    val item = MediaItems.of(
      first.copy(trackNumber = null, discNumber = null),
      "https://host/s",
      null,
      isAudiobook = false,
      format = StreamFormat.Raw,
    )

    assertThat(item.mediaMetadata.trackNumber).isNull()
    assertThat(item.mediaMetadata.discNumber).isNull()
  }

  @Test
  fun everyItemIsPlayableAndNotBrowsable() {
    // Fixed values rather than mapped ones, so the two-observation rule does not apply -- but they
    // are load-bearing: Android Auto (Plan 5) renders a browse tree from exactly these flags, and
    // an item marked browsable shows up as a folder that opens onto nothing.
    assertThat(pair { it.mediaMetadata.isPlayable }).containsExactly(true, true)
    assertThat(pair { it.mediaMetadata.isBrowsable }).containsExactly(false, false)
    // `mediaType` used to be asserted here too, at one value, because it was a constant. It is not
    // one any more, and both fixtures above are built `isAudiobook = false` -- so an assertion here
    // would observe the music arm twice and prove nothing about the switch. The two tests below
    // observe it at both values instead.
  }

  /**
   * The one fact the protocol cannot supply, and the one this app is allowed to decide.
   *
   * Navidrome hardcodes `child.Type = "music"` for every media file — the seeded `Test Book.m4b`
   * comes back as `"type": "music"` — so the user's own `LibraryRole` assignment, joined to
   * `Song.libraryId` by [QueueRepository], is the only mechanism there is. Two observations, so a
   * constant satisfies neither: this field was a hardcoded `MEDIA_TYPE_MUSIC` until this task, and
   * it is what `PlaybackAudioAttributes` reads to decide speech versus music.
   */
  @Test
  fun theMediaTypeFollowsTheUsersOwnLibraryRoleAndNothingElse() {
    assertThat(
      MediaItems.of(first, "https://host/s", null, isAudiobook = false, format = StreamFormat.Raw).mediaMetadata.mediaType,
    ).isEqualTo(MediaMetadata.MEDIA_TYPE_MUSIC)
    assertThat(
      MediaItems.of(second, "https://host/s", null, isAudiobook = true, format = StreamFormat.Raw).mediaMetadata.mediaType,
    ).isEqualTo(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
  }

  /**
   * The suffix is not the signal, in **both** directions.
   *
   * `second` has suffix `m4b` — the shape that tempts an inference — and is still music unless the
   * user's `LibraryRole` says otherwise; `first` has suffix `mp3` and is a book when the user said
   * its library is one. Both are real: an audiobook library holds plain mp3 chapters, and a music
   * library holds m4b DJ sets. A suffix inference passes the test above and fails this one.
   */
  @Test
  fun theFileSuffixNeverDecidesWhetherSomethingIsAnAudiobook() {
    assertThat(second.suffix).isEqualTo("m4b")
    assertThat(first.suffix).isEqualTo("mp3")
    assertThat(
      MediaItems.of(second, "https://host/s", null, isAudiobook = false, format = StreamFormat.Raw).mediaMetadata.mediaType,
    ).isEqualTo(MediaMetadata.MEDIA_TYPE_MUSIC)
    assertThat(
      MediaItems.of(first, "https://host/s", null, isAudiobook = true, format = StreamFormat.Raw).mediaMetadata.mediaType,
    ).isEqualTo(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
  }

  /**
   * The **served** MIME type, which is not the source file's suffix.
   *
   * `MediaItem.localConfiguration.mimeType` is a real Media3 field the local extractor reads as a
   * hint, and Plan 6 makes it the single value three separate parties read: the proxy serves it as
   * `Content-Type`, the proxy path ends in the matching extension, and `res/@protocolInfo` in the
   * DIDL document declares it. See `ServedMedia` and `MimeAgreement` in `:core:cast`.
   *
   * Two observations of the raw branch, so it cannot be a constant, and two of the transcode
   * branch, where the suffix must NOT win: an Opus track announced to Sonos as `audio/ogg` while
   * MP3 bytes are served is spec section 12's "Sonos rejects a served format" risk in its most
   * confusing form.
   *
   * **The `flac` line is the one that discriminates, and it is here because the `opus` line does
   * not.** Measured: with `ServedMedia.of`'s transcode arm mutated to fall through to the source
   * suffix -- the exact defect this test is named for -- the `opus` assertion stays **green**,
   * because `opus` is absent from `RAW_TYPES` and the fallback answers `audio/mpeg` either way.
   * The `opus` line states the rule; only a suffix the raw table knows can catch it being broken.
   */
  @Test
  fun theMimeTypeIsTheServedFormatAndNotTheSourceSuffix() {
    assertThat(mimeOf(first.copy(suffix = "mp3"), StreamFormat.Raw)).isEqualTo("audio/mpeg")
    assertThat(mimeOf(first.copy(suffix = "flac"), StreamFormat.Raw)).isEqualTo("audio/flac")
    assertThat(mimeOf(first.copy(suffix = "opus"), StreamFormat.Mp3(192))).isEqualTo("audio/mpeg")
    assertThat(mimeOf(first.copy(suffix = "flac"), StreamFormat.Mp3(192))).isEqualTo("audio/mpeg")
  }

  /**
   * ...and the value really did come from this call rather than from the queue deciding twice.
   * `QueueRepository` computes one `StreamFormat`, builds the URL with it and passes the same value
   * here, so `format=mp3` on the wire and `audio/mpeg` on the item are one decision. The pairing
   * that would go unnoticed is a `.opus` source streamed raw, which this rules out from the other
   * side: the same song answers differently depending only on the format it is given.
   */
  @Test
  fun theSameSongAnswersDifferentlyForTheFormatItsUrlWasBuiltWith() {
    val song = first.copy(suffix = "flac")

    assertThat(mimeOf(song, StreamFormat.Raw)).isEqualTo("audio/flac")
    assertThat(mimeOf(song, StreamFormat.Mp3(192))).isEqualTo("audio/mpeg")
  }

  private fun mimeOf(song: Song, format: StreamFormat): String? =
    MediaItems.of(song, "https://host/s", null, isAudiobook = false, format = format)
      .localConfiguration?.mimeType

  // ---- Plan 3 Task 12: the format stamp, and reading it back ----------------------------------

  /**
   * The `format` wire value the URI was built with, stamped on the item.
   *
   * `TranscodeSeek` decides how to seek from this and from nothing else, and nothing else on a
   * `MediaItem` could answer it: the MIME type reads `audio/mpeg` for a transcoded Opus **and** for
   * a plain mp3 streamed raw, which is exactly the pair that must not be confused. Two values, so a
   * stamp hardcoded to either one fails here.
   */
  @Test
  fun theStreamFormatIsStampedWithTheValueTheUriWasBuiltFrom() {
    val raw = MediaItems.of(first, "https://host/s", null, isAudiobook = false, format = StreamFormat.Raw)
    val transcoded = MediaItems.of(
      first,
      "https://host/s",
      null,
      isAudiobook = false,
      format = StreamFormat.Mp3(192),
    )

    assertThat(raw.mediaMetadata.extras!!.getString(MediaItems.KEY_STREAM_FORMAT)).isEqualTo("raw")
    assertThat(transcoded.mediaMetadata.extras!!.getString(MediaItems.KEY_STREAM_FORMAT)).isEqualTo("mp3")
    // The cap rides along only for a transcode -- a bitrate beside `format=raw` is a value the
    // server ignores and a re-issue would then ask for.
    assertThat(raw.mediaMetadata.extras!!.containsKey(MediaItems.KEY_STREAM_MAX_BITRATE_KBPS)).isFalse
    assertThat(transcoded.mediaMetadata.extras!!.getInt(MediaItems.KEY_STREAM_MAX_BITRATE_KBPS))
      .isEqualTo(192)
  }

  /**
   * ...and read back as the same [StreamFormat], which is what the re-issued URI is built from.
   *
   * Round-tripped rather than asserted as a string, because the thing that must not drift is the
   * *request*: `TranscodeOffsetSupport.reissue` asks the server for whatever this returns, and a
   * reader that dropped the cap would change the transcode -- and Navidrome's cache entry with it
   * -- in the middle of a track.
   */
  @Test
  fun theStampedFormatReadsBackAsTheFormatItWasBuiltFrom() {
    val each = listOf(StreamFormat.Raw, StreamFormat.Mp3(96), StreamFormat.Mp3(320))

    assertThat(
      each.map { format ->
        MediaItems.streamFormatOf(
          MediaItems.of(first, "https://host/s", null, isAudiobook = false, format = format),
        )
      },
    ).containsExactlyElementsOf(each)
  }

  /**
   * An item this object did not build carries no stamp, and reads back as `null` -- which
   * `TranscodeSeek` treats as "seek in place", the behaviour of every player before Task 12.
   *
   * Three shapes, because they are three different absences: no extras at all (a `MediaItem`
   * assembled by the system restoring a session), extras with no format key, and -- the one worth
   * writing down -- a `"mp3"` stamp with no bitrate beside it, which is not a format anyone can ask
   * a server for and so must not be guessed at.
   */
  @Test
  fun anItemThisObjectDidNotBuildHasNoStreamFormat() {
    val bare = MediaItem.Builder().setMediaId("x").build()
    val emptyExtras = MediaItem.Builder().setMediaId("x")
      .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle()).build()).build()
    val mp3WithNoCap = MediaItem.Builder().setMediaId("x").setMediaMetadata(
      MediaMetadata.Builder()
        .setExtras(Bundle().apply { putString(MediaItems.KEY_STREAM_FORMAT, "mp3") })
        .build(),
    ).build()

    assertThat(MediaItems.streamFormatOf(bare)).isNull()
    assertThat(MediaItems.streamFormatOf(emptyExtras)).isNull()
    assertThat(MediaItems.streamFormatOf(mp3WithNoCap)).isNull()
  }

  /**
   * A freshly built item begins at the top of its track, and an item with no extras at all does
   * too.
   *
   * `MediaItems.timeOffsetMsOf` is what `MuPlayer` adds to every position it reports, so a
   * non-zero answer here would shift the whole clock of every ordinary track. Only
   * `TranscodeOffsetSupport.reissue` ever writes that key.
   */
  @Test
  fun anItemThatWasNotReissuedBeginsAtZero() {
    val built = MediaItems.of(first, "https://host/s", null, isAudiobook = false, format = StreamFormat.Raw)

    assertThat(MediaItems.timeOffsetMsOf(built)).isEqualTo(0L)
    assertThat(MediaItems.timeOffsetMsOf(MediaItem.Builder().setMediaId("x").build())).isEqualTo(0L)
    // ...and a value that *was* written reads back, so the line above is not "always zero".
    val reissued = built.buildUpon().setMediaMetadata(
      built.mediaMetadata.buildUpon()
        .setExtras(Bundle(built.mediaMetadata.extras).apply { putLong(MediaItems.KEY_TIME_OFFSET_MS, 24_000L) })
        .build(),
    ).build()
    assertThat(MediaItems.timeOffsetMsOf(reissued)).isEqualTo(24_000L)
  }

}
