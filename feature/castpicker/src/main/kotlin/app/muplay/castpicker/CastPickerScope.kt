package app.muplay.castpicker

import javax.inject.Qualifier

/**
 * The application-lifetime scope this module's settings writes run on.
 *
 * Qualified rather than unqualified for the reason `:core:media`'s `@CastCommands` is: an
 * unqualified `CoroutineScope` binding is the sort two modules add independently, and Dagger then
 * refuses the whole graph with a duplicate-binding error that names neither of them usefully.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CastPickerScope
