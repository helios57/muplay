package io.github.helios57.muplay.model

/**
 * The listener's verdict on one track: a thumb up, a thumb down, or nothing.
 *
 * ### Why this is the server's rating and not a local table
 *
 * Subsonic's `setRating` is **per user** by construction — the rating belongs to the account the
 * request authenticates as, not to the file — which is exactly what "each user has his promoted
 * songs" asks for, with no user model of this app's own. It also survives a reinstall, shows up in
 * Navidrome's web UI, and is visible to every other client the same listener uses.
 *
 * That is the opposite of the decision taken for audiobook positions, which are local-only and
 * never sent anywhere, and the two are worth holding side by side: a position is a private detail
 * of how far somebody got, while a rating is a statement about the music that the listener would
 * expect their other clients to honour.
 *
 * ### Why three states over a five-star scale
 *
 * Both consequences this app attaches to a rating are things a user cannot see from the star
 * itself: a [Demoted] song never appears in a shuffle again, and a [Promoted] one is twice as
 * likely to. Applying either to a star this app did not write would be acting on an ambiguous
 * input, so [ofUserRating] recognises only the two exact values [userRating] emits and reads
 * everything else as [Neutral]. `SongRatingTest` records why the tempting `>= 4` / `<= 2` split is
 * the wrong reading.
 */
enum class SongRating {

  /** Thumb down. Dropped from every shuffle, and removed from the promoted playlist. */
  Demoted,

  /** No thumb. Shuffled at its natural rate. */
  Neutral,

  /** Thumb up. Weighted at twice a [Neutral] song, and added to the promoted playlist. */
  Promoted,
  ;

  /**
   * The number `setRating` is sent for this verdict.
   *
   * [Neutral] is `0` rather than an omitted parameter because Subsonic has no "unset" verb:
   * clearing a rating is `setRating(rating = 0)`, and Navidrome answers it by removing the field
   * entirely — measured, which is why [ofUserRating] has to read `null` and `0` the same way.
   */
  val userRating: Int
    get() = when (this) {
      Demoted -> 1
      Neutral -> 0
      Promoted -> 5
    }

  /**
   * What one tap on [tapped] leaves this song at.
   *
   * Tapping the thumb a song already carries clears it; tapping the other one switches outright.
   * It lives here rather than in a ViewModel because it is one rule for two controls, and the copy
   * that got it wrong would be the one nobody looked at.
   */
  fun toggledTo(tapped: SongRating): SongRating = if (this == tapped) Neutral else tapped

  companion object {

    /** Reads a Subsonic `userRating`, which is absent for a track nobody has rated. */
    fun ofUserRating(rating: Int?): SongRating = when (rating) {
      Promoted.userRating -> Promoted
      Demoted.userRating -> Demoted
      else -> Neutral
    }
  }
}
