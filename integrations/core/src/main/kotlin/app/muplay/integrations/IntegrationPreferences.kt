package app.muplay.integrations

import javax.inject.Qualifier

/**
 * Distinguishes the integrations' DataStore from `:core:database`'s unqualified one. Without it
 * Hilt sees two bindings for `DataStore<Preferences>` and fails the build — which is the correct
 * behaviour, and this qualifier is the answer to it rather than a workaround.
 *
 * A qualifier alone would not have been enough, and `CastPreferences` in `:core:database` already
 * records why: the two bindings must also name two different **files**. `CredentialStore.clear()`
 * is `dataStore.edit { it.clear() }` — it empties the whole file, not its own keys — so an
 * integration sharing that file would be forgotten by a Navidrome sign-out, and the symptom would
 * read as an integrations bug.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IntegrationPreferences
