package app.muplay.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.dao.LibraryDao
import app.muplay.database.entity.LibraryEntity
import app.muplay.model.LibraryRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDaoTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var dao: LibraryDao

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      MuPlayDatabase::class.java,
    ).build()
    dao = db.libraryDao()
  }

  @After
  fun tearDown() = db.close()

  private fun unassigned(id: Int, name: String) = LibraryEntity(id, name, LibraryRole.UNASSIGNED)

  @Test
  fun librariesArriveFromTheServerUnassigned() = runTest {
    dao.mergeFromServer(listOf(unassigned(1, "Music"), unassigned(2, "Audiobooks")))

    // Nothing in a Subsonic response says what a library is *for*, so this is the only correct
    // starting state -- not a placeholder, and not something to guess from the names above.
    assertThat(dao.idsWithRole(LibraryRole.AUDIOBOOKS)).isEmpty()
    assertThat(dao.idsWithRole(LibraryRole.MUSIC)).isEmpty()
    assertThat(dao.idsWithRole(LibraryRole.UNASSIGNED)).containsExactly(1, 2)
  }

  @Test
  fun taggingIsWhatMakesALibraryAnAudiobookLibrary() = runTest {
    dao.mergeFromServer(listOf(unassigned(1, "Music"), unassigned(2, "Audiobooks")))

    dao.setRole(2, LibraryRole.AUDIOBOOKS)
    dao.setRole(1, LibraryRole.MUSIC)

    assertThat(dao.idsWithRole(LibraryRole.AUDIOBOOKS)).containsExactly(2)
    assertThat(dao.idsWithRole(LibraryRole.MUSIC)).containsExactly(1)
  }

  /**
   * The requirement a naive `@Upsert` silently breaks: a re-sync must update a library's name and
   * add a new library without touching the role the user chose. Getting this wrong un-tags
   * someone's audiobook library behind their back, and the only symptom is audiobooks turning up
   * in a music shuffle days later.
   */
  @Test
  fun resyncingPreservesUserAssignedRolesWhileUpdatingNames() = runTest {
    dao.mergeFromServer(listOf(unassigned(1, "Music"), unassigned(2, "Audiobooks")))
    dao.setRole(2, LibraryRole.AUDIOBOOKS)

    dao.mergeFromServer(
      listOf(unassigned(1, "Musik"), unassigned(2, "Hörbücher"), unassigned(3, "Podcasts")),
    )

    assertThat(dao.idsWithRole(LibraryRole.AUDIOBOOKS)).containsExactly(2)
    assertThat(dao.find(1)!!.name).isEqualTo("Musik")
    assertThat(dao.find(2)!!.name).isEqualTo("Hörbücher")
    // ...and the new one is UNASSIGNED, not guessed at from its name.
    assertThat(dao.find(3)!!.role).isEqualTo(LibraryRole.UNASSIGNED)
  }

  @Test
  fun aLibraryTheServerNoLongerReportsIsRemoved() = runTest {
    dao.mergeFromServer(listOf(unassigned(1, "Music"), unassigned(2, "Audiobooks")))

    dao.mergeFromServer(listOf(unassigned(1, "Music")))

    assertThat(dao.allIds()).containsExactly(1)
    assertThat(dao.find(2)).isNull()
  }

  @Test
  fun mergingAnEmptyServerListRemovesEverything() = runTest {
    // The boundary case an `IN (:keep)` clause gets wrong if `keep` is empty and the SQL is
    // written carelessly: SQLite's `NOT IN ()` is a syntax error, and Room binds an empty list as
    // `NOT IN ()`. Room actually expands it to `NOT IN (NULL)`-safe SQL, but this asserts the
    // behaviour rather than trusting it.
    dao.mergeFromServer(listOf(unassigned(1, "Music")))

    dao.mergeFromServer(emptyList())

    assertThat(dao.allIds()).isEmpty()
  }

  @Test
  fun observeAllEmitsInIdOrder() = runTest {
    dao.mergeFromServer(listOf(unassigned(2, "Audiobooks"), unassigned(1, "Music")))

    assertThat(dao.observeAll().first().map { it.musicFolderId }).containsExactly(1, 2)
  }

  @Test
  fun theRoleEnumSurvivesTheRoundTripThroughSqlite() = runTest {
    dao.mergeFromServer(listOf(unassigned(1, "Music")))
    dao.setRole(1, LibraryRole.MUSIC)

    // Room converts enums automatically, and "automatically" is exactly the kind of thing worth
    // one assertion: a converter that stored an ordinal would silently reorder if a member were
    // ever inserted into `LibraryRole`.
    assertThat(dao.find(1)!!.role).isEqualTo(LibraryRole.MUSIC)
  }
}
