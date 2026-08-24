package app.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicSourceFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var credentialStore: CredentialStore
  private lateinit var source: FakeSubsonicSource
  private lateinit var repository: LibraryRepository

  /**
   * The credentials [SubsonicSourceProvider.current] actually handed the factory on its most
   * recent call. [FakeSubsonicSource] itself is indifferent to credentials -- every
   * `SubsonicSource` method behaves the same regardless -- so without capturing this separately,
   * nothing in this file would notice `current()` passing a hardcoded value instead of whatever
   * [CredentialStore] holds. See `currentPassesWhicheverCredentialsAreActuallyStoredToTheFactory`.
   */
  private var credentialsSeenByFactory: SubsonicCredentials? = null

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    file = File(context.filesDir, "library-repo-test-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    credentialStore = CredentialStore(dataStore)
    source = FakeSubsonicSource()
    repository = LibraryRepository(
      libraryDao = db.libraryDao(),
      sourceProvider = SubsonicSourceProvider(
        credentialStore = credentialStore,
        factory = SubsonicSourceFactory { credentials ->
          credentialsSeenByFactory = credentials
          source
        },
      ),
    )
  }

  @After
  fun tearDown() = runTest {
    credentialStore.clear()
    file.delete()
    db.close()
  }

  private suspend fun signIn() =
    credentialStore.save(SubsonicCredentials("http://localhost:4533", "admin", "testpass"))

  @Test
  fun refreshingWithNoStoredCredentialsFailsLoudly() = runTest {
    // "Not configured" and "the server is down" must not look the same to a caller: only the
    // first is fixable by the user typing a URL, and the setup flow keys off exactly that.
    //
    // `runCatching`, not `assertThatThrownBy { repository.refreshFromServer() }`: AssertJ's
    // `ThrowingCallable` is a Java SAM, not a coroutine context, so a bare `suspend` call cannot
    // appear inside it at all -- the Kotlin compiler rejects that with "Suspension functions can
    // only be called within coroutine body." Wrapping the call in `runBlocking` instead compiles
    // and genuinely still covers the `throw` branch, but it blocks the test thread and takes
    // `refreshFromServer` off `runTest`'s `TestScheduler` -- harmless today only because
    // `CredentialStore.load()` completes on a real dispatcher with nothing to advance virtual
    // time for. `runCatching` stays on the test coroutine and needs no such argument.
    val thrown = runCatching { repository.refreshFromServer() }.exceptionOrNull()
    assertThat(thrown).isInstanceOf(NotConfiguredException::class.java)
  }

  @Test
  fun refreshingStoresEveryLibraryTheServerReports() = runTest {
    signIn()
    source.musicFolders = listOf(
      MusicLibrary(1, "Music", LibraryRole.UNASSIGNED),
      MusicLibrary(2, "Audiobooks", LibraryRole.UNASSIGNED),
    )

    repository.refreshFromServer()

    assertThat(repository.libraries.first().map { it.name })
      .containsExactly("Music", "Audiobooks")
    // `containsExactly`, not `allMatch`: the `containsExactly` on names above already establishes
    // non-emptiness, so this one was guarded even before N-4's fix -- rewritten anyway, to the
    // same standard, since a mapped-field assertion should say what it means rather than lean on
    // a neighbouring assertion to rule out the vacuous case.
    assertThat(repository.libraries.first().map { it.role })
      .containsExactly(LibraryRole.UNASSIGNED, LibraryRole.UNASSIGNED)
  }

  @Test
  fun aRefreshDoesNotDisturbTheRolesTheUserChose() = runTest {
    signIn()
    source.musicFolders = listOf(
      MusicLibrary(1, "Music", LibraryRole.UNASSIGNED),
      MusicLibrary(2, "Audiobooks", LibraryRole.UNASSIGNED),
    )
    repository.refreshFromServer()
    repository.setRole(1, LibraryRole.MUSIC)
    repository.setRole(2, LibraryRole.AUDIOBOOKS)

    repository.refreshFromServer()

    // Two distinct, non-empty values for the same delegating method
    // (`LibraryRepository.idsWithRole` -> `libraryDao.idsWithRole`): a build that ignored the
    // `role` argument and hardcoded `AUDIOBOOKS` would make the MUSIC assertion below fail --
    // before this fix, MUSIC was observed at exactly one value in the whole suite, the empty
    // list, which a hardcoded AUDIOBOOKS answers just as well as a real implementation does.
    assertThat(repository.idsWithRole(LibraryRole.AUDIOBOOKS)).containsExactly(2)
    assertThat(repository.idsWithRole(LibraryRole.MUSIC)).containsExactly(1)
  }

  @Test
  fun unassignedLibrariesAreReportedUntilEveryOneIsTagged() = runTest {
    signIn()
    source.musicFolders = listOf(
      MusicLibrary(1, "Music", LibraryRole.UNASSIGNED),
      MusicLibrary(2, "Audiobooks", LibraryRole.UNASSIGNED),
    )
    repository.refreshFromServer()

    assertThat(repository.hasUnassignedLibraries()).isTrue
    repository.setRole(1, LibraryRole.MUSIC)
    assertThat(repository.hasUnassignedLibraries()).isTrue
    repository.setRole(2, LibraryRole.AUDIOBOOKS)
    assertThat(repository.hasUnassignedLibraries()).isFalse
  }

  @Test
  fun theRepositoryNeverGuessesARoleFromALibraryName() = runTest {
    signIn()
    // The names most likely to tempt a name-matching heuristic, in two languages. Every one of
    // them must still come back UNASSIGNED: a wrong guess here is silent and its only symptom is
    // audiobooks appearing in a music shuffle.
    source.musicFolders = listOf(
      MusicLibrary(1, "Audiobooks", LibraryRole.UNASSIGNED),
      MusicLibrary(2, "Hörbücher", LibraryRole.UNASSIGNED),
      MusicLibrary(3, "Music", LibraryRole.UNASSIGNED),
    )

    repository.refreshFromServer()

    // `containsExactly`, not `allMatch`: `allMatch` (and `isEmpty()` below) are true vacuously on
    // an empty result, so a `refreshFromServer` that silently stored nothing would pass this test
    // too. Mapping to the exact expected list of three UNASSIGNED roles is only true if three
    // rows actually landed.
    assertThat(repository.libraries.first().map { it.role })
      .containsExactly(LibraryRole.UNASSIGNED, LibraryRole.UNASSIGNED, LibraryRole.UNASSIGNED)
    assertThat(repository.idsWithRole(LibraryRole.AUDIOBOOKS)).isEmpty()
    assertThat(repository.idsWithRole(LibraryRole.MUSIC)).isEmpty()
  }

  /**
   * `libraries` maps `LibraryEntity` to `MusicLibrary` field by field
   * (`id = it.musicFolderId, name = it.name, role = it.role`), and no test above pins `id` or
   * `role` on that specific mapping to anything but a single, shared value -- every fixture
   * above uses ids 1/2 uniformly and UNASSIGNED uniformly, so a build that hardcoded either
   * (`id = 1` for every row, or `role = UNASSIGNED` for every row) would still pass every test
   * above. This attributes two *different* roles to two *different* ids and checks both are
   * attached to the right one.
   */
  @Test
  fun theLibrariesFlowReportsEachLibrarysOwnIdAndRoleNotAConstant() = runTest {
    signIn()
    source.musicFolders = listOf(
      MusicLibrary(1, "Music", LibraryRole.UNASSIGNED),
      MusicLibrary(2, "Audiobooks", LibraryRole.UNASSIGNED),
    )
    repository.refreshFromServer()
    repository.setRole(1, LibraryRole.MUSIC)
    repository.setRole(2, LibraryRole.AUDIOBOOKS)

    val byId = repository.libraries.first().associateBy { it.id }

    assertThat(byId.keys).containsExactlyInAnyOrder(1, 2)
    assertThat(byId.getValue(1).role).isEqualTo(LibraryRole.MUSIC)
    assertThat(byId.getValue(2).role).isEqualTo(LibraryRole.AUDIOBOOKS)
  }

  /**
   * `refreshFromServer`'s own mapping (`LibraryEntity(musicFolderId = it.id, name = it.name,
   * role = it.role)`) passes the source's reported role straight through for a brand-new row --
   * `mergeFromServer` only ever *writes* that role when the row does not already exist. Every
   * other test in this file feeds `refreshFromServer` a `MusicLibrary` whose role is
   * `UNASSIGNED` (matching what the real `SubsonicClient` always reports), so a hardcoded
   * `role = LibraryRole.UNASSIGNED` in that mapping would pass every one of them. Feeding a role
   * no real server response would carry is what makes this a test of the passthrough itself
   * rather than of what `SubsonicClient` happens to send today.
   */
  @Test
  fun refreshFromServerPassesTheSourcesReportedRoleThroughForABrandNewLibrary() = runTest {
    signIn()
    source.musicFolders = listOf(MusicLibrary(9, "Whatever the source reports", LibraryRole.MUSIC))

    repository.refreshFromServer()

    assertThat(repository.libraries.first().first { it.id == 9 }.role)
      .isEqualTo(LibraryRole.MUSIC)
  }

  /**
   * `allIds()` was in the brief's own `Produces` list but had no test at all -- JaCoCo measured
   * it 0/1 LINE, invisible behind `LibraryRepository`'s other, well-exercised lines. It is the
   * "every library" scope a cross-library search or a shuffle-everything path will iterate; a
   * build where it returned the wrong set produced silently empty results with nothing here
   * going red.
   */
  @Test
  fun allIdsReportsEveryLibraryRegardlessOfRole() = runTest {
    signIn()
    source.musicFolders = listOf(
      MusicLibrary(1, "Music", LibraryRole.UNASSIGNED),
      MusicLibrary(2, "Audiobooks", LibraryRole.UNASSIGNED),
    )
    repository.refreshFromServer()
    repository.setRole(1, LibraryRole.MUSIC)
    repository.setRole(2, LibraryRole.AUDIOBOOKS)

    assertThat(repository.allIds()).containsExactly(1, 2)
  }

  /**
   * [SubsonicSourceProvider.current] is supposed to build its [app.muplay.network.SubsonicSource]
   * from *whatever credentials are stored right now* -- not a value fixed at construction time.
   * [FakeSubsonicSource] answers identically no matter what credentials it was built with, so no
   * assertion on server responses could ever distinguish a correct implementation from one that
   * quietly always uses the first credentials it ever saw (or a hardcoded value). Two different,
   * sequentially-stored credential sets, each captured at the moment `refreshFromServer` runs, is
   * what makes the passthrough itself the thing under test.
   */
  @Test
  fun currentPassesWhicheverCredentialsAreActuallyStoredToTheFactory() = runTest {
    source.musicFolders = emptyList()
    val first = SubsonicCredentials("http://first.invalid", "alice", "pw1")
    val second = SubsonicCredentials("http://second.invalid", "bob", "pw2")

    credentialStore.save(first)
    repository.refreshFromServer()
    val firstSeen = credentialsSeenByFactory

    credentialStore.save(second)
    repository.refreshFromServer()
    val secondSeen = credentialsSeenByFactory

    assertThat(firstSeen).isEqualTo(first)
    assertThat(secondSeen).isEqualTo(second)
  }
}
