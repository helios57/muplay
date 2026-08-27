package app.muplay.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * The settings screen's contents: whatever sections the graph contains, in a stated order.
 *
 * There is no state to hold and nothing to load, so this is a very thin ViewModel -- and it is here
 * rather than an `EntryPoint` read from the composable because injecting a multibound set into a
 * `@Composable` needs one or the other, and this is the shape every other screen in this project
 * already uses.
 *
 * `Set<@JvmSuppressWildcards SettingsSection>` is not decoration: without it Kotlin generates
 * `Set<? extends SettingsSection>` for the constructor parameter, and Dagger's multibinding key is
 * `Set<SettingsSection>` -- the two do not match and the build fails with a missing binding for a
 * type that is visibly present.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
  sections: Set<@JvmSuppressWildcards SettingsSection>,
) : ViewModel() {

  /**
   * The sections to render, ordered.
   *
   * A `val` computed once rather than a `Flow`: the set is fixed at graph-construction time, and a
   * section's own state is the section's business (`RendererDirectSection` collects its preference
   * itself). A `StateFlow` here would be a second, always-constant source of truth.
   */
  val sections: List<SettingsSection> = orderedSections(sections)
}
