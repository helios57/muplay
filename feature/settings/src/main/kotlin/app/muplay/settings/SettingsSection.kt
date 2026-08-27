package app.muplay.settings

import androidx.compose.runtime.Composable

/**
 * One block of the settings screen, contributed by whichever feature owns the thing being set.
 *
 * **The slot exists so that this module never learns what is in it.** A settings screen that
 * imported every feature it displays would make every feature un-droppable: removing casting would
 * mean editing this module, and Plan 6's definition of done requires that removing casting is
 * `git rm -r core/cast feature/castpicker` and nothing else. Sections arrive through a Hilt
 * `@IntoSet` multibinding, so deleting the module that declares one deletes the binding with it and
 * the set simply gets smaller.
 *
 * That direction is not self-enforcing -- an `implementation(project(":core:cast"))` in this
 * module's build file would compile and work -- so `ConventionTest`'s `the settings slot never
 * learns what is in it` holds it against the tree.
 *
 * ### Implementing one
 *
 * An implementation is an ordinary `@Inject`-constructed class bound `@Binds @IntoSet` in its own
 * module's Hilt module. It may inject whatever it needs; [Content] is composed inside the settings
 * screen's own column, so it should render its own title and use the ambient `MaterialTheme`
 * rather than a `Scaffold` or a surface of its own.
 */
interface SettingsSection {

  /**
   * Where this section sits, ascending.
   *
   * Sparse on purpose (100, 200, ...), so that a section can be inserted between two others without
   * renumbering anything -- which would otherwise mean editing a file in a module that has nothing
   * to do with the new section, i.e. exactly the coupling this interface exists to avoid.
   *
   * Ties are broken by class name, not by set iteration order; see [orderedSections].
   */
  val order: Int

  /** The section's own UI. Composed into the settings screen's column, in [order]. */
  @Composable
  fun Content()
}

/**
 * The sections, in the order they are shown.
 *
 * A separate function, and not a `sortedBy` inlined at the call site, for one reason worth the
 * file: **a `Set` has no order**. Dagger's multibinding hands over a `LinkedHashSet` whose
 * iteration order follows the order Dagger happened to generate its bindings in -- which is stable
 * for a given compilation and not stable across one. So a screen that iterated the set directly
 * would render its sections in an order that changed when an unrelated module was added, and a
 * `sortedBy { it.order }` alone leaves ties resolved by that same accident.
 *
 * The class-name tiebreak makes the result a function of the set's contents and nothing else, which
 * is what lets `SettingsViewModelTest` assert an order at all.
 */
internal fun orderedSections(sections: Set<SettingsSection>): List<SettingsSection> =
  sections.sortedWith(compareBy({ it.order }, { it::class.java.name }))
