package app.muplay.media.browse

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.browse.BrowseCompletion
import app.muplay.model.browse.BrowseCompletionStatus
import app.muplay.model.browse.BrowseExtras
import app.muplay.model.browse.BrowseId
import app.muplay.model.browse.BrowseMediaType
import app.muplay.model.browse.BrowseNode
import app.muplay.model.browse.BrowseStyle
import app.muplay.model.browse.BrowseSurface
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `BrowseNode` -> `MediaItem`, field by field.
 *
 * **On a device, and not because of Hilt.** `androidx.media3.common.MediaItem` reaches
 * `android.net.Uri` and `android.os.Bundle`, both unimplemented stubs in the JVM's `android.jar`;
 * a plain unit test of this mapping fails with *"not mocked"* and the only escape is Robolectric,
 * which spec sections 2 and 10 ban. Plan 3 Task 4 made the same call for the same reason.
 *
 * Every assertion below compares **two different nodes**, so that replacing any single assignment
 * with a constant fails here. Method names are camelCase, not backticked: `minSdk 26` compiles DEX
 * 035, which forbids a space in any `SimpleName`, and a backticked instrumented test does not dex
 * at all.
 */
@RunWith(AndroidJUnit4::class)
class BrowseItemsTest {

  @Test
  fun everyFieldOfANodeReachesTheMediaItem() {
    val items = listOf(BOOK_NODE to "http://host/art/1", ALBUM_NODE to "http://host/art/2")
      .map { (node, art) -> BrowseItems.of(node, art) }

    assertThat(items.map { it.mediaId })
      .containsExactly("muplay/book/al-7c3f", "muplay/album/al-9911")
    assertThat(items.map { it.mediaMetadata.title?.toString() })
      .containsExactly("Test Book", "Abbey Road")
    assertThat(items.map { it.mediaMetadata.subtitle?.toString() })
      .containsExactly("Test Author · 4 min left", "The Beatles")
    assertThat(items.map { it.mediaMetadata.artworkUri?.toString() })
      .containsExactly("http://host/art/1", "http://host/art/2")
    assertThat(items.map { it.mediaMetadata.mediaType })
      .containsExactly(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK, MediaMetadata.MEDIA_TYPE_ALBUM)
    assertThat(items.map { it.mediaMetadata.durationMs }).containsExactly(15_000L, 2_832_000L)
  }

  @Test
  fun browsableAndPlayableAreReadFromTheNodeAndNotAssumed() {
    // The pair that a constant satisfies most easily: every node in the fixture above is both
    // browsable and playable, so a `.setIsBrowsable(true)` would pass that test. A folder, a
    // playable leaf and a book that is both are the three combinations the tree actually produces.
    val nodes = listOf(FOLDER_NODE, TRACK_NODE, BOOK_NODE).map { BrowseItems.of(it, null) }

    assertThat(nodes.map { it.mediaMetadata.isBrowsable }).containsExactly(true, false, true)
    assertThat(nodes.map { it.mediaMetadata.isPlayable }).containsExactly(false, true, true)
  }

  @Test
  fun aNodeWithNoArtworkProducesNoArtworkUriRatherThanAnEmptyOne() {
    // `Uri.parse("")` is a valid, empty Uri, and an image loader renders it as a broken image
    // rather than as the placeholder a null would get. Both the null and the blank case, because
    // they take different paths through the same expression.
    assertThat(
      listOf(null, "", "   ").map { BrowseItems.of(ALBUM_NODE, it).mediaMetadata.artworkUri },
    ).containsExactly(null, null, null)
  }

  @Test
  fun noBrowseItemCarriesALocalConfigurationAndThereforeNoStreamUrl() {
    // A browse item is an identity, never a stream. `localConfiguration` is where a `MediaItem`
    // keeps its `Uri`, and Android Auto persists browse ids and their items across reinstalls --
    // an authenticated Subsonic URL put here would outlive the credentials in it.
    assertThat(listOf(BOOK_NODE, ALBUM_NODE, TRACK_NODE).map { BrowseItems.of(it, "http://host/a") })
      .isNotEmpty
      .allSatisfy { assertThat(it.localConfiguration).isNull() }
  }

  @Test
  fun theExtrasBundleCarriesEveryKeyTheMapDid() {
    val extras = requireNamedExtras(BrowseItems.of(BOOK_NODE, null))

    // Sorted so the assertion is stable across `Bundle`'s own iteration order, and exact so an
    // *extra* key is as visible as a missing one. `android.` sorts before `androidx.`.
    assertThat(extras.keySet().sorted()).containsExactly(
      BrowseExtras.CONTENT_STYLE_BROWSABLE,
      BrowseExtras.CONTENT_STYLE_PLAYABLE,
      BrowseExtras.COMPLETION_PERCENTAGE,
      BrowseExtras.COMPLETION_STATUS,
    )
    assertThat(extras.getInt(BrowseExtras.COMPLETION_STATUS))
      .isEqualTo(BrowseExtras.STATUS_PARTIALLY_PLAYED)
    assertThat(extras.getDouble(BrowseExtras.COMPLETION_PERCENTAGE)).isEqualTo(0.2)
    assertThat(extras.getInt(BrowseExtras.CONTENT_STYLE_BROWSABLE))
      .isEqualTo(BrowseExtras.STYLE_LIST)
  }

  @Test
  fun bundleOfPreservesTheTypeOfEveryValueRatherThanItsStringForm() {
    // `Bundle` is typed storage: `getInt` on a key written as a Double answers 0, silently, and a
    // car reading a status of 0 sees "not played" on a finished book. Read back through the same
    // getters Android Auto uses.
    val bundle = BrowseItems.bundleOf(
      mapOf("i" to 7, "d" to 0.75, "b" to true),
    )

    assertThat(bundle.getInt("i")).isEqualTo(7)
    assertThat(bundle.getDouble("d")).isEqualTo(0.75)
    assertThat(bundle.getBoolean("b")).isTrue
    assertThat(bundle.keySet().sorted()).containsExactly("b", "d", "i")
  }

  @Test
  fun bundleOfRefusesAValueKindTheExtrasCannotCarry() {
    // A silently dropped extra is invisible in a car. `runCatching` rather than an assertion on a
    // return value, because the contract is that it throws.
    val thrown = runCatching { BrowseItems.bundleOf(mapOf("s" to "a string")) }.exceptionOrNull()

    assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
    assertThat(thrown?.message).contains("s").contains("java.lang.String")
  }

  @Test
  fun everyMediaTypeMapsToADistinctMedia3Constant() {
    // Nine enum members, nine constants, asserted as an exact ordered list. A `when` with a wrong
    // arm, or an `else ->` that swallowed a member, fails here rather than showing a book as a
    // song in a car three screens away.
    assertThat(app.muplay.model.browse.BrowseMediaType.entries.map(BrowseItems::mediaTypeOf))
      .containsExactly(
        MediaMetadata.MEDIA_TYPE_MIXED,
        MediaMetadata.MEDIA_TYPE_ALBUM,
        MediaMetadata.MEDIA_TYPE_ARTIST,
        MediaMetadata.MEDIA_TYPE_MUSIC,
        MediaMetadata.MEDIA_TYPE_AUDIO_BOOK,
        MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER,
        MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
        MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
        MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
      )
    // And they really are distinct -- a mapping that returned MEDIA_TYPE_MIXED for everything would
    // otherwise need nine separate assertions to catch.
    assertThat(BrowseMediaType.entries.map(BrowseItems::mediaTypeOf).toSet()).hasSize(9)
  }

  @Test
  fun theRootItemDiffersBySurface() {
    val car = requireNamedExtras(BrowseItems.root(BrowseSurface.CAR))
    val watch = requireNamedExtras(BrowseItems.root(BrowseSurface.WATCH))

    assertThat(BrowseItems.root(BrowseSurface.CAR).mediaId).isEqualTo("muplay/root")
    assertThat(BrowseItems.root(BrowseSurface.CAR).mediaMetadata.isBrowsable).isTrue
    assertThat(BrowseItems.root(BrowseSurface.CAR).mediaMetadata.isPlayable).isFalse
    assertThat(BrowseItems.root(BrowseSurface.CAR).mediaMetadata.mediaType)
      .isEqualTo(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
    assertThat(
      listOf(
        car.getInt(BrowseExtras.CONTENT_STYLE_BROWSABLE),
        watch.getInt(BrowseExtras.CONTENT_STYLE_BROWSABLE),
      ),
    ).containsExactly(BrowseExtras.STYLE_GRID, BrowseExtras.STYLE_LIST)
    assertThat(car.getBoolean(BrowseExtras.CONTENT_STYLE_SUPPORTED)).isTrue
  }

  private fun requireNamedExtras(item: MediaItem) =
    requireNotNull(item.mediaMetadata.extras) { "no extras on ${item.mediaId}" }

  private companion object {
    val BOOK_NODE = BrowseNode(
      id = BrowseId.Book("al-7c3f"),
      title = "Test Book",
      subtitle = "Test Author · 4 min left",
      isBrowsable = true,
      isPlayable = true,
      mediaType = BrowseMediaType.AUDIO_BOOK,
      artworkId = "cov-1",
      childStyle = BrowseStyle.LIST,
      completion = BrowseCompletion(BrowseCompletionStatus.PARTIALLY_PLAYED, 0.2),
      durationMs = 15_000L,
    )

    val ALBUM_NODE = BrowseNode(
      id = BrowseId.Album("al-9911"),
      title = "Abbey Road",
      subtitle = "The Beatles",
      isBrowsable = true,
      isPlayable = true,
      mediaType = BrowseMediaType.ALBUM,
      artworkId = "cov-2",
      childStyle = BrowseStyle.LIST,
      completion = null,
      durationMs = 2_832_000L,
    )

    val FOLDER_NODE = BrowseNode(
      id = BrowseId.Albums,
      title = "Albums",
      isBrowsable = true,
      isPlayable = false,
      mediaType = BrowseMediaType.FOLDER_ALBUMS,
      childStyle = BrowseStyle.GRID,
    )

    val TRACK_NODE = BrowseNode(
      id = BrowseId.Track("tr-1"),
      title = "Come Together",
      subtitle = "The Beatles",
      isBrowsable = false,
      isPlayable = true,
      mediaType = BrowseMediaType.TRACK,
      durationMs = 259_000L,
    )
  }
}
