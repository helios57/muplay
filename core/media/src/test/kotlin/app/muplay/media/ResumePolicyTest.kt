package app.muplay.media

import java.lang.reflect.Modifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A JVM test, because [ResumePolicy] takes media ids and an index — no `MediaItem`, no
 * `android.net.Uri`. That signature is chosen for exactly this reason, and it has a second
 * benefit: the policy is *structurally incapable* of honouring a caller's requested position,
 * because it is never told one.
 *
 * **What this class can and cannot prove.** It verifies the *decision* — what [NeverResume]
 * answers, and what shape [ResumePolicy] is allowed to be asked. It cannot verify that the answer
 * is what reaches the player; that is the *application* of the decision, it lives in `MuPlayer`'s
 * six `setMediaItem(s)` overrides, and only an instrumented `MuPlayerTest` driving a real
 * `ExoPlayer` can observe it. A green run here says nothing about whether any overload was wired
 * up at all.
 */
class ResumePolicyTest {

  /**
   * Five items rather than three, so an index can be chosen that is not 0, not `size`, and not
   * `size - 1` — three values an implementation could return by accident and still look right
   * against a short queue.
   */
  private val queue = listOf("a", "b", "c", "d", "e")

  @Test
  fun `the plan-3 policy starts every item from zero`() {
    // Spec section 3: "Music restarts from 0 -- progress is still recorded, just not honoured on
    // prepare." Not a placeholder; the specified behaviour.
    assertThat(NeverResume.resolve(queue, requestedIndex = 0).startPositionMs).isZero
    assertThat(NeverResume.resolve(queue, requestedIndex = 3).startPositionMs).isZero
  }

  @Test
  fun `the position is zero whatever queue it is shown`() {
    // The other axis of the same value, and the one the index-varying test above cannot reach: a
    // policy answering `mediaIds.size * 1000L`, or the length of the first id, passes that test at
    // every index and fails here. Three genuinely disjoint queues -- different lengths, different
    // ids, one of them empty.
    val positions = listOf(
      NeverResume.resolve(queue, requestedIndex = 0).startPositionMs,
      NeverResume.resolve(listOf("only-one"), requestedIndex = 0).startPositionMs,
      NeverResume.resolve(emptyList(), requestedIndex = 0).startPositionMs,
    )

    assertThat(positions).containsExactly(0L, 0L, 0L)
  }

  @Test
  fun `the caller's chosen item is respected`() {
    // The index is queue membership -- "play track 3 of this album" is a legitimate request -- so
    // it is *not* discarded. Three observations, because a policy that returned 0 always would
    // pass the first alone, and one that returned `mediaIds.lastIndex` would pass a two-value
    // check that happened to name the last item.
    assertThat(NeverResume.resolve(queue, requestedIndex = 0).startIndex).isZero
    assertThat(NeverResume.resolve(queue, requestedIndex = 3).startIndex).isEqualTo(3)
    assertThat(NeverResume.resolve(queue, requestedIndex = 4).startIndex).isEqualTo(4)
  }

  @Test
  fun `the resolved index names an item in the order the caller passed`() {
    // `startIndex` is only meaningful against the list it was resolved over, so this is the
    // assertion written the way a caller reads it. Two orderings of the same five ids, because an
    // index chosen from a mirrored view of the queue -- `lastIndex - requestedIndex`, the shape a
    // "resume from the end" implementation reaches for -- answers 4 and 0 here and names the wrong
    // track both times, while agreeing with a single-ordering check that only looked at the number.
    assertThat(queue[NeverResume.resolve(queue, requestedIndex = 3).startIndex]).isEqualTo("d")
    assertThat(queue.reversed()[NeverResume.resolve(queue.reversed(), requestedIndex = 3).startIndex])
      .isEqualTo("b")
  }

  @Test
  fun `clearing the queue is answered rather than thrown on`() {
    // `setMediaItems(emptyList())` is how a queue is cleared and it reaches the policy like any
    // other call, on the player's application thread. A policy that read `mediaIds[requestedIndex]`
    // -- the obvious way to "look up what is being resumed" -- would take playback down with it
    // here. Plan 4's policy has to keep this property.
    val target = NeverResume.resolve(emptyList(), requestedIndex = 0)

    assertThat(target).isEqualTo(ResumeTarget(startIndex = 0, startPositionMs = 0L))
  }

  @Test
  fun `a policy cannot be handed a position to honour`() {
    // A structural assertion, and the reason this test class exists at all. `resolve` has exactly
    // two parameters; adding a `requestedPositionMs` would give a future implementation something
    // to accidentally trust, which is the failure mode spec section 3's seam exists to remove.
    val parameters = ResumePolicy::class.java.methods.single { it.name == "resolve" }.parameterTypes

    assertThat(parameters.map { it.simpleName }).containsExactly("List", "int")
  }

  @Test
  fun `a policy answers with a target rather than a bare position`() {
    // The other half of the signature, and not a restatement of the one above: a `resolve` that
    // returned a `Long` would satisfy the parameter check exactly, and would silently drop the
    // caller's index -- the one thing in this signature that belongs to the caller and must
    // survive. Both facts have to hold for the seam to be able to call `setMediaItems(items,
    // index, position)` at all.
    val resolve = ResumePolicy::class.java.methods.single { it.name == "resolve" }

    assertThat(resolve.returnType).isEqualTo(ResumeTarget::class.java)
  }

  @Test
  fun `a resume target carries an index and a position and nothing else`() {
    // The shape guard on the answer, matching the one on the question above. A third property --
    // `requestedPositionMs`, `honourCallersPosition` -- would reintroduce from the return side
    // exactly what taking the position out of the parameters removed.
    //
    // Order-insensitive on purpose: `Class.getDeclaredFields` makes no ordering guarantee, and
    // field *order* carries no meaning here, unlike the parameter order asserted above.
    val fields = ResumeTarget::class.java.declaredFields.filterNot { it.isSynthetic }.map { it.name }

    assertThat(fields).containsExactlyInAnyOrder("startIndex", "startPositionMs")
  }

  @Test
  fun `the policy has exactly one thing to implement`() {
    // Plan 4 swaps this binding for a policy that answers from an in-memory `media_progress`
    // snapshot, and `fun interface` is what lets that be a lambda over the snapshot rather than a
    // class. A `fun interface` is only legal while it has exactly one abstract method, so this is
    // that constraint asserted rather than left to the day someone adds a second one and
    // discovers the extension point no longer compiles.
    //
    // It is also the reason `resolve` can be found by name above with `single { .. }`: a second
    // abstract member would make both of those structural assertions ambiguous rather than false,
    // which is a worse way to find out.
    val abstractMethods = ResumePolicy::class.java.methods
      .filter { Modifier.isAbstract(it.modifiers) }
      .map { it.name }

    assertThat(abstractMethods).containsExactly("resolve")
  }
}
