package io.github.helios57.muplay.database

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * How far along a sync is, as a number a progress bar can be given.
 *
 * The arithmetic is here rather than in the screen because a fraction is the one part of this a
 * test can pin exactly, and because "how far along" must never be *invented*: every member below
 * that cannot honestly answer returns `null` and gets an indeterminate bar instead of a made-up
 * one. That is the same rule `LibraryUiState.Content.syncMessage` states for its wordings, applied
 * to a number.
 */
class SyncProgressTest {

  @Test
  fun `nothing is countable before the server has answered`() {
    assertThat(SyncProgress.Idle.fraction).isNull()
    assertThat(SyncProgress.Preparing.fraction).isNull()
  }

  @Test
  fun `listing has no fraction, because the total is unknown until the last page`() {
    // `fetchAllAlbums` learns it was on the last page by getting a short one back. Until then the
    // only honest statement is how many albums have been listed, never how many remain.
    assertThat(SyncProgress.Listing(found = 128).fraction).isNull()
  }

  @Test
  fun `reading is a real fraction of the albums that have to be read`() {
    assertThat(SyncProgress.Reading(done = 37, total = 412).fraction)
      .isNotNull
      .isCloseTo(37f / 412f, within(1e-6f))
  }

  @Test
  fun `the fraction spans the whole range, ends included`() {
    assertThat(SyncProgress.Reading(done = 0, total = 4).fraction).isEqualTo(0f)
    assertThat(SyncProgress.Reading(done = 4, total = 4).fraction).isEqualTo(1f)
  }

  @Test
  fun `a library with no albums has no fraction rather than a division by zero`() {
    // Reachable: a library the server reports with zero albums is a state `SyncEngine` is
    // explicitly built to mirror rather than skip. `0f / 0` is NaN, and Compose's
    // `LinearProgressIndicator` renders NaN as a bar of no width that never moves -- which reads
    // as "stuck" rather than as "nothing to do".
    assertThat(SyncProgress.Reading(done = 0, total = 0).fraction).isNull()
  }
}
