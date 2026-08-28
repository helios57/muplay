package app.muplay.settings

import androidx.compose.runtime.Composable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The slot's one piece of logic: what order the sections come out in.
 *
 * Worth a test rather than a `sortedBy` at the call site because **a `Set` has no order**. Dagger's
 * multibinding hands over a `LinkedHashSet` whose iteration order follows the order Dagger happened
 * to generate its bindings in -- stable within one compilation and not across one. A screen that
 * iterated the set directly would render its sections in an order that changed when an unrelated
 * module was added to the build, which is the sort of thing that gets blamed on Compose.
 *
 * Plain JVM: `SettingsSection` is an interface with a `@Composable` method, and nothing here
 * composes anything.
 */
class SettingsViewModelTest {

  @Test
  fun `sections come out in ascending order, whatever order the set is in`() {
    // The input is deliberately in the wrong order and deliberately not reverse order either: a
    // reversal is the one permutation an accidental `sortedByDescending` and an accidental
    // `reversed()` both produce, and it would let either pass.
    val viewModel = SettingsViewModel(linkedSetOf(Third, First, Second))

    assertThat(viewModel.sections).containsExactly(First, Second, Third)
  }

  @Test
  fun `the order really is read from the section rather than taken from the set`() {
    // The same three objects, inserted in ascending order, so a `SettingsViewModel` that returned
    // the set untouched passes. The assertion is that the answer does not move when the input does.
    val ascending = SettingsViewModel(linkedSetOf(First, Second, Third))
    val descending = SettingsViewModel(linkedSetOf(Third, Second, First))

    assertThat(ascending.sections).isEqualTo(descending.sections)
  }

  @Test
  fun `two sections claiming the same position are still ordered the same way every time`() {
    // Two modules can pick the same `order` without knowing about each other -- there is no
    // registry, which is the point of the slot. `sortedBy` alone is stable, which means it would
    // preserve the set's own arbitrary iteration order for the tie, so the answer would depend on
    // Dagger's codegen order. The class-name tiebreak makes it a function of the contents.
    val oneWay = SettingsViewModel(linkedSetOf(Tied, First))
    val theOther = SettingsViewModel(linkedSetOf(First, Tied))

    // `First` and `Tied` both claim 100; `First` wins on class name and does so both times.
    assertThat(oneWay.sections).containsExactly(First, Tied)
    assertThat(theOther.sections).containsExactly(First, Tied)
  }

  @Test
  fun `a graph with no sections at all is an empty list, not a failure`() {
    // The state a build with casting removed is in. `SettingsModule`'s `@Multibinds` is what makes
    // this reachable at all -- without it an empty set is a Dagger missing-binding error and the
    // "removing casting costs nothing" claim is false in the one case it is claimed for.
    assertThat(SettingsViewModel(emptySet()).sections).isEmpty()
  }

  private abstract class FakeSection(override val order: Int) : SettingsSection {
    @Composable
    override fun Content() = Unit
  }

  /** Named so that class-name ordering matches position ordering, and the tie test can rely on it. */
  private object First : FakeSection(100)

  private object Second : FakeSection(200)

  private object Third : FakeSection(300)

  /** Claims [First]'s position; loses the tiebreak on class name (`Tied` sorts after `First`). */
  private object Tied : FakeSection(100)
}
