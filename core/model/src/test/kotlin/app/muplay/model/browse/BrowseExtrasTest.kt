package app.muplay.model.browse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The extras a car head unit reads.
 *
 * The **string keys** are asserted as literals, because they are a contract with software this
 * project does not own and no compiler checks them. The **values** are asserted as exact maps,
 * because "the bundle has a completion status in it" is satisfied by a status that is the same
 * constant for every book -- and `isEqualTo` over the whole map is what makes an *extra* key as
 * visible as a missing one, which `containsEntry` is not.
 */
class BrowseExtrasTest {

  @Test
  fun `the wire keys and values are exactly the ones Android Auto documents`() {
    assertThat(
      listOf(
        BrowseExtras.CONTENT_STYLE_SUPPORTED,
        BrowseExtras.CONTENT_STYLE_BROWSABLE,
        BrowseExtras.CONTENT_STYLE_PLAYABLE,
        BrowseExtras.COMPLETION_STATUS,
        BrowseExtras.COMPLETION_PERCENTAGE,
      ),
    ).containsExactly(
      "android.media.browse.CONTENT_STYLE_SUPPORTED",
      "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT",
      "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT",
      "androidx.media.MediaBrowserCompat.extras.COMPLETION_STATUS",
      "androidx.media.MediaBrowserCompat.extras.COMPLETION_PERCENTAGE",
    )
    assertThat(
      listOf(
        BrowseExtras.STYLE_LIST,
        BrowseExtras.STYLE_GRID,
        BrowseExtras.STATUS_NOT_PLAYED,
        BrowseExtras.STATUS_PARTIALLY_PLAYED,
        BrowseExtras.STATUS_FULLY_PLAYED,
      ),
    ).containsExactly(1, 2, 0, 1, 2)
  }

  @Test
  fun `a browsable node carries its own child style and a list style for its playables`() {
    val grid = BrowseExtras.forNode(folder(BrowseStyle.GRID))
    val list = BrowseExtras.forNode(folder(BrowseStyle.LIST))

    assertThat(grid).isEqualTo(
      mapOf(
        BrowseExtras.CONTENT_STYLE_BROWSABLE to 2,
        BrowseExtras.CONTENT_STYLE_PLAYABLE to 1,
      ),
    )
    // Both observations, so the style is proven to come from the node rather than to be a constant.
    assertThat(list).isEqualTo(
      mapOf(
        BrowseExtras.CONTENT_STYLE_BROWSABLE to 1,
        BrowseExtras.CONTENT_STYLE_PLAYABLE to 1,
      ),
    )
  }

  @Test
  fun `a playable leaf carries no style extras at all`() {
    assertThat(BrowseExtras.forNode(leaf())).isEmpty()
  }

  @Test
  fun `only a partially played item carries a percentage`() {
    val notPlayed =
      BrowseExtras.forNode(book(BrowseCompletion(BrowseCompletionStatus.NOT_PLAYED, 0.0)))
    val partly =
      BrowseExtras.forNode(book(BrowseCompletion(BrowseCompletionStatus.PARTIALLY_PLAYED, 0.42)))
    val finished =
      BrowseExtras.forNode(book(BrowseCompletion(BrowseCompletionStatus.FULLY_PLAYED, 1.0)))

    assertThat(notPlayed).isEqualTo(mapOf(BrowseExtras.COMPLETION_STATUS to 0))
    assertThat(partly).isEqualTo(
      mapOf(
        BrowseExtras.COMPLETION_STATUS to 1,
        BrowseExtras.COMPLETION_PERCENTAGE to 0.42,
      ),
    )
    assertThat(finished).isEqualTo(mapOf(BrowseExtras.COMPLETION_STATUS to 2))
  }

  @Test
  fun `the percentage is the node's own fraction and not a constant`() {
    // The defect this catches: `put(COMPLETION_PERCENTAGE, 0.5)`. Two fractions, one assertion.
    assertThat(
      listOf(0.11, 0.87).map { fraction ->
        BrowseExtras.forNode(book(BrowseCompletion(BrowseCompletionStatus.PARTIALLY_PLAYED, fraction)))[
          BrowseExtras.COMPLETION_PERCENTAGE,
        ]
      },
    ).containsExactly(0.11, 0.87)
  }

  @Test
  fun `a browsable book carries both its child style and its completion`() {
    // The two halves of `forNode` are independent `if`s over the same node, and every other test
    // here observes exactly one of them. A node that is browsable *and* has a completion is what
    // a multi-file, part-heard book actually is, and it is the only input that fails an
    // implementation that returned early after the first block.
    assertThat(
      BrowseExtras.forNode(
        BrowseNode(
          id = BrowseId.Book("al-1"),
          title = "Test Book",
          isBrowsable = true,
          isPlayable = true,
          mediaType = BrowseMediaType.AUDIO_BOOK,
          childStyle = BrowseStyle.LIST,
          completion = BrowseCompletion(BrowseCompletionStatus.PARTIALLY_PLAYED, 0.2),
        ),
      ),
    ).isEqualTo(
      mapOf(
        BrowseExtras.CONTENT_STYLE_BROWSABLE to 1,
        BrowseExtras.CONTENT_STYLE_PLAYABLE to 1,
        BrowseExtras.COMPLETION_STATUS to 1,
        BrowseExtras.COMPLETION_PERCENTAGE to 0.2,
      ),
    )
  }

  @Test
  fun `the root advertises content style support and the surface's own default`() {
    assertThat(BrowseExtras.forRoot(BrowseSurface.CAR)).isEqualTo(
      mapOf(
        BrowseExtras.CONTENT_STYLE_SUPPORTED to true,
        BrowseExtras.CONTENT_STYLE_BROWSABLE to 2,
        BrowseExtras.CONTENT_STYLE_PLAYABLE to 1,
      ),
    )
    assertThat(BrowseExtras.forRoot(BrowseSurface.WATCH)).isEqualTo(
      mapOf(
        BrowseExtras.CONTENT_STYLE_SUPPORTED to true,
        BrowseExtras.CONTENT_STYLE_BROWSABLE to 1,
        BrowseExtras.CONTENT_STYLE_PLAYABLE to 1,
      ),
    )
  }

  private companion object {
    fun folder(style: BrowseStyle) = BrowseNode(
      id = BrowseId.Albums,
      title = "Albums",
      isBrowsable = true,
      isPlayable = false,
      mediaType = BrowseMediaType.FOLDER_ALBUMS,
      childStyle = style,
    )

    fun leaf() = BrowseNode(
      id = BrowseId.Track("tr-1"),
      title = "Track 1",
      isBrowsable = false,
      isPlayable = true,
      mediaType = BrowseMediaType.TRACK,
    )

    fun book(completion: BrowseCompletion) = BrowseNode(
      id = BrowseId.Book("al-1"),
      title = "Test Book",
      isBrowsable = false,
      isPlayable = true,
      mediaType = BrowseMediaType.AUDIO_BOOK,
      completion = completion,
    )
  }
}
