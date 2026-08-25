package app.muplay.media

import app.muplay.database.entity.MediaProgressEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The other half of `PlaybackQueue`'s structural guard, now that this plan is about to grow a
 * writer.
 *
 * Spec section 3: *"Nothing about queue membership may live in this table — a `queuePosition`
 * column would invert the design."* `PlaybackQueue` is guarded against gaining a position; this
 * guards the progress table against gaining queue membership. The two together are what make
 * "two pointer lists over one progress table" a fact rather than an intention.
 *
 * It lives in `:core:media` rather than in `:core:database` deliberately: the reason this table
 * must not grow a queue column is a *playback* reason, and this is the module whose progress
 * writer would be the one to want the column. A guard placed where the temptation is is a guard
 * that gets read.
 */
class ProgressTableShapeTest {

  @Test
  fun `the progress table carries no queue membership`() {
    val fields = MediaProgressEntity::class.java.declaredFields
      .filterNot { it.isSynthetic }
      .map { it.name }

    assertThat(fields)
      .describedAs(
        "media_progress is a property of the item, never of a queue (spec section 3). A " +
          "queuePosition or isInQueue column inverts the design: it makes one global now-playing " +
          "position that the next thing played overwrites, which is the exact defect this app " +
          "exists to fix.",
      )
      .containsExactlyInAnyOrder(
        "mediaId", "positionMs", "isFinished", "lastPlayedAtEpochMs", "speed", "skipSilence", "gainDb",
      )
  }
}
