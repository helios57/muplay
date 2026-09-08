package io.github.helios57.muplay.model

import kotlin.math.pow
import kotlin.random.Random

/**
 * Turns a pool of candidate songs into the queue a library shuffle actually plays.
 *
 * Two rules, and they are not symmetrical:
 *
 * - a [SongRating.Demoted] song is **removed**, absolutely, before anything random happens;
 * - a [SongRating.Promoted] song is **weighted**, at [PROMOTED_WEIGHT] against a neutral one.
 *
 * The asymmetry is the point. "Never play this again" is a promise a probability cannot keep, so
 * demotion is a filter. "Play this more" is a rate, so promotion is a weight.
 *
 * ### Why weighting and not duplication
 *
 * The obvious reading of "played twice as much" is to put a promoted track into the queue twice.
 * That produces a queue with the same song in it twice, which a listener reads as a bug. Instead a
 * promoted song is twice as likely to be **selected into** the queue, which is the same rate over
 * a listening session and never repeats a track inside one.
 *
 * The selection is Efraimidis–Spirakis: draw one uniform per candidate, raise it to `1/weight`,
 * keep the highest keys. Raising a number below 1 to a smaller power moves it toward 1, so a
 * heavier candidate gets a systematically higher key without ever being guaranteed a place — a
 * neutral song that draws well still beats a promoted one that draws badly, which is what keeps a
 * shuffle feeling like a shuffle.
 *
 * ### The limit that makes this do nothing
 *
 * Sampling **without replacement** down to a target that is not smaller than the eligible pool
 * returns the whole pool, whatever anything is rated. So the caller has to fetch more candidates
 * than the queue is long or the thumb up has no effect at all. `ShuffleRepository` over-fetches for
 * this reason and `ShufflePlanTest` pins the limit, because it is invisible from here.
 */
object ShufflePlan {

  /**
   * How much more likely a promoted song is to be selected than a neutral one.
   *
   * This is a selection weight, not a multiplier on anything observable: see the class doc for why
   * the achieved ratio approaches it from below rather than hitting it exactly.
   */
  const val PROMOTED_WEIGHT = 2.0

  /**
   * How many times the queue length to draw candidates from.
   *
   * Three, which makes a promoted song's advantage visible without asking the server for a library
   * every time somebody taps shuffle. It is not tuned: it is the smallest whole multiple at which
   * the selection is genuinely competitive, and `ShufflePlanTest`'s ratio measurement was taken at
   * a pool four times the queue for the same reason.
   */
  const val POOL_FACTOR = 3

  /**
   * The most candidates worth asking for, which is the protocol's own ceiling on `getRandomSongs`.
   *
   * `SubsonicClient` clamps to this number as well, at the point the request is built. Repeating it
   * here is not the duplicated clamp that class's KDoc warns against — that one changed the number
   * on the wire, this one only stops [poolSizeFor] returning a figure the wire cannot honour, so
   * the caller's arithmetic and the server's answer agree.
   */
  const val MAX_POOL = 500

  /** How many candidates to fetch in order to select a queue of [target]. */
  fun poolSizeFor(target: Int): Int {
    if (target <= 0) return 0
    // The ceiling is applied to the *product*, not to the target: a target already above the cap
    // must still ask for the cap, or a large queue would ask for fewer songs than it means to play.
    return (target.toLong() * POOL_FACTOR).coerceAtMost(MAX_POOL.toLong()).toInt()
  }

  /**
   * At most [target] songs from [songs], demoted ones dropped and promoted ones favoured.
   *
   * The returned order is shuffled independently of the weights. Selecting by weight and then
   * emitting in key order would put every promoted track at the front — an ordered playlist
   * wearing a shuffle's name — so the selection is weighted and the ordering is not.
   */
  fun queue(songs: List<Song>, target: Int, random: Random): List<Song> {
    if (target <= 0) return emptyList()

    val eligible = songs.filter { it.rating != SongRating.Demoted }
    if (eligible.size <= target) return eligible.shuffled(random)

    return eligible
      .map { song -> song to random.nextDouble().pow(1.0 / weightOf(song.rating)) }
      .sortedByDescending { (_, key) -> key }
      .take(target)
      .map { (song, _) -> song }
      .shuffled(random)
  }

  private fun weightOf(rating: SongRating): Double = when (rating) {
    SongRating.Promoted -> PROMOTED_WEIGHT
    // A demoted song is filtered out before any key is drawn, so its weight is never consulted.
    // It is stated rather than folded into the neutral branch so that this `when` stays exhaustive
    // over the enum: a fourth rating would have to be given a weight here rather than silently
    // inheriting 1.0.
    SongRating.Demoted, SongRating.Neutral -> 1.0
  }
}
