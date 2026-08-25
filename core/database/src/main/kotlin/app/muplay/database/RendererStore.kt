package app.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.muplay.model.RememberedRenderer
import app.muplay.model.RememberedRenderers
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Remembered renderers, in DataStore.
 *
 * **Not a Room table, and that is a decision rather than an omission.** This is a bounded list of
 * at most [RememberedRenderers.MAX_REMEMBERED] flat records that is read once when the picker
 * opens and written once when it closes. There is no query, no join, no ordering requirement and
 * no relationship. A Room table would cost a schema version bump and a migration, and Plan 6's
 * standing rule is that a migration in this plan means something has been added that belongs to
 * another one.
 *
 * Records are stored as tab-separated triples in a `Set<String>`, because DataStore Preferences
 * has no list type and adding kotlinx.serialization to `:core:database` for three fields would be
 * a dependency bought for nothing. The separator is a tab: a UDN is a `uuid:` URN and a
 * description URL is a URL, and neither can contain one, while a **friendly name can contain
 * almost anything** -- which is why the name is stored **last** and re-joined on read rather than
 * split blindly.
 *
 * A `Set` does not preserve order, and nothing here needs it to: `RendererDirectory` sorts the
 * picker itself, because arrival order is a property of the network rather than of the store.
 */
@Singleton
class RendererStore @Inject constructor(
  @CastPreferences private val dataStore: DataStore<Preferences>,
) : RememberedRenderers {

  override suspend fun load(): List<RememberedRenderer> =
    dataStore.data.first()[KEY].orEmpty().mapNotNull(::decode)

  override suspend fun remember(renderers: List<RememberedRenderer>) {
    val encoded = renderers.take(RememberedRenderers.MAX_REMEMBERED).map(::encode).toSet()
    // Replaces rather than merges. A phone that moved between five networks would otherwise
    // accumulate every speaker it had ever seen, and the bound above would then evict the ones
    // that are actually on the air.
    dataStore.edit { it[KEY] = encoded }
  }

  override suspend fun forget(udn: String) {
    dataStore.edit { preferences ->
      preferences[KEY] = preferences[KEY].orEmpty()
        .filterNot { decode(it)?.udn == udn }
        .toSet()
    }
  }

  private fun encode(renderer: RememberedRenderer): String =
    listOf(renderer.udn, renderer.descriptionUrl, renderer.friendlyName).joinToString(SEPARATOR)

  private fun decode(record: String): RememberedRenderer? {
    // `limit = 3`, so a friendly name containing a tab rejoins into the third field instead of
    // truncating the record. Names come from a device on someone else's network; assuming they
    // are well behaved is how a picker ends up with an entry called "Kü".
    val parts = record.split(SEPARATOR, limit = 3)
    if (parts.size != 3) return null
    return RememberedRenderer(udn = parts[0], descriptionUrl = parts[1], friendlyName = parts[2])
  }

  private companion object {
    val KEY = stringSetPreferencesKey("remembered_renderers")
    const val SEPARATOR = "\t"
  }
}

/**
 * Distinguishes the cast store's DataStore from `:core:database`'s unqualified one, which holds
 * the Navidrome password and is cleared wholesale on sign-out.
 *
 * **The qualifier is not decoration.** `CredentialStore.clear()` is `dataStore.edit { it.clear() }`
 * -- it empties its *file*, not its own keys -- so a `RendererStore` sharing that file would lose
 * every remembered speaker on sign-out. The symptom would be the "not answering" list going empty,
 * which reads as a discovery bug and would be chased in `RendererDirectory`.
 *
 * A separate *file* is required, not merely a separate name: a qualifier over the same path would
 * still be wiped by `clear()`, and DataStore throws
 * `IllegalStateException: There are multiple DataStores active for the same file` if two instances
 * share one. A separate binding then requires this qualifier, because two unqualified
 * `DataStore<Preferences>` bindings are a Hilt duplicate-binding failure.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CastPreferences
