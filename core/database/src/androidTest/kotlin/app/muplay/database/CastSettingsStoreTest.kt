package app.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The renderer-direct setting, against a **real** DataStore file on a real device.
 *
 * The pure decision -- what an absent value means -- is gated on the JVM tier by `CastSettingsTest`,
 * deliberately, because that is the mutation that turns a security default inside out and it must
 * not be reachable only from the 45-minute tier. What *this* class is for is everything a plain
 * `Preferences` object cannot show: that the choice reaches the disk, under the name this app will
 * still be reading after an upgrade, and that a store which cannot be read at all answers **off**
 * rather than throwing into a `Player`'s load path.
 *
 * Hermetic: its own file, per run. The production file path, and the fact that it is not the
 * credentials file sign-out empties, are asserted in `DataModuleTest` -- the one class in this
 * module allowed to build the shipped DataStores, because DataStore refuses a second instance over
 * one file in one process.
 */
@RunWith(AndroidJUnit4::class)
class CastSettingsStoreTest {

  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var settings: CastSettings

  @Before
  fun setUp() {
    file = File(context().filesDir, "cast-settings-test-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    settings = CastSettings(dataStore)
  }

  @After
  fun tearDown() {
    // Guarded, per CLAUDE.md: an `@After` that throws replaces the real failure with its own, and
    // `setUp` here can fail before either `lateinit` is assigned.
    if (::file.isInitialized) file.delete()
  }

  @Test
  fun theDefaultIsOffAndThatIsASecurityDecision() = runTest {
    // Asserted rather than assumed. `CastRouter.confirm`'s fallback branch, the `Unroutable`
    // outcome a user actually sees, and spec section 6's corrected Let's Encrypt claim are all
    // only true while this is false -- three arguments resting on one boolean nobody had read.
    assertThat(settings.allowRendererDirect.first()).isFalse()
    assertThat(settings.allowRendererDirectNow()).isFalse()
    assertThat(CastSettings.DEFAULT_ALLOW_RENDERER_DIRECT).isFalse()
  }

  @Test
  fun theStoredValueIsReadBackAndCanBeTurnedOffAgain() = runTest {
    // Both directions. A setter that only ever wrote `true` passes a one-way test, and a user who
    // cannot turn this back off has been handed a one-way security decision.
    settings.setAllowRendererDirect(true)
    assertThat(settings.allowRendererDirect.first()).isTrue()
    assertThat(settings.allowRendererDirectNow()).isTrue()

    settings.setAllowRendererDirect(false)
    assertThat(settings.allowRendererDirect.first()).isFalse()
    assertThat(settings.allowRendererDirectNow()).isFalse()
  }

  @Test
  fun theChoiceReachesTheDiskUnderTheNameTheNextVersionWillLookFor() = runTest {
    // "Persisted" is the claim, and reading it back through the same object cannot see any of it:
    // an in-memory field satisfies both tests above. Two observations the class itself cannot
    // fake -- the raw store holds the value under the documented key, and the key's own name is in
    // the bytes on disk.
    //
    // A second `DataStore` over the same file is not an option and that is not a shortcut being
    // taken: DataStore throws `IllegalStateException: There are multiple DataStores active for the
    // same file` and offers no way to close the first, which is the same constraint that makes
    // `DataModuleTest` the only class allowed to build the shipped stores.
    settings.setAllowRendererDirect(true)

    val raw = dataStore.data.first()[booleanPreferencesKey("cast_allow_renderer_direct")]

    assertThat(raw).describedAs("the value as some later version will read it").isTrue()
    assertThat(String(file.readBytes(), Charsets.ISO_8859_1)).contains("cast_allow_renderer_direct")
  }

  @Test
  fun aSecondInstanceOverTheSameStoreSeesTheChoice() = runTest {
    // The seam the app actually has: `CastSettings` is a `@Singleton`, but nothing stops a second
    // one being constructed over the same binding, and a value cached in a field would diverge
    // between them the moment either one wrote.
    settings.setAllowRendererDirect(true)

    assertThat(CastSettings(dataStore).allowRendererDirect.first()).isTrue()
  }

  @Test
  fun aStoreThatCannotBeReadAnswersOffRatherThanThrowing() = runTest {
    // Fail-closed, and the direction matters. `DataStore.data` throws on a corrupt file, and this
    // flow is read from `CastRouter.confirm` -- a `Player`'s load path, which cannot suspend and
    // has nowhere to put an exception. Propagating would surface a damaged preferences file as a
    // cast that throws, and the obvious repair for *that* is a `runCatching { } ?: true` somewhere
    // upstream. Answering `false` costs the user the feature and never the credential.
    val corruptFile = File(context().filesDir, "cast-corrupt-${System.nanoTime()}.preferences_pb")
    // A length-delimited field claiming 127 bytes that are not there: truncated, so the proto
    // parser refuses it rather than reading it as an empty message.
    corruptFile.writeBytes(byteArrayOf(0x0A, 0x7F))
    val corruptStore = PreferenceDataStoreFactory.create { corruptFile }
    try {
      // Without this the assertion below passes for the wrong reason -- an "empty preferences"
      // parse answers `false` too, and this test would then be measuring nothing.
      assertThatThrownBy { runBlocking { corruptStore.data.first() } }
        .describedAs("the raw store really is unreadable")
        .isInstanceOf(IOException::class.java)

      assertThat(CastSettings(corruptStore).allowRendererDirect.first()).isFalse()
    } finally {
      corruptFile.delete()
    }
  }

  private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()
}
