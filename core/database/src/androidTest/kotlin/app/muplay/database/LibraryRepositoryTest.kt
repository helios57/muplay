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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
        factory = SubsonicSourceFactory { source },
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
    // `runBlocking` here (not a plain call): `assertThatThrownBy`'s `ThrowingCallable` is a Java
    // SAM, not a coroutine context, so a `suspend` call cannot appear directly inside it -- the
    // Kotlin compiler rejects that with "Suspension functions can only be called within
    // coroutine body." `runBlocking` gives the lambda a coroutine context of its own without
    // changing what is being asserted.
    assertThatThrownBy { runBlocking { repository.refreshFromServer() } }
      .isInstanceOf(NotConfiguredException::class.java)
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
    assertThat(repository.libraries.first()).allMatch { it.role == LibraryRole.UNASSIGNED }
  }

  @Test
  fun aRefreshDoesNotDisturbTheRolesTheUserChose() = runTest {
    signIn()
    source.musicFolders = listOf(
      MusicLibrary(1, "Music", LibraryRole.UNASSIGNED),
      MusicLibrary(2, "Audiobooks", LibraryRole.UNASSIGNED),
    )
    repository.refreshFromServer()
    repository.setRole(2, LibraryRole.AUDIOBOOKS)

    repository.refreshFromServer()

    assertThat(repository.idsWithRole(LibraryRole.AUDIOBOOKS)).containsExactly(2)
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

    assertThat(repository.libraries.first()).allMatch { it.role == LibraryRole.UNASSIGNED }
    assertThat(repository.idsWithRole(LibraryRole.AUDIOBOOKS)).isEmpty()
    assertThat(repository.idsWithRole(LibraryRole.MUSIC)).isEmpty()
  }
}
