package io.github.helios57.muplay.player

import io.github.helios57.muplay.database.RatingRepository
import io.github.helios57.muplay.model.SongRating
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * The thumbs, as this screen needs them.
 *
 * A seam beside [PlaybackControls], and for the same reason that one exists: it lets
 * `PlayerViewModelTest` drive every branch — a rating that saves, one the server refuses, a track
 * change mid-tap — on the JVM tier, with no Room, no server and no device. `RatingRepository` is
 * the shipped implementation and [Impl] is a one-line delegation to it.
 */
interface Ratings {

  /** The thumb on [songId], as it changes. */
  fun ratingOf(songId: String): Flow<SongRating>

  /** Applies a tap on [tapped]; see `RatingRepository.rate` for why this takes the button pressed. */
  suspend fun rate(songId: String, tapped: SongRating): SongRating

  /**
   * Delegates to the real repository.
   *
   * `@Inject`-constructed rather than bound with `@Binds`, so nothing in this module needs a Hilt
   * module of its own -- `PlayerViewModel`'s own `@Inject` constructor asks for this class by name.
   */
  class Impl @Inject constructor(private val repository: RatingRepository) : Ratings {
    override fun ratingOf(songId: String): Flow<SongRating> = repository.ratingOf(songId)
    override suspend fun rate(songId: String, tapped: SongRating): SongRating =
      repository.rate(songId, tapped)
  }
}
