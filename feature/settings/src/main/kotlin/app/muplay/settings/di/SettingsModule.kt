package app.muplay.settings.di

import app.muplay.settings.SettingsSection
import dagger.Module
import dagger.multibindings.Multibinds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Declares the section slot, and declares that it is allowed to be **empty**.
 *
 * `@Multibinds` is the whole reason this file exists. Without it, a `Set<SettingsSection>` with no
 * `@IntoSet` contributions is not an empty set -- it is a *missing binding*, and the build fails
 * with `[Dagger/MissingBinding] java.util.Set<SettingsSection> cannot be provided`. So a build
 * with casting removed (`git rm -r core/cast feature/castpicker`, which is the severability
 * contract Plan 6's definition of done item 5 states) would not compile, and the "settings screen
 * loses a section without noticing" property would be false in the one situation it is claimed for.
 *
 * An `interface` with an `abstract` method rather than an `object` with a `@Provides`: an abstract
 * multibinding declaration compiles to no executable line, so it costs this module's LINE floor
 * nothing. `MediaModule.Bindings` and `DataModule.Bindings` carry the same shape for the same
 * measured reason.
 */
@Module
@InstallIn(SingletonComponent::class)
interface SettingsModule {

  @Multibinds
  fun settingsSections(): Set<SettingsSection>
}
