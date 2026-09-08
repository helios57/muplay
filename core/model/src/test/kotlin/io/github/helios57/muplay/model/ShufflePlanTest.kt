package io.github.helios57.muplay.model

import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [ShufflePlan]: what the two thumbs actually do to a shuffle.
 *
 * The promise is "a demoted song is never shuffled again, a promoted one is played twice as much".
 * The first half is absolute and is asserted as such. The second is a *rate*, so it is asserted
 * over many draws against a seeded [Random] — deterministic, but still a measurement of the
 * distribution rather than of one call.
 */
class ShufflePlanTest {

  @Test
  fun `a demoted song is never drawn, however many times the shuffle runs`() {
    val songs = listOf(song("keep", SongRating.Neutral), song("banned", SongRating.Demoted))

    val drawn = (1..500).flatMap { seed -> ShufflePlan.queue(songs, target = 2, Random(seed)) }

    // `target` is 2 and there are 2 songs, so a plan that merely shuffled would return both every
    // time. The demoted one is absent because it was removed, not because it lost a draw.
    assertThat(drawn.map { it.id }).containsOnly("keep")
    assertThat(drawn).hasSize(500)
  }

  @Test
  fun `a promoted song is drawn about twice as often as a neutral one`() {
    // Twenty songs, half promoted, drawing five at a time: the pool is four times the queue, so
    // being chosen is genuinely competitive and the weighting has room to show.
    val songs = (0 until 10).map { song("neutral-$it", SongRating.Neutral) } +
      (0 until 10).map { song("promoted-$it", SongRating.Promoted) }

    val drawn = (1..4000).flatMap { seed -> ShufflePlan.queue(songs, target = 5, Random(seed)) }
    val promoted = drawn.count { it.id.startsWith("promoted") }
    val neutral = drawn.count { it.id.startsWith("neutral") }

    // Not asserted as exactly 2.0. Sampling *without replacement* cannot hold a 2:1 ratio exactly
    // once a queue is a large fraction of the pool -- each promoted song drawn removes itself from
    // the next draw -- so the achievable ratio is bounded below 2 and approaches it as the pool
    // grows.
    //
    // **Measured on this fixture, not predicted:** 1.856 at `PROMOTED_WEIGHT = 2.0`. Falsified in
    // both directions on the same tree -- weight 1.0 gives 1.021 and weight 4.0 gives 3.457, and
    // this band rejects both. Re-run that falsification if you move the weight or the fixture; a
    // recorded number is a measurement with a timestamp, not a property.
    val ratio = promoted.toDouble() / neutral
    assertThat(ratio).isBetween(1.6, 2.0)
  }

  @Test
  fun `weighting cannot show at all when the queue is as long as the pool`() {
    // The limit worth stating out loud, because it is how this feature silently does nothing.
    // Sampling *without replacement* down to a target that is not smaller than the eligible pool
    // returns the whole pool whatever anything is rated -- so `ShuffleRepository` has to ask the
    // server for more songs than the queue length or the thumbs up has no effect. That over-fetch
    // is the load-bearing part of the feature and it lives at the caller, so this test is the only
    // place that says so.
    val songs = listOf(song("a", SongRating.Neutral), song("b", SongRating.Promoted))

    val drawn = ShufflePlan.queue(songs, target = 2, Random(1))

    assertThat(drawn.map { it.id }).containsExactlyInAnyOrder("a", "b")
  }

  @Test
  fun `no song is ever drawn twice in one queue`() {
    // Weighting by duplication would have been the easy implementation of "twice as much" and it
    // would put the same track in one queue twice, which reads as a bug to a listener.
    val songs = (0 until 30).map { song("s-$it", if (it % 2 == 0) SongRating.Promoted else SongRating.Neutral) }

    repeat(200) { seed ->
      val drawn = ShufflePlan.queue(songs, target = 20, Random(seed))
      assertThat(drawn.map { it.id }).doesNotHaveDuplicates()
      assertThat(drawn).hasSize(20)
    }
  }

  @Test
  fun `a pool of nothing but demoted songs produces an empty queue rather than falling back`() {
    // The honest answer. Returning the demoted songs "because otherwise there is nothing to play"
    // would break the one promise the thumb down makes, at exactly the moment the user would
    // notice.
    val songs = listOf(song("x", SongRating.Demoted), song("y", SongRating.Demoted))

    assertThat(ShufflePlan.queue(songs, target = 10, Random(1))).isEmpty()
  }

  @Test
  fun `an empty pool and a zero target are both empty queues rather than failures`() {
    assertThat(ShufflePlan.queue(emptyList(), target = 10, Random(1))).isEmpty()
    assertThat(ShufflePlan.queue(listOf(song("a", SongRating.Neutral)), target = 0, Random(1))).isEmpty()
    assertThat(ShufflePlan.queue(listOf(song("a", SongRating.Neutral)), target = -5, Random(1))).isEmpty()
  }

  @Test
  fun `the order is shuffled rather than the order the weights imply`() {
    // Top-k-by-weight would return the promoted songs first and the neutral ones after, which is
    // an ordered playlist wearing a shuffle's name. The selection is weighted; the order is not.
    val songs = (0 until 10).map { song("p-$it", SongRating.Promoted) } +
      (0 until 10).map { song("n-$it", SongRating.Neutral) }

    val positionsOfNeutral = (1..200).map { seed ->
      ShufflePlan.queue(songs, target = 20, Random(seed)).indexOfFirst { it.id.startsWith("n-") }
    }

    // Every song is drawn here (target == pool), so if the order followed the weights the first
    // neutral track would sit at index 10 every single time.
    assertThat(positionsOfNeutral.toSet()).hasSizeGreaterThan(1)
    assertThat(positionsOfNeutral.min()).isLessThan(10)
  }

  @Test
  fun `the pool asked for is a multiple of the queue, so the weighting has something to choose from`() {
    // The companion piece to `weighting cannot show at all when the queue is as long as the pool`.
    // That test states the limit; this one states the caller's answer to it, and the two are worth
    // reading together -- on their own, each looks like an arbitrary number.
    assertThat(ShufflePlan.poolSizeFor(100)).isGreaterThan(100)
    assertThat(ShufflePlan.poolSizeFor(100)).isEqualTo(100 * ShufflePlan.POOL_FACTOR)
  }

  @Test
  fun `the pool never exceeds what the protocol will return`() {
    // Subsonic caps `getRandomSongs` at 500 and `SubsonicClient` clamps to it. Asking for 1500 and
    // being given 500 is not an error anywhere -- so a pool factor applied without a ceiling here
    // reads, from the repository, as "I asked for three times the queue" while the server answered
    // with one and a half. The ceiling is stated where the multiplication happens so the number the
    // caller reasons about is the number that comes back.
    assertThat(ShufflePlan.poolSizeFor(400)).isEqualTo(ShufflePlan.MAX_POOL)
    assertThat(ShufflePlan.poolSizeFor(1000)).isEqualTo(ShufflePlan.MAX_POOL)
  }

  @Test
  fun `a queue larger than the protocol cap asks for the cap rather than for less than the queue`() {
    // The degenerate case, and the one that would silently shrink a shuffle: `min(target * 3, 500)`
    // is 500 for a target of 600, which is fewer songs than were asked to be played. There is
    // nothing better available -- the server will not return more -- but it must not return less
    // than the cap either, which a naive `coerceAtMost(MAX_POOL)` on the target alone would.
    assertThat(ShufflePlan.poolSizeFor(600)).isEqualTo(ShufflePlan.MAX_POOL)
  }

  @Test
  fun `a zero or negative target asks for nothing`() {
    assertThat(ShufflePlan.poolSizeFor(0)).isZero()
    assertThat(ShufflePlan.poolSizeFor(-1)).isZero()
  }

  private fun song(id: String, rating: SongRating) = Song(
    id = id,
    libraryId = 1,
    title = id,
    albumId = null,
    albumName = null,
    artistId = null,
    artistName = null,
    trackNumber = null,
    discNumber = null,
    durationSeconds = 100,
    suffix = "mp3",
    coverArtId = null,
    rating = rating,
  )
}
