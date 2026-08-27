package app.muplay.media.cast

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.proxy.ProxyRegistry
import app.muplay.cast.route.CastRoute
import app.muplay.database.CastSettings
import app.muplay.media.di.MediaModule
import java.io.File
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bridge between the stored setting and the routing rule, on a real DataStore.
 *
 * `MediaModuleTest` proves the router obeys whatever this policy answers, from the JVM tier, by
 * handing it a lambda. What no JVM test can reach is the shipped implementation of that lambda:
 * `StoredRendererDirectPolicy` reads DataStore, DataStore needs a file, and this project runs no
 * Robolectric. So the two halves of the wire are gated on two tiers, and the join -- *the policy
 * the graph binds actually returns what the user last chose* -- is here.
 *
 * The defect this is aimed at is not "it returns the wrong constant". It is **staleness**: a policy
 * that read the store once and cached it satisfies every assertion about a single value, and fails
 * only when a user changes the setting and casts without restarting the app. Every test below that
 * writes twice exists for that reason.
 */
@RunWith(AndroidJUnit4::class)
class StoredRendererDirectPolicyTest {

  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var settings: CastSettings
  private lateinit var policy: RendererDirectPolicy

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    file = File(context.filesDir, "renderer-direct-policy-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    settings = CastSettings(dataStore)
    policy = StoredRendererDirectPolicy(settings)
  }

  @After
  fun tearDown() {
    // Guarded, per CLAUDE.md: an `@After` that throws over an unassigned `lateinit` replaces the
    // real failure with its own and the cause never appears in the report.
    if (::file.isInitialized) file.delete()
  }

  @Test
  fun aFreshInstallAnswersNo() {
    assertThat(policy.isAllowed()).isFalse()
  }

  @Test
  fun theAnswerFollowsTheStoredChoiceInBothDirections(): Unit = runBlocking {
    settings.setAllowRendererDirect(true)
    assertThat(policy.isAllowed()).isTrue()

    settings.setAllowRendererDirect(false)
    assertThat(policy.isAllowed()).isFalse()
  }

  @Test
  fun theAnswerIsReReadEveryTime(): Unit = runBlocking {
    // The staleness assertion, stated as a sequence on ONE policy object. A policy that resolved
    // the store in its constructor -- or in a `by lazy`, or into a cached field -- passes both
    // tests above and fails here, which is the only place the difference is visible.
    assertThat(policy.isAllowed()).isFalse()

    settings.setAllowRendererDirect(true)

    assertThat(policy.isAllowed()).isTrue()
  }

  @Test
  fun theRouterBuiltOverThisPolicyChangesItsAnswerWithTheSetting(): Unit = runBlocking {
    // The join, end to end, through the production provider: one `@Singleton` router, one policy,
    // and the setting changing underneath both. Nothing here fakes the boolean.
    //
    // The renderer is a URL nothing listens on, so `confirm` always reaches the fallback -- which
    // is the branch the setting decides and the only one worth driving here.
    val registry = ProxyRegistry()
    val proxy = MediaModule.provideMediaProxyServer(
      MediaModule.provideProxyUpstream(
        MediaModule.provideMediaCallFactory(),
      ),
      registry,
    )
    proxy.use {
      val router = MediaModule.provideCastRouter(it, registry, policy)

      val refused = router.confirm(router.candidate(device(), UPSTREAM, MP3), UPSTREAM)
      assertThat(refused).isInstanceOf(CastRoute.Unroutable::class.java)
      // The credential-bearing URL is not in what a user reads, and never in a log or a report.
      assertThat((refused as CastRoute.Unroutable).detail).doesNotContain(UPSTREAM)

      settings.setAllowRendererDirect(true)

      val allowed = router.confirm(router.candidate(device(), UPSTREAM, MP3), UPSTREAM)
      assertThat(allowed).isEqualTo(CastRoute.RendererDirect(UPSTREAM))
    }
  }

  private fun device() = CastDevice(
    udn = "uuid:test",
    friendlyName = "Test Speaker",
    manufacturer = null,
    modelName = null,
    descriptionUrl = URI("http://127.0.0.1:1/desc.xml"),
    avTransportControlUrl = URI("http://127.0.0.1:1/av"),
    avTransportScpdUrl = null,
    renderingControlUrl = null,
    isSonos = false,
  )

  private companion object {
    val MP3 = ServedMedia("audio/mpeg", "mp3")

    /**
     * Shaped like Navidrome's and carrying **no** authentication parameters, not even fabricated
     * ones: a stream URL's `t` and `s` are password equivalents and this repository does not write
     * them down, in a test or anywhere else.
     */
    const val UPSTREAM = "https://nav.example/rest/stream?id=1&format=raw"
  }
}
