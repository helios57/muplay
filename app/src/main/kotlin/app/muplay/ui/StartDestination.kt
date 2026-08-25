package app.muplay.ui

/**
 * Where the app opens.
 *
 * [Setup] covers two different situations on purpose — no credentials at all, and credentials
 * with a library still untagged. Both need the same screen, and an untagged library is not a
 * lesser problem: it is invisible to every browse and shuffle path, so opening the library screen
 * with one outstanding would show a user an app that silently does nothing.
 */
sealed interface StartDestination {
  data object Loading : StartDestination
  data object Setup : StartDestination
  data object Library : StartDestination
}
