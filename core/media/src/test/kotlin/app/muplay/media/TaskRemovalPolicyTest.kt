package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The rule `MuPlaybackService.onTaskRemoved` applies, gated by the fast tier.
 *
 * The service method itself is called by the system and by nothing else, so it cannot be driven
 * from an instrumented test -- which is exactly why the decision does not live inside it. What is
 * asserted here is every combination that changes the answer, including the two that are the whole
 * point: playing with a queue must **not** stop, and ready-with-an-empty-queue must.
 */
class TaskRemovalPolicyTest {

  @Test
  fun `music that is playing survives the user tidying their recents list`() {
    // The defect this half exists to prevent: a service that stops unconditionally kills audio the
    // user is actively listening to.
    assertThat(TaskRemovalPolicy.stopsService(playWhenReady = true, mediaItemCount = 3)).isFalse()
  }

  @Test
  fun `a paused player does not keep the service alive`() {
    // The other half: an idle foreground service leaves a notification the user cannot dismiss,
    // belonging to an app that is no longer in recents.
    assertThat(TaskRemovalPolicy.stopsService(playWhenReady = false, mediaItemCount = 3)).isTrue()
  }

  @Test
  fun `an empty queue stops the service even when the player is ready to play`() {
    // `playWhenReady` survives `clearMediaItems`, so this combination is reachable and a rule that
    // read only that flag would keep an empty player alive forever.
    assertThat(TaskRemovalPolicy.stopsService(playWhenReady = true, mediaItemCount = 0)).isTrue()
  }

  @Test
  fun `no session at all is the clearest reason to stop`() {
    assertThat(TaskRemovalPolicy.stopsService(playWhenReady = null, mediaItemCount = null)).isTrue()
    // Half-null cannot happen through the service, but the rule has to answer it rather than
    // depend on its one caller never producing it.
    assertThat(TaskRemovalPolicy.stopsService(playWhenReady = true, mediaItemCount = null)).isTrue()
    assertThat(TaskRemovalPolicy.stopsService(playWhenReady = null, mediaItemCount = 3)).isTrue()
  }
}
