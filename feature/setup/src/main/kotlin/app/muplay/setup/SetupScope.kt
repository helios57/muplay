package app.muplay.setup

import javax.inject.Qualifier

/**
 * The scope [ServerSection]'s sign-out runs on.
 *
 * Its own qualifier rather than a shared application scope, for the reason `@CastPickerScope` is
 * its own: a scope named by two features is a scope neither of them can reason about the lifetime
 * of, and this one has exactly one job whose failure mode is a credential left on the device.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SetupScope
