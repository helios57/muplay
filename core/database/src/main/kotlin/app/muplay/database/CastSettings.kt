package app.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The cast subsystem's one persisted **choice**: may a speaker be handed the Navidrome stream URL
 * directly.
 *
 * ### What turning this on actually means
 *
 * A Subsonic stream URL is `/rest/stream?id=...&u=...&t=...&s=...`. The `t` and `s` pair is
 * `md5(password + salt)` and the salt that made it -- a **password equivalent**, and one that does
 * not expire. Handing that URL to a renderer hands it to a device that logs URLs, on a network the
 * user may not own. That is the whole reason this is a stored choice with a sentence beside it
 * rather than a fallback the routing rule takes on its own; `CastRoute.RendererDirect` and
 * `CastRouter.confirm` carry the other half of the argument.
 *
 * Nothing in this class, in its tests, or in any fixture in this repository writes such a URL
 * down. The value stored here is a `Boolean`.
 *
 * ### Why the class is not called `CastPreferences`
 *
 * Plan 6 Task 12's brief names it that. **That name is taken, in this very package**, by the
 * DataStore *qualifier* [CastPreferences] -- the annotation that distinguishes the cast file from
 * the credentials file (see [RendererStore]'s own documentation for why a separate file, not
 * merely a separate key, is required). A `class CastPreferences` beside it is a Kotlin
 * redeclaration error, so the class is `CastSettings` and the qualifier keeps its name.
 *
 * ### Why the reading half is a `companion` function
 *
 * [readAllowRendererDirect] is the `?:` that decides the default, and it is deliberately reachable
 * **without a device**: a `DataStore` needs a file and therefore an emulator, but
 * `androidx.datastore.preferences.core.Preferences` is plain Kotlin and can be built on the JVM.
 * That places this project's single most dangerous mutation -- flipping the default from `false`
 * to `true`, which turns a security decision inside out while every route still "works" -- on
 * Tier 1, where `check` and `ci/mutation-probes.sh` can both see it. `CastSettingsTest` (JVM) and
 * `CastSettingsStoreTest` (instrumented) are the two halves.
 */
@Singleton
class CastSettings @Inject constructor(
  @CastPreferences private val dataStore: DataStore<Preferences>,
) {

  /**
   * Whether a renderer may be given the upstream Navidrome URL. `false` until a user says
   * otherwise, including when the store cannot be read at all.
   *
   * The `catch` is a **fail-closed** rule, not boilerplate. `DataStore.data` throws on a corrupt
   * or unreadable file (`CorruptionException` is an `IOException`), and the two available
   * behaviours there are "propagate, and let the caller decide" and "answer with the default".
   * Propagating would put the decision in `CastRouter.confirm`, whose caller is a `Player`'s load
   * path -- so an unreadable preferences file would surface as a cast that throws, and the obvious
   * repair for *that* is a `runCatching { ... } ?: true` somewhere upstream. Answering `false`
   * here means a damaged file costs the user the *feature* and never the credential.
   *
   * Anything that is not an `IOException` is rethrown: a `CancellationException` swallowed here
   * would make this flow uncancellable, and a programming error should be loud.
   */
  val allowRendererDirect: Flow<Boolean> = dataStore.data
    .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
    .map(::readAllowRendererDirect)

  /** Stores the choice. Both directions: a user who cannot turn this back off never chose it. */
  suspend fun setAllowRendererDirect(allowed: Boolean) {
    dataStore.edit { preferences -> preferences[ALLOW_RENDERER_DIRECT] = allowed }
  }

  /**
   * The current value, read now.
   *
   * Exists so that the routing rule can consult the setting **at the moment it needs it** rather
   * than at the moment the object graph was assembled -- see `MediaModule.provideCastRouter`. A
   * user who turns the switch on and immediately casts must get the answer they just chose.
   */
  suspend fun allowRendererDirectNow(): Boolean = allowRendererDirect.first()

  companion object {

    /**
     * **Off.** Asserted rather than assumed, in three places, because three separate arguments
     * rest on it: `CastRouter.confirm`'s fallback branch, `UnroutableReason
     * .PROXY_UNREACHABLE_AND_DIRECT_DISABLED` being the outcome a user actually sees, and spec
     * section 6's corrected claim that the Let's Encrypt trust question is *deferred* by default.
     * All three become false the moment this becomes `true`, and none of them would fail.
     */
    const val DEFAULT_ALLOW_RENDERER_DIRECT: Boolean = false

    /**
     * The key as it lands on disk, pinned by a test.
     *
     * A rename round-trips perfectly with itself -- the writer and the reader move together -- and
     * silently forgets the choice on upgrade. For most preferences that is a papercut; for this
     * one, forgetting means reverting to off, which is the safe direction, and that is exactly why
     * nothing would ever notice.
     */
    internal val ALLOW_RENDERER_DIRECT = booleanPreferencesKey("cast_allow_renderer_direct")

    /**
     * The stored answer, or [DEFAULT_ALLOW_RENDERER_DIRECT] when nothing is stored.
     *
     * `internal` and on the companion so that the JVM tier can execute it; see this class's own
     * header for why that placement is the point rather than an accident.
     */
    internal fun readAllowRendererDirect(preferences: Preferences): Boolean =
      preferences[ALLOW_RENDERER_DIRECT] ?: DEFAULT_ALLOW_RENDERER_DIRECT
  }
}
