package app.muplay.book

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * A book's cover, or a neutral placeholder when the server gave the album no `coverArt` id.
 *
 * **This module's own, and not `:feature:library`'s `CoverArtImage`.** `settings.gradle.kts` says
 * in as many words that `:feature:book` depends on `:core:media`, `:core:database` and
 * `:core:model` and on *no other feature*, and `:feature:player` set the precedent by writing its
 * own `Artwork` rather than reaching across. Moving `CoverArtImage` into `:core:designsystem`
 * instead would have been the other honest answer, and it is a bigger change than it looks: it
 * drags `coverArtCacheKey`, two call sites, `CoverArtTest` and four `:feature:library` coverage
 * floors that name `CoverArtKt` -- floors this piece cannot re-measure, because three of the four
 * are `requiresInstrumentedData` and it adds no device tests. Consolidating the three cover
 * composables is a refactor with its own measurement, not a side effect of this one.
 *
 * The cache key is the **art id and the requested size, and nothing else** -- in particular not
 * the URL, which carries `u`, `t` and a fresh salt per request, so Coil's default URL-derived key
 * would miss both caches on every load and re-download every cover on every scroll.
 *
 * **The key format is a second copy of `:feature:library`'s `coverArtCacheKey`, and that is worth
 * saying out loud rather than leaving to be discovered.** It is deliberately byte-identical for a
 * non-null size (`"$coverArtId@$sizePx"`), so a book cover and an album cover of the same art at
 * the same size share one cache entry instead of storing the same bytes twice. What a second copy
 * costs is that the two can drift; what it buys is that this module has no edge to another
 * feature. The single-copy answer is the `:core:designsystem` move described above, and it is the
 * right one -- it is simply not free, and it is not this piece's to pay for unmeasured.
 *
 * [urlProvider] is a suspending lookup rather than a plain string because building the URL reads
 * the stored credentials.
 *
 * [shape] comes from `MuPlayShapes` rather than from a constant in this file, and it is a parameter
 * because the three sizes this cover is drawn at want three radii: a 56dp shelf thumbnail, a 96dp
 * one on a book's own screen, and the 240dp picture on the player. A single number is wrong for at
 * least two of them, which is what the old fixed 4dp was.
 */
@Composable
internal fun BookCover(
  coverArtId: String?,
  sizePx: Int,
  contentDescription: String?,
  urlProvider: suspend (String, Int) -> String,
  modifier: Modifier = Modifier,
  shape: Shape = MaterialTheme.shapes.small,
) {
  if (coverArtId == null) {
    Box(
      modifier = modifier
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant),
    )
    return
  }

  val url by produceState<String?>(initialValue = null, coverArtId, sizePx) {
    value = runCatching { urlProvider(coverArtId, sizePx) }.getOrNull()
  }
  val context = LocalContext.current
  val key = "$coverArtId@$sizePx"

  AsyncImage(
    model = ImageRequest.Builder(context)
      .data(url)
      // Both caches, explicitly. Omitting either leaves that half keyed on the URL, which changes
      // on every request because the salt does.
      .memoryCacheKey(key)
      .diskCacheKey(key)
      .build(),
    contentDescription = contentDescription,
    contentScale = ContentScale.Crop,
    modifier = modifier.clip(shape),
  )
}
