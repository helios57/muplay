package app.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.RememberedRenderer
import app.muplay.model.RememberedRenderers
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The remembered-speaker store, against a **real** DataStore file on a real device.
 *
 * A JVM test cannot exercise DataStore at all, and the two failures this class exists to catch --
 * a record format that cannot survive a name a device chose, and a store that grows without bound
 * as a phone moves between networks -- are both properties of what actually lands on disk.
 *
 * Hermetic: its own file, per run. The *production* file path, and the fact that it is not the
 * credentials file, are asserted in `DataModuleTest`, which is the one place in this module
 * allowed to build the shipped DataStores (DataStore refuses a second instance over one file).
 */
@RunWith(AndroidJUnit4::class)
class RendererStoreTest {

  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var store: RendererStore

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    file = File(context.filesDir, "renderer-store-test-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    store = RendererStore(dataStore)
  }

  @After
  fun tearDown() {
    file.delete()
  }

  @Test
  fun nothingIsRememberedBeforeAnythingIsSaved() = runTest {
    assertThat(store.load()).isEmpty()
  }

  @Test
  fun remembersEveryFieldOfEveryDevice() = runTest {
    store.remember(
      listOf(
        device("uuid:a", "Küche", "http://10.0.0.1:1400/d.xml"),
        device("uuid:b", "Study", "http://10.0.0.2:2869/x.xml"),
      ),
    )

    // The exact set of triples, field by field. `hasSize(2)` passes with both names blank, and
    // two devices are what makes a field read from the wrong record visible.
    assertThat(store.load().map { it.udn }).containsExactlyInAnyOrder("uuid:a", "uuid:b")
    assertThat(store.load().single { it.udn == "uuid:a" }.friendlyName).isEqualTo("Küche")
    assertThat(store.load().single { it.udn == "uuid:a" }.descriptionUrl)
      .isEqualTo("http://10.0.0.1:1400/d.xml")
    assertThat(store.load().single { it.udn == "uuid:b" }.friendlyName).isEqualTo("Study")
    assertThat(store.load().single { it.udn == "uuid:b" }.descriptionUrl)
      .isEqualTo("http://10.0.0.2:2869/x.xml")
  }

  /**
   * The record format itself, and the preference key it lives under, both pinned against the raw
   * DataStore.
   *
   * Reading it back through `load()` alone cannot see either: a store that wrote its fields in a
   * different order, or under a different key, round-trips perfectly with itself and loses every
   * remembered speaker on the upgrade that changes it. The field order is load-bearing separately
   * -- the friendly name is last precisely so `limit = 3` can rejoin it.
   */
  @Test
  fun theOnDiskRecordIsAUdnThenUrlThenNameTriple() = runTest {
    store.remember(listOf(device("uuid:a", "Küche", "http://10.0.0.1:1400/d.xml")))

    val raw = dataStore.data.first()[stringSetPreferencesKey("remembered_renderers")]

    assertThat(raw).containsExactly("uuid:a\thttp://10.0.0.1:1400/d.xml\tKüche")
  }

  @Test
  fun aNameContainingTheSeparatorSurvives() = runTest {
    // The record format's one sharp edge, pinned. Without `limit = 3` this comes back truncated.
    store.remember(listOf(device("uuid:t", "Kitchen\tSpeaker", "http://10.0.0.3/d.xml")))

    assertThat(store.load().single().friendlyName).isEqualTo("Kitchen\tSpeaker")
    assertThat(store.load().single().descriptionUrl).isEqualTo("http://10.0.0.3/d.xml")
  }

  @Test
  fun rememberingReplacesRatherThanAccumulating() = runTest {
    store.remember(listOf(device("uuid:a", "A", "http://10.0.0.1/d.xml")))
    store.remember(listOf(device("uuid:b", "B", "http://10.0.0.2/d.xml")))

    // A store that merged would grow without bound as a phone moved between networks.
    assertThat(store.load().map { it.udn }).containsExactly("uuid:b")
  }

  @Test
  fun forgettingRemovesOneAndKeepsTheRest() = runTest {
    store.remember(
      listOf(
        device("uuid:a", "A", "http://10.0.0.1/d.xml"),
        device("uuid:b", "B", "http://10.0.0.2/d.xml"),
      ),
    )

    store.forget("uuid:a")

    assertThat(store.load().map { it.udn }).containsExactly("uuid:b")
    assertThat(store.load().single().friendlyName).isEqualTo("B")
  }

  /**
   * The other observation of the same predicate. A `forget` that cleared the key outright passes
   * the test above whenever it is read with a single-element assertion, and empties the store.
   */
  @Test
  fun forgettingSomethingThatWasNeverThereKeepsEverything() = runTest {
    store.remember(
      listOf(
        device("uuid:a", "A", "http://10.0.0.1/d.xml"),
        device("uuid:b", "B", "http://10.0.0.2/d.xml"),
      ),
    )

    store.forget("uuid:never-seen")

    assertThat(store.load().map { it.udn }).containsExactlyInAnyOrder("uuid:a", "uuid:b")
  }

  @Test
  fun theStoreIsBoundedAndKeepsTheOnesItWasGivenFirst() = runTest {
    store.remember((1..40).map { device("uuid:$it", "Speaker $it", "http://10.0.0.$it/d.xml") })

    // Which sixteen, not just how many: `RendererDirectory` puts the devices that answered at the
    // front of this list precisely so a `takeLast` here would evict them. `hasSize(16)` passes
    // against either end.
    assertThat(store.load()).hasSize(RememberedRenderers.MAX_REMEMBERED)
    assertThat(store.load().map { it.udn })
      .containsExactlyInAnyOrderElementsOf((1..RememberedRenderers.MAX_REMEMBERED).map { "uuid:$it" })
  }

  /**
   * A record this version cannot read must be skipped, not crash the picker. The store is a set of
   * strings on disk; a downgrade, a partial write or a future format all produce one, and
   * `parts[2]` on a two-field record is an `IndexOutOfBoundsException` inside a `Flow` collected
   * by the UI.
   */
  @Test
  fun aRecordThatIsNotAWholeTripleIsSkippedAndItsNeighboursAreKept() = runTest {
    dataStore.edit {
      it[stringSetPreferencesKey("remembered_renderers")] = setOf(
        "uuid:good\thttp://10.0.0.1/d.xml\tKitchen",
        "uuid:truncated\thttp://10.0.0.2/d.xml",
        "",
      )
    }

    assertThat(store.load().map { it.udn }).containsExactly("uuid:good")
    assertThat(store.load().single().friendlyName).isEqualTo("Kitchen")
  }

  private fun device(udn: String, friendlyName: String, descriptionUrl: String) =
    RememberedRenderer(udn = udn, friendlyName = friendlyName, descriptionUrl = descriptionUrl)
}
