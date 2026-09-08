package io.github.helios57.muplay.database

import io.github.helios57.muplay.database.dao.BrowseDao
import io.github.helios57.muplay.model.PromotedPlaylist
import io.github.helios57.muplay.model.PromotedPlaylistAction
import io.github.helios57.muplay.model.SongRating
import io.github.helios57.muplay.network.SubsonicSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The thumbs: one tap, three consequences.
 *
 * A rating is **server state**, per user, written with Subsonic's `setRating`. That is not the
 * choice audiobook positions made — those are local-only and never leave the device — and the two
 * are worth holding side by side. A position is a private detail of how far somebody got; a rating
 * is a statement about the music that the same listener would expect Navidrome's web UI and every
 * other client to honour. It also answers "each user has his promoted songs" with no user model of
 * this app's own, because `setRating` already records the account the request authenticates as.
 *
 * ### The three consequences, in the order they happen
 *
 * 1. **The server's rating**, which is what makes the thumb durable and shared.
 * 2. **The mirror's copy of it**, so the thumb stays lit without waiting for a reconcile.
 * 3. **The `promoted-<user>` playlist**, which is the part the listener can hand to something else.
 *
 * The order is deliberate and each step is allowed to fail loudly. If the server refuses, nothing
 * local changes and the caller sees it. If the playlist edit fails after the rating saved, the
 * rating *has* saved — reverting it would be a lie about what the server holds — and the caller
 * still sees the failure. What must never happen is the reverse: a lit thumb over a rating the
 * server never took.
 */
@Singleton
class RatingRepository @Inject constructor(
  private val browseDao: BrowseDao,
  private val credentialStore: CredentialStore,
  private val sourceProvider: SubsonicSourceProvider,
) {

  /**
   * The thumb on [songId], as it changes.
   *
   * Read from the mirror rather than the server: a rating is on the screen next to a track that is
   * already playing, so it has to be answerable without a request, and the reconcile keeps it
   * honest. A track the mirror has never seen reads as [SongRating.Neutral], which is what an
   * unrated track looks like too — the difference is not one a thumb can draw.
   */
  fun ratingOf(songId: String): Flow<SongRating> =
    browseDao.observeUserRating(songId).map(SongRating::ofUserRating)

  /**
   * Applies a tap on [tapped] to [songId] and returns the rating it now carries.
   *
   * A tap on the thumb a song already has clears it — `SongRating.toggledTo` owns that rule — so
   * this takes the button that was pressed, not the state to move to. The current state is read
   * from the mirror rather than passed in, because the screen's copy of it can be a moment stale
   * and two taps in quick succession would otherwise both compute the same "next".
   */
  suspend fun rate(songId: String, tapped: SongRating): SongRating {
    val current = SongRating.ofUserRating(browseDao.findSong(songId)?.userRating)
    val next = current.toggledTo(tapped)
    val source = sourceProvider.current()

    source.setRating(songId, next.userRating)
    browseDao.setUserRating(songId, next.userRating)
    syncPromotedPlaylist(source, songId, promoted = next == SongRating.Promoted)

    return next
  }

  /**
   * Brings the `promoted-<user>` playlist into line with [promoted].
   *
   * Every branch is [PromotedPlaylist]'s; this only carries them out. The membership read is not
   * optional — measured against Navidrome, `songIdToAdd` on a song the playlist already holds
   * appends a **second copy** — and it is why promoting costs two reads before it writes anything.
   */
  private suspend fun syncPromotedPlaylist(source: SubsonicSource, songId: String, promoted: Boolean) {
    val username = credentialStore.load()?.username ?: return
    val listed = PromotedPlaylist.find(source.getPlaylists(), username)
    // `getPlaylist`'s `musicFolderId` is only a stamp on the songs it returns, and nothing here
    // reads anything but their ids, so which library it names cannot change the answer. It is
    // stated as a named constant rather than an inline zero so that nobody later "fixes" it into a
    // real library id and makes this method need one.
    val existing = listed?.let { source.getPlaylist(it.id, LIBRARY_STAMP_UNUSED) }

    when (val action = PromotedPlaylist.actionFor(existing, songId, promoted)) {
      PromotedPlaylistAction.None -> Unit
      PromotedPlaylistAction.Create ->
        source.createPlaylist(PromotedPlaylist.nameFor(username), listOf(songId))
      PromotedPlaylistAction.Add ->
        source.updatePlaylist(
          playlistId = requireNotNull(listed).id,
          songIdsToAdd = listOf(songId),
          songIndexesToRemove = emptyList(),
        )
      is PromotedPlaylistAction.Remove ->
        source.updatePlaylist(
          playlistId = requireNotNull(listed).id,
          songIdsToAdd = emptyList(),
          songIndexesToRemove = action.indices,
        )
    }
  }

  private companion object {
    /** See [syncPromotedPlaylist]: `getPlaylist` stamps a library on its entries and nothing here reads it. */
    const val LIBRARY_STAMP_UNUSED = 0
  }
}
