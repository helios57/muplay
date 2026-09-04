package app.muplay.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * One cover image, or a neutral placeholder when the server gave the item no `coverArt` id.
 *
 * The cache key it stamps on both caches is [coverArtCacheKey], which lives in its own file — see
 * that file's own note for why, and do not move it back here.
 *
 * [urlProvider] is a suspending lookup rather than a plain string because building the URL needs
 * the stored credentials, which are read asynchronously.
 */
@Composable
fun CoverArtImage(
  coverArtId: String?,
  sizePx: Int,
  contentDescription: String?,
  urlProvider: suspend (String, Int) -> String,
  modifier: Modifier = Modifier,
) {
  if (coverArtId == null) {
    Box(
      modifier = modifier
        .clip(RoundedCornerShape(4.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
    return
  }

  val url by produceState<String?>(initialValue = null, coverArtId, sizePx) {
    value = runCatching { urlProvider(coverArtId, sizePx) }.getOrNull()
  }
  val context = LocalContext.current
  val key = coverArtCacheKey(coverArtId, sizePx)

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
    modifier = modifier.clip(RoundedCornerShape(4.dp)),
  )
}
