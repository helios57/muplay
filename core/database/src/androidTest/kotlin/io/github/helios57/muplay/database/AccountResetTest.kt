package io.github.helios57.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.helios57.muplay.database.entity.LibraryEntity
import io.github.helios57.muplay.database.entity.MediaProgressEntity
import io.github.helios57.muplay.model.Album
import io.github.helios57.muplay.model.Song
import io.github.helios57.muplay.model.LibraryRole
import io.github.helios57.muplay.model.SubsonicCredentials
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What signing out forgets, and what it promises to keep.
 *
 * The defect this exists for: sign-out cleared the credentials and nothing else, so the next
 * account inherited the previous one's library mirror -- including libraries it may not read,
 * which its first sync then asked for by id -- and the previous account's sync watermark, which can
 * make that first sync decide there is nothing to do.
 */
@RunWith(AndroidJUnit4::class)
class AccountResetTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var credentialStore: CredentialStore
  private val cleared = mutableListOf<String>()

  @Before
  fun setUp() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    file = File(context.filesDir, "account-reset-test-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    credentialStore = CredentialStore(dataStore)
    credentialStore.save(SubsonicCredentials("http://localhost:4533", "admin", "testpass"))

    db.libraryDao().mergeFromServer(listOf(LibraryEntity(1, "Music", LibraryRole.MUSIC)))
    db.browseDao().replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = listOf(MirrorMapper.albumEntity(Album("al-1", 1, "Album", null, null, null, 1, 60))),
      songs = listOf(
        MirrorMapper.songEntity(
          Song("s-1", 1, "Song", "al-1", "Album", null, null, 1, null, 60, "mp3", null),
        ),
      ),
    )
    db.syncWatermarkDao().store("scan-of-the-old-server")
    db.mediaProgressDao().upsert(MediaProgressEntity("book-1", 12_345L, false, 1L, 1f, false, 0f))
  }

  @After
  fun tearDown() = runTest {
    credentialStore.clear()
    file.delete()
    db.close()
  }

  private fun reset(vararg scoped: AccountScopedData) = AccountReset(
    credentialStore = credentialStore,
    database = db,
    scoped = scoped.toSet(),
  )

  @Test
  fun signingOutForgetsTheCredentials() = runTest {
    reset().signOut()

    assertThat(credentialStore.load()).isNull()
  }

  @Test
  fun signingOutForgetsTheLibraryMirrorAndTheWatermark() = runTest {
    reset().signOut()

    assertThat(db.libraryDao().allIds()).isEmpty()
    assertThat(db.browseDao().observeAlbums(1).first()).isEmpty()
    assertThat(db.browseDao().songIdsInLibrary(1, listOf("s-1"))).isEmpty()
    assertThat(db.syncWatermarkDao().read()).isNull()
  }

  @Test
  fun signingOutKeepsAudiobookPositionsBecauseTheConfirmationPromisesIt() = runTest {
    // ServerSection's confirmation says "Your audiobook positions stay on this phone."
    reset().signOut()

    assertThat(db.mediaProgressDao().find("book-1")?.positionMs).isEqualTo(12_345)
  }

  @Test
  fun everyAccountScopedStoreIsClearedEvenIfOneOfThemFails() = runTest {
    val failing = AccountScopedData { cleared += "failing"; error("disk full") }
    val queue = AccountScopedData { cleared += "queue" }

    reset(failing, queue).signOut()

    assertThat(cleared).containsExactlyInAnyOrder("failing", "queue")
    assertThat(credentialStore.load()).isNull()
  }
}
