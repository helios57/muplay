package app.muplay.setup

import app.cash.turbine.test
import app.muplay.model.Album
import app.muplay.model.AlbumListType
import app.muplay.model.AlbumWithSongs
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.ScanStatus
import app.muplay.model.SearchResults
import app.muplay.model.ServerCapabilities
import app.muplay.model.ServerInfo
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicErrorException
import app.muplay.network.SubsonicHttpException
import app.muplay.network.SubsonicSource
import java.io.IOException
import java.net.UnknownServiceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

  /** A minimal hand-written source: nothing but the two commands setup makes. */
  private class StubSource(
    private val pingResult: () -> ServerInfo,
    private val folders: List<MusicLibrary> = emptyList(),
  ) : SubsonicSource {
    override suspend fun ping(): ServerInfo = pingResult()
    override suspend fun getMusicFolders(): List<MusicLibrary> = folders
    override suspend fun getScanStatus(): ScanStatus = error("not used by setup")
    override suspend fun getAlbumList2(musicFolderId: Int, type: AlbumListType, size: Int, offset: Int): List<Album> =
      error("not used by setup")
    override suspend fun getAlbum(albumId: String, musicFolderId: Int): AlbumWithSongs =
      error("not used by setup")
    override suspend fun search3(query: String, musicFolderId: Int, artistCount: Int, albumCount: Int, songCount: Int): SearchResults =
      error("not used by setup")
    override suspend fun getRandomSongs(musicFolderId: Int, size: Int): List<Song> =
      error("not used by setup")
    override fun coverArtUrl(coverArtId: String, sizePx: Int?): String = error("not used by setup")
    override fun streamUrl(songId: String, format: StreamFormat, timeOffsetSeconds: Int?): String =
      error("not used by setup")
    override suspend fun capabilities(): ServerCapabilities = error("not used by setup")
  }

  /**
   * Records what setup stored, in place of the real Keystore-backed store. Appends to the shared
   * [callOrder] on every save -- `connect`'s doc claims credentials are stored *before* libraries
   * are fetched, and a bare `saved != null` check cannot tell that claim from its reverse.
   */
  private class RecordingCredentials(private val callOrder: MutableList<String>) : SetupCredentialSink {
    var saved: SubsonicCredentials? = null
    override suspend fun save(credentials: SubsonicCredentials) {
      saved = credentials
      callOrder += "save"
    }
  }

  /** The library half of the flow, in memory. Also appends to the shared [callOrder]; see above. */
  private class FakeLibraries(private val callOrder: MutableList<String>) : SetupLibrarySink {
    var refreshed = 0
    val roles = mutableMapOf<Int, LibraryRole>()
    var reported: List<MusicLibrary> = emptyList()

    override suspend fun refreshFromServer() {
      refreshed++
      callOrder += "refreshFromServer"
    }
    override suspend fun setRole(musicFolderId: Int, role: LibraryRole) { roles[musicFolderId] = role }
    override suspend fun current(): List<MusicLibrary> =
      reported.map { it.copy(role = roles[it.id] ?: LibraryRole.UNASSIGNED) }
  }

  private val dispatcher = StandardTestDispatcher()
  private val callOrder = mutableListOf<String>()
  private val credentials = RecordingCredentials(callOrder)
  private val libraries = FakeLibraries(callOrder)

  @BeforeEach
  fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel(source: SubsonicSource) =
    SetupViewModel({ source }, credentials, libraries)

  private fun serverInfo() = ServerInfo("navidrome", "0.63.2", "1.16.1", isOpenSubsonic = true)

  @Test
  fun `a blank url is rejected before any network call`() = runTest(dispatcher) {
    val vm = viewModel(StubSource({ error("must not be called") }))

    vm.connect("   ", "admin", "testpass")

    assertThat(vm.uiState.value)
      .isEqualTo(SetupUiState.Failure(SetupFailureReason.InvalidUrl))
    assertThat(credentials.saved).isNull()
  }

  @Test
  fun `the server url is trimmed before it is stored`() = runTest(dispatcher) {
    // `trimmedUrl`, not the raw parameter, is what becomes `SubsonicCredentials.baseUrl` --
    // previously unobserved: the blank-url test passes with or without `.trim()`, because
    // "   ".toHttpUrlOrNull() is null either way, so it alone cannot prove trimming happens.
    libraries.reported = listOf(MusicLibrary(1, "Music", LibraryRole.UNASSIGNED))
    val vm = viewModel(StubSource({ serverInfo() }))

    vm.connect("  http://localhost:4533  ", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(credentials.saved?.baseUrl).isEqualTo("http://localhost:4533")
  }

  @Test
  fun `a successful connect saves the credentials and lists the libraries for tagging`() =
    runTest(dispatcher) {
      libraries.reported = listOf(
        MusicLibrary(1, "Music", LibraryRole.UNASSIGNED),
        MusicLibrary(2, "Audiobooks", LibraryRole.UNASSIGNED),
      )
      val vm = viewModel(StubSource({ serverInfo() }))

      vm.uiState.test {
        assertThat(awaitItem()).isEqualTo(SetupUiState.Idle)
        vm.connect("http://localhost:4533", "admin", "testpass")
        assertThat(awaitItem()).isEqualTo(SetupUiState.Connecting)

        val tagging = awaitItem() as SetupUiState.Tagging
        assertThat(tagging.serverInfo.type).isEqualTo("navidrome")
        // Full equality, not just `.type`: SetupScreen renders serverVersion too
        // ("Connected to ${type} ${serverVersion}"), and a mutant that kept `.type` correct while
        // corrupting any other ServerInfo field previously passed every test.
        assertThat(tagging.serverInfo).isEqualTo(serverInfo())
        assertThat(tagging.libraries.map { it.name }).containsExactly("Music", "Audiobooks")
        // Every library arrives untagged, and the flow cannot be finished until they are not.
        assertThat(tagging.libraries).allMatch { it.role == LibraryRole.UNASSIGNED }
        assertThat(tagging.canContinue).isFalse
        cancelAndIgnoreRemainingEvents()
      }

      assertThat(credentials.saved)
        .isEqualTo(SubsonicCredentials("http://localhost:4533", "admin", "testpass"))
      assertThat(libraries.refreshed).isEqualTo(1)
    }

  @Test
  fun `credentials are stored before the libraries are fetched`() = runTest(dispatcher) {
    // Not an ordering nicety: `LibraryRepository.refreshFromServer` reads the credential store,
    // so fetching first would throw NotConfiguredException on every first run. `callOrder` is
    // what actually proves this -- a bare "both happened" assertion cannot tell this order from
    // its reverse, and previously did not.
    libraries.reported = listOf(MusicLibrary(1, "Music", LibraryRole.UNASSIGNED))
    val vm = viewModel(StubSource({ serverInfo() }))

    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(credentials.saved).isNotNull
    assertThat(libraries.refreshed).isEqualTo(1)
    assertThat(callOrder).containsExactly("save", "refreshFromServer")
  }

  @Test
  fun `tagging every library is what unlocks continuing`() = runTest(dispatcher) {
    libraries.reported = listOf(
      MusicLibrary(1, "Music", LibraryRole.UNASSIGNED),
      MusicLibrary(2, "Audiobooks", LibraryRole.UNASSIGNED),
    )
    val vm = viewModel(StubSource({ serverInfo() }))
    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    vm.setRole(1, LibraryRole.MUSIC)
    dispatcher.scheduler.advanceUntilIdle()
    assertThat((vm.uiState.value as SetupUiState.Tagging).canContinue).isFalse

    vm.setRole(2, LibraryRole.AUDIOBOOKS)
    dispatcher.scheduler.advanceUntilIdle()
    val tagged = vm.uiState.value as SetupUiState.Tagging
    assertThat(tagged.canContinue).isTrue
    assertThat(tagged.libraries.single { it.id == 2 }.role).isEqualTo(LibraryRole.AUDIOBOOKS)
  }

  @Test
  fun `setting a role before any connection has succeeded stores it but touches no screen state`() =
    runTest(dispatcher) {
      // setRole's `serverInfo?.let { ... }` guard is what stops this from crashing: there is no
      // server identity yet to rebuild a Tagging state around. The role is still forwarded --
      // libraries.setRole itself does not depend on serverInfo -- so only the *screen state*
      // update is guarded, not the write.
      val vm = viewModel(StubSource({ error("must not be called") }))

      vm.setRole(1, LibraryRole.MUSIC)
      dispatcher.scheduler.advanceUntilIdle()

      assertThat(vm.uiState.value).isEqualTo(SetupUiState.Idle)
      assertThat(libraries.roles).containsEntry(1, LibraryRole.MUSIC)
    }

  @Test
  fun `continuing before everything is tagged does nothing`() = runTest(dispatcher) {
    // An untagged library is invisible to every browse and shuffle path, so letting the user past
    // this screen would hand them an app that silently shows nothing.
    libraries.reported = listOf(MusicLibrary(1, "Music", LibraryRole.UNASSIGNED))
    val vm = viewModel(StubSource({ serverInfo() }))
    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    vm.continueToLibrary()
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(vm.uiState.value).isInstanceOf(SetupUiState.Tagging::class.java)
  }

  @Test
  fun `continuing once everything is tagged reaches Ready`() = runTest(dispatcher) {
    libraries.reported = listOf(MusicLibrary(1, "Music", LibraryRole.UNASSIGNED))
    val vm = viewModel(StubSource({ serverInfo() }))
    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()
    vm.setRole(1, LibraryRole.MUSIC)
    dispatcher.scheduler.advanceUntilIdle()

    vm.continueToLibrary()
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(vm.uiState.value).isEqualTo(SetupUiState.Ready)
  }

  @Test
  fun `the view model never guesses a role from a library name`() = runTest(dispatcher) {
    libraries.reported = listOf(
      MusicLibrary(1, "Audiobooks", LibraryRole.UNASSIGNED),
      MusicLibrary(2, "Hörbücher", LibraryRole.UNASSIGNED),
      MusicLibrary(3, "Music", LibraryRole.UNASSIGNED),
    )
    val vm = viewModel(StubSource({ serverInfo() }))

    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    val tagging = vm.uiState.value as SetupUiState.Tagging
    assertThat(tagging.libraries).allMatch { it.role == LibraryRole.UNASSIGNED }
    assertThat(tagging.canContinue).isFalse
    assertThat(libraries.roles).isEmpty()
  }

  @Test
  fun `a server with no libraries at all has nothing to continue past`() = runTest(dispatcher) {
    // `current.none { UNASSIGNED }` is vacuously true over an empty list, so canContinue's
    // emptiness guard is the only thing standing between "no libraries" and a false "all tagged".
    libraries.reported = emptyList()
    val vm = viewModel(StubSource({ serverInfo() }))

    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    val tagging = vm.uiState.value as SetupUiState.Tagging
    assertThat(tagging.libraries).isEmpty()
    assertThat(tagging.canContinue).isFalse
  }

  @Test
  fun `continuing with no libraries at all does nothing`() = runTest(dispatcher) {
    // continueToLibrary's own `isNotEmpty() &&` guard, independent of tagging's: `current.none {
    // UNASSIGNED }` is vacuously true over an empty list, so without this guard a server with
    // zero libraries would reach Ready the moment this is called -- proved directly (before the
    // fix) by reaching Ready here.
    libraries.reported = emptyList()
    val vm = viewModel(StubSource({ serverInfo() }))
    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    vm.continueToLibrary()
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(vm.uiState.value).isInstanceOf(SetupUiState.Tagging::class.java)
  }

  @Test
  fun `a rejected sign-in reports the server's own code and stores nothing`() = runTest(dispatcher) {
    val vm = viewModel(StubSource({ throw SubsonicErrorException(40, "Wrong username or password") }))

    vm.connect("http://localhost:4533", "admin", "wrong")
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(vm.uiState.value)
      .isEqualTo(SetupUiState.Failure(SetupFailureReason.Rejected(40, "Wrong username or password")))
    assertThat(credentials.saved).isNull()
  }

  @Test
  fun `a bad http status is a rejection, not an unreachable server`() = runTest(dispatcher) {
    val vm = viewModel(StubSource({ throw SubsonicHttpException(502) }))

    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    val failure = vm.uiState.value as SetupUiState.Failure
    assertThat(failure.reason).isInstanceOf(SetupFailureReason.Rejected::class.java)
    assertThat((failure.reason as SetupFailureReason.Rejected).code).isEqualTo(502)
  }

  @Test
  fun `a transport failure is unreachable`() = runTest(dispatcher) {
    val vm = viewModel(StubSource({ throw IOException("connection refused") }))

    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(vm.uiState.value)
      .isEqualTo(SetupUiState.Failure(SetupFailureReason.Unreachable))
  }

  @Test
  fun `a cleartext-blocked http url names the scheme, not the connection`() = runTest(dispatcher) {
    // What Android really throws when `cleartextTrafficPermitted` is false. It is an IOException,
    // so the `catch (e: Exception)` below used to render it as Unreachable -- "check the URL and
    // your connection" for a URL and a connection that are both fine.
    val vm = viewModel(
      StubSource({
        throw UnknownServiceException(
          "CLEARTEXT communication to music.example.com not permitted by network security policy",
        )
      }),
    )

    vm.connect("http://music.example.com:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(vm.uiState.value)
      .isEqualTo(SetupUiState.Failure(SetupFailureReason.CleartextForbidden("music.example.com")))
  }

  @Test
  fun `a cleartext failure carries the host the user typed, not the one in the message`() =
    runTest(dispatcher) {
      // The host comes from the URL the user entered. Reading it out of the platform's message
      // would tie this to a string Android is free to reword.
      val vm = viewModel(StubSource({ throw UnknownServiceException("CLEARTEXT not permitted") }))

      vm.connect("http://10.0.0.7:4533", "admin", "testpass")
      dispatcher.scheduler.advanceUntilIdle()

      val failure = vm.uiState.value as SetupUiState.Failure
      assertThat((failure.reason as SetupFailureReason.CleartextForbidden).host).isEqualTo("10.0.0.7")
    }

  @Test
  fun `a cancelled connection is never reported as a failure`() = runTest(dispatcher) {
    // CancellationException is a java.lang.Exception, so a `catch (e: Exception)` clause with no
    // earlier, more specific catch would swallow it and report Unreachable -- exactly the bug
    // connect's explicit `catch (e: CancellationException) { throw e }` exists to prevent.
    // Rethrowing inside a launched coroutine is ordinary cooperative cancellation, not an
    // uncaught failure, so this does not fail the test: nothing after the throw ever runs, and
    // uiState is left wherever connect's synchronous half already put it.
    val vm = viewModel(StubSource({ throw CancellationException("cancelled") }))

    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(vm.uiState.value).isEqualTo(SetupUiState.Connecting)
  }
}
