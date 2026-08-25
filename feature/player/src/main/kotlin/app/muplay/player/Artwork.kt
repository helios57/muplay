package app.muplay.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest

/**
 * Cover art, cached on the **media id** rather than on the URL.
 *
 * Same principle as `setCustomCacheKey` one layer down, and for the same reason: a cover-art URL
 * carries a fresh auth salt on every call (`StreamUrlTest` pins that), so a loader keyed on the URL
 * re-downloads the same image on every session and never hits its cache. [cacheKey] is
 * `PlaybackState.mediaId`, which is stable for as long as the server's own id is.
 *
 * **[uri] is never rendered, never logged and never put in a content description.** A cover-art URL
 * carries the same auth token and salt a stream URL does; it is handed to the image loader and
 * nowhere else. [contentDescription] is a caller-supplied label, not derived from the URL.
 *
 * Not shared with `:feature:library`'s `CoverArtImage`, which takes a cover-art **id** and a
 * suspending URL builder because it renders items from the mirror. This one is handed an
 * already-built URL by the media session and keys on a media id. Consolidating the two into
 * `:core:designsystem` is a later refactor with two call sites, not a design change — but it must
 * keep both cache-key policies, which is why they are not merged by simply deleting one.
 */
@Composable
fun Artwork(
  uri: String?,
  cacheKey: String?,
  contentDescription: String?,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      // On the Box, not on the AsyncImage: the placeholder must carry the same label as the
      // loaded image, or the screen loses its only description of this element for as long as the
      // art takes to arrive -- or forever, for a track the server has no art for.
      .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
  ) {
    if (uri != null) {
      AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
          .data(uri)
          // Both caches, explicitly. Omitting either leaves that half keyed on the URL, which
          // changes on every request because the salt does.
          .memoryCacheKey(cacheKey)
          .diskCacheKey(cacheKey)
          .build(),
        // Null, because the Box above already carries it: two nodes with the same description
        // would make `onNodeWithContentDescription` ambiguous and read the element out twice.
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}
