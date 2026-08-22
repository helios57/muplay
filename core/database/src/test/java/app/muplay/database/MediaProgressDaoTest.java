package app.muplay.database;

import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import app.muplay.database.dao.MediaProgressDao;
import app.muplay.database.entity.MediaProgressEntity;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MediaProgressDaoTest {

  private MuPlayDatabase db;
  private MediaProgressDao dao;

  @Before
  public void setUp() {
    db =
        Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(), MuPlayDatabase.class)
            .allowMainThreadQueries()
            .build();
    dao = db.mediaProgressDao();
  }

  @After
  public void tearDown() {
    db.close();
  }

  @Test
  public void unknownMediaHasNoProgress() {
    assertThat(dao.find("does-not-exist")).isNull();
  }

  @Test
  public void progressRoundTrips() {
    dao.upsert(new MediaProgressEntity("book-1", 123_456L, false, 1000L, 1.5f, true, -3.5f));

    MediaProgressEntity found = dao.find("book-1");
    assertThat(found).isNotNull();
    // dao.find is @Nullable at the type level, per the brief's DAO signature — the Truth
    // assertion above is the real test assertion; requireNonNull below only narrows the type so
    // NullAway (app.muplay's AnnotatedPackages scope covers this test too) allows dereferencing
    // found on the following lines. Truth's isNotNull() alone does not narrow for NullAway.
    requireNonNull(found);
    assertThat(found.positionMs()).isEqualTo(123_456L);
    assertThat(found.speed()).isEqualTo(1.5f);
    assertThat(found.skipSilence()).isTrue();
    assertThat(found.gainDb()).isEqualTo(-3.5f);
  }

  /**
   * The failure mode this schema exists to prevent: playing a different item must not disturb the
   * first item's position. This is the user-visible requirement — a book keeps its place across a
   * music session.
   */
  @Test
  public void progressForOneItemSurvivesPlayingAnother() {
    dao.upsert(new MediaProgressEntity("book-1", 900_000L, false, 1000L, 1.0f, false, 0f));
    dao.upsert(new MediaProgressEntity("song-1", 30_000L, false, 2000L, 1.0f, false, 0f));

    assertThat(requireNonNull(dao.find("book-1")).positionMs()).isEqualTo(900_000L);
  }

  @Test
  public void upsertReplacesTheSameMediaId() {
    dao.upsert(new MediaProgressEntity("book-1", 100L, false, 1L, 1.0f, false, 0f));
    dao.upsert(new MediaProgressEntity("book-1", 200L, false, 2L, 1.0f, false, 0f));

    assertThat(requireNonNull(dao.find("book-1")).positionMs()).isEqualTo(200L);
    assertThat(dao.findAll()).hasSize(1);
  }
}
