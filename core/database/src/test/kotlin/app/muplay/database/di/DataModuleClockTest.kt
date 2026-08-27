package app.muplay.database.di

import java.time.Clock
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The project's only wall-clock read, held to being a real one.
 *
 * **Moved here from `:core:media`'s `MediaModuleTest` by Plan 4 Task 4, with the binding it
 * asserts.** `AudiobookRepository` is the first class in this module to take a `Clock`, and a
 * binding declared in a module *above* its consumer leaves every `@HiltAndroidTest` here without
 * one -- so `provideClock` came down and its test came with it.
 *
 * A plain JVM test in an Android module, and deliberately not one of `DataModuleTest`'s
 * instrumented provider tests next door: `provideClock` needs no `Context`, no Room and no device,
 * and `ci/mutation-probes.sh`'s `progress/clock-frozen` probe can only see a test the **JVM** tier
 * runs (`run_suite()` names JVM test tasks; a mutation whose only witness is on a device reports
 * MISSED with zero failures, which is how a probe on `PlayerConstructionTest` had to be removed in
 * Plan 3 Task 7b). Putting this back on a device would silently disarm that probe.
 *
 * Calling the provider directly needs no Hilt machinery: [DataModule] is a Kotlin `object` whose
 * providers take plain parameters. This one takes none.
 */
class DataModuleClockTest {

  @Test
  fun `the injected clock is a real clock and not a frozen one`() {
    // Asserted as *moving* rather than as `isNotNull`: a `Clock.fixed(..)` left here by a test edit
    // would stamp every row with the same instant, and `MediaProgressDao.recentlyPlayed`'s
    // `ORDER BY lastPlayedAtEpochMs DESC` would then return an arbitrary order forever, silently.
    // `AudiobookRepository.markFinished` writes through the same clock.
    val clock = DataModule.provideClock()

    assertThat(clock.millis()).isGreaterThan(EARLIEST_PLAUSIBLE_EPOCH_MS)
    // UTC, because the column is epoch millis: a zoned clock would still report the same instant,
    // but `Clock.systemDefaultZone()` invites a later `LocalDateTime.now(clock)` that is not.
    assertThat(clock.zone).isEqualTo(ZoneOffset.UTC)
    assertThat(clock).isEqualTo(Clock.systemUTC())
  }

  private companion object {
    /** 2024-01-01T00:00:00Z. Any real clock is past it; a `Clock.fixed(EPOCH, ..)` is not. */
    const val EARLIEST_PLAUSIBLE_EPOCH_MS = 1_704_067_200_000L
  }
}
