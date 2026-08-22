# MuPlay Plan 2 — Library Mirror + Browse Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A local Room mirror of the Navidrome library that can be browsed and
searched offline, with each library tagged Music or Audiobooks — and
**library-scoped shuffle**, the feature no existing client offers.

**Architecture:** A new `:core:database` module owns Room. `:feature:library`
owns browse UI (Views + XML). A `LibraryRepository` in `:core:database` is the
only thing that talks to both the mirror and `SubsonicClient`; UI never sees a
DTO. Sync is a full reconcile triggered by `getScanStatus`'s monotonic
`lastScan` watermark, because Subsonic has no real delta primitive and never
reports deletions.

**Tech Stack:** Room 2.8.x (Room 3 is Kotlin-codegen-only), Hilt, RxJava 3 for
observable queries, Guava `ListenableFuture` for one-shots, Glide, Material
Components 1.13, Navigation Component, Android Keystore + DataStore.

**Spec:** `docs/superpowers/specs/2026-08-21-muplay-design.md`

## Global Constraints

Everything from Plan 1 still binds. Copied verbatim, plus what Plan 1 learned.

- **Java 17 only. No Kotlin in any module**, including tests and build logic.
  `.kts` banned repo-wide. Enforced by ArchUnit, not convention.
- Licence **MIT**. No GPL code may be copied — all prior art is architecture-only.
- `@Nonnull`/`@Nullable` (JSR-305) on **every** public signature; NullAway fails
  the build. An ArchUnit rule enforces annotation coverage.
- Records for DTOs and domain models; **sealed interfaces for state and results**.
- **No new NullAway or ArchUnit suppressions.**
- `compileSdk 37`, `targetSdk 36`, `minSdk 26`.
- Subsonic client identifier **`c=MuPlay`**, protocol version **`v=1.16.1`**.
- **JSON is Jackson**, never Moshi — Moshi cannot deserialise Java records.
- **Every Jackson-deserialised record needs an explicit `@JsonCreator` on its
  canonical constructor and `@JsonProperty` on every component.** D8 strips
  record reflection metadata below `minSdk 33`, so Jackson's implicit records
  path works under Robolectric and **fails silently on-device**. This cost Plan 1
  a real bug; `ArchitectureTest.everyDtoRecordHasAnExplicitJacksonCreator`
  enforces it.
- `OpenApiFixtureValidator.assertValid`'s path argument is the vendored spec's
  literal key: **`/rest/<operationId>`**, e.g. `/rest/getAlbumList2`.
- **Exception model:** `sealed SubsonicResponseException permits
  SubsonicHttpException, SubsonicErrorException` means "we asked and got a real
  answer" → callers may degrade. A bare `IOException` means "we don't know" →
  propagate. Do not blur these.
- Book positions are **local only**. No server sync.
- **PR gate ≤ 10 minutes, no emulator.** Emulator work is nightly.
- Inject `java.time.Clock`; `System.currentTimeMillis()` banned outside `:di`.
- Branch coverage on `:core:*` must not decrease (JaCoCo ratchet from Plan 1).

---

## File Structure

| File | Responsibility |
|---|---|
| `core/database/build.gradle` | Room 2.8.x, no Kotlin |
| `core/database/.../MuPlayDatabase.java` | `@Database`, version 1, exported schema |
| `core/database/.../entity/LibraryEntity.java` | `musicFolderId` + name + role |
| `core/database/.../entity/ArtistEntity.java` | mirror row |
| `core/database/.../entity/AlbumEntity.java` | mirror row, carries `libraryId` |
| `core/database/.../entity/SongEntity.java` | mirror row, carries `libraryId` + `albumId` |
| `core/database/.../entity/MediaProgressEntity.java` | **the one progress table** (spec §3) |
| `core/database/.../dao/LibraryDao.java` | role read/write |
| `core/database/.../dao/BrowseDao.java` | scoped artist/album/song queries |
| `core/database/.../dao/MediaProgressDao.java` | position read/write — used by Plan 4 |
| `core/database/.../LibraryRepository.java` | the only seam between mirror and network |
| `core/database/.../SyncEngine.java` | `getScanStatus` watermark + full reconcile |
| `core/database/.../CredentialStore.java` | Keystore AES-GCM + DataStore |
| `core/network/.../SubsonicApi.java` | **modify** — add browse endpoints |
| `core/network/.../dto/*.java` | **new** — album/artist/song response records |
| `feature/library/.../LibraryFragment.java` | browse UI |
| `feature/library/.../SetupFragment.java` | first-run: tag each library |
| `feature/library/.../ShuffleUseCase.java` | library-scoped random |
| `testing/.../fixtures/*.json` | recorded responses, spec-validated |

---

## Task 1: `:core:database` module and the progress schema

**Files:**
- Create: `core/database/build.gradle`
- Create: `core/database/src/main/java/app/muplay/database/MuPlayDatabase.java`
- Create: `core/database/src/main/java/app/muplay/database/entity/MediaProgressEntity.java`
- Create: `core/database/src/main/java/app/muplay/database/dao/MediaProgressDao.java`
- Modify: `settings.gradle`, `gradle/libs.versions.toml`
- Test: `core/database/src/test/java/app/muplay/database/MediaProgressDaoTest.java`

**Interfaces:**
- Consumes: nothing from Plan 1 except the build conventions
- Produces:
  - `MuPlayDatabase.mediaProgressDao()`
  - `MediaProgressDao.upsert(MediaProgressEntity)`,
    `MediaProgressDao.find(String mediaId)` → `@Nullable MediaProgressEntity`
  - `MediaProgressEntity(String mediaId, long positionMs, boolean isFinished,
    long lastPlayedAt, float speed, boolean skipSilence, float gainDb)`

**Why this table is the whole point.** Spec §3: *the queue is a list of
pointers; progress is a property of the item.* Every other player keeps one
global "now playing position" that the next thing played overwrites — which is
exactly why the user cannot listen to music between two audiobook sessions
without losing their place. There is **one** progress table, keyed by the
server's stable media id, and music and audiobooks are two pointer lists over
it. Nothing about queue membership may ever be stored here.

- [ ] **Step 1: Write the failing test**

`core/database/src/test/java/app/muplay/database/MediaProgressDaoTest.java`:

```java
package app.muplay.database;

import static com.google.common.truth.Truth.assertThat;

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

    assertThat(dao.find("book-1").positionMs()).isEqualTo(900_000L);
  }

  @Test
  public void upsertReplacesTheSameMediaId() {
    dao.upsert(new MediaProgressEntity("book-1", 100L, false, 1L, 1.0f, false, 0f));
    dao.upsert(new MediaProgressEntity("book-1", 200L, false, 2L, 1.0f, false, 0f));

    assertThat(dao.find("book-1").positionMs()).isEqualTo(200L);
    assertThat(dao.findAll()).hasSize(1);
  }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core:database:test`
Expected: FAIL — the module does not exist.

- [ ] **Step 3: Register the module**

`settings.gradle` — add `':core:database'` to the existing `include` line.

`gradle/libs.versions.toml` — add under `[versions]`:

```toml
room = "2.8.4"
rxjava = "3.1.10"
rxandroid = "3.0.2"
```

and under `[libraries]`:

```toml
room-runtime   = { module = "androidx.room:room-runtime", version.ref = "room" }
room-compiler  = { module = "androidx.room:room-compiler", version.ref = "room" }
room-rxjava3   = { module = "androidx.room:room-rxjava3", version.ref = "room" }
room-testing   = { module = "androidx.room:room-testing", version.ref = "room" }
rxjava         = { module = "io.reactivex.rxjava3:rxjava", version.ref = "rxjava" }
rxandroid      = { module = "io.reactivex.rxjava3:rxandroid", version.ref = "rxandroid" }
```

**Room 2.8.x, not Room 3.** Room 3 is Kotlin-codegen-only, which the Java-only
constraint rules out independently of its age.

- [ ] **Step 4: Write the module build file**

`core/database/build.gradle`:

```groovy
plugins {
  id 'com.android.library'
  id 'com.google.dagger.hilt.android'
}

android {
  namespace = 'app.muplay.database'
  compileSdk = 37

  defaultConfig {
    minSdk = 26
    // Room's exported schemas are the input to migration tests. Without this the
    // schema is invisible and every future migration is unverifiable.
    javaCompileOptions {
      annotationProcessorOptions {
        arguments += ['room.schemaLocation': "$projectDir/schemas".toString()]
      }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions {
    unitTests {
      includeAndroidResources = true
    }
  }
}

dependencies {
  api project(':core:model')
  api project(':core:network')

  implementation libs.room.runtime
  implementation libs.room.rxjava3
  implementation libs.rxjava
  annotationProcessor libs.room.compiler

  implementation libs.hilt.android
  annotationProcessor libs.hilt.compiler

  implementation libs.guava
  implementation libs.jsr305

  testImplementation libs.junit
  testImplementation libs.truth
  testImplementation libs.robolectric
  testImplementation libs.room.testing
  testImplementation libs.androidx.test.core
}

// Room's generated implementations are not our code; NullAway must not police them.
tasks.withType(JavaCompile).configureEach {
  options.errorprone.excludedPaths = '.*/build/generated/.*'
}
```

- [ ] **Step 5: Write the entity**

`core/database/src/main/java/app/muplay/database/entity/MediaProgressEntity.java`:

```java
package app.muplay.database.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import javax.annotation.Nonnull;

/**
 * The single source of truth for "where was I in this item".
 *
 * <p>There is exactly one of these tables. Music queues and audiobook queues are two pointer lists
 * over it, so switching from a book to music touches no row here — which is the entire reason a
 * book keeps its exact position across an intervening music session.
 *
 * <p>{@code mediaId} is the server's stable id, never a rowid: a re-scan on the server must not
 * orphan a listener's progress.
 *
 * <p>Nothing about queue membership belongs in this table. If you find yourself adding a
 * {@code queuePosition} or {@code isInQueue} column, the design has been inverted.
 */
@Entity(tableName = "media_progress")
public record MediaProgressEntity(
    @PrimaryKey @Nonnull String mediaId,
    long positionMs,
    boolean isFinished,
    long lastPlayedAt,
    float speed,
    boolean skipSilence,
    float gainDb) {}
```

- [ ] **Step 6: Write the DAO**

`core/database/src/main/java/app/muplay/database/dao/MediaProgressDao.java`:

```java
package app.muplay.database.dao;

import androidx.room.Dao;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Upsert;
import app.muplay.database.entity.MediaProgressEntity;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Dao
public interface MediaProgressDao {

  @Upsert
  void upsert(@Nonnull MediaProgressEntity progress);

  @Query("SELECT * FROM media_progress WHERE mediaId = :mediaId")
  @Nullable
  MediaProgressEntity find(@Nonnull String mediaId);

  @Nonnull
  @Query("SELECT * FROM media_progress")
  List<MediaProgressEntity> findAll();

  @Nonnull
  @Query("SELECT * FROM media_progress WHERE isFinished = 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
  List<MediaProgressEntity> recentlyPlayed(int limit);
}
```

- [ ] **Step 7: Write the database class**

`core/database/src/main/java/app/muplay/database/MuPlayDatabase.java`:

```java
package app.muplay.database;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import app.muplay.database.dao.MediaProgressDao;
import app.muplay.database.entity.MediaProgressEntity;

@Database(
    entities = {MediaProgressEntity.class},
    version = 1,
    exportSchema = true)
public abstract class MuPlayDatabase extends RoomDatabase {

  public abstract MediaProgressDao mediaProgressDao();
}
```

- [ ] **Step 8: Run the tests**

Run: `./gradlew :core:database:test`
Expected: PASS, 4/4.

Then `./gradlew build` — the ArchUnit module-coverage guard derives its module
list from the filesystem, so it will notice `:core:database` automatically. If it
fails complaining the new module's classes are absent from the import, add
`implementation project(':core:database')` to `app/build.gradle` — that is the
guard working as designed, not a bug.

- [ ] **Step 9: Commit**

```bash
git add settings.gradle gradle/libs.versions.toml core/database app/build.gradle
git commit -m "feat(database): the single media_progress table"
```

---

## Task 2: Credential storage on the Android Keystore

**Files:**
- Create: `core/database/src/main/java/app/muplay/database/CredentialStore.java`
- Create: `core/database/src/main/java/app/muplay/database/KeystoreCipher.java`
- Test: `core/database/src/test/java/app/muplay/database/KeystoreCipherTest.java`
- Test: `app/src/androidTest/java/app/muplay/CredentialStoreInstrumentedTest.java`

**Interfaces:**
- Consumes: `app.muplay.model.SubsonicCredentials`
- Produces:
  - `CredentialStore.save(SubsonicCredentials)` → `ListenableFuture<Void>`
  - `CredentialStore.load()` → `ListenableFuture<Optional<SubsonicCredentials>>`
  - `CredentialStore.clear()` → `ListenableFuture<Void>`

**Why not `EncryptedSharedPreferences`.** It is deprecated wholesale. And the
password is needed in **cleartext at request time** anyway — Subsonic auth
computes `t = md5(password + salt)` with a fresh salt per request, so there is no
hashed-at-rest option available. The honest design is: AES-GCM key in the Android
Keystore, ciphertext in DataStore, decrypt on demand.

**The real test is the instrumented one.** A Robolectric test cannot exercise a
hardware-backed keystore. Unit-test the cipher's contract against a software
provider; prove the real thing on-device in the nightly lane.

- [ ] **Step 1: Write the failing unit test**

`core/database/src/test/java/app/muplay/database/KeystoreCipherTest.java`:

```java
package app.muplay.database;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.security.GeneralSecurityException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Test;

public class KeystoreCipherTest {

  private static SecretKey key() throws Exception {
    KeyGenerator gen = KeyGenerator.getInstance("AES");
    gen.init(256);
    return gen.generateKey();
  }

  @Test
  public void roundTripsUtf8() throws Exception {
    SecretKey k = key();
    // A non-ASCII password is the case that breaks a charset-sloppy implementation.
    String secret = "hunter2-Ünïcödé-🎵";

    byte[] sealed = KeystoreCipher.seal(k, secret);
    assertThat(KeystoreCipher.open(k, sealed)).isEqualTo(secret);
  }

  @Test
  public void everySealUsesAFreshIv() throws Exception {
    SecretKey k = key();

    byte[] a = KeystoreCipher.seal(k, "same");
    byte[] b = KeystoreCipher.seal(k, "same");

    // GCM with a reused IV under the same key is a catastrophic break, not a nitpick.
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  public void tamperingIsDetected() throws Exception {
    SecretKey k = key();
    byte[] sealed = KeystoreCipher.seal(k, "secret");
    sealed[sealed.length - 1] ^= 0x01;

    assertThrows(GeneralSecurityException.class, () -> KeystoreCipher.open(k, sealed));
  }

  @Test
  public void aDifferentKeyCannotOpenIt() throws Exception {
    byte[] sealed = KeystoreCipher.seal(key(), "secret");

    assertThrows(GeneralSecurityException.class, () -> KeystoreCipher.open(key(), sealed));
  }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core:database:test --tests '*KeystoreCipherTest*'`
Expected: FAIL — `KeystoreCipher` does not exist.

- [ ] **Step 3: Implement the cipher**

`core/database/src/main/java/app/muplay/database/KeystoreCipher.java`:

```java
package app.muplay.database;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.annotation.Nonnull;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-GCM sealing for credential material.
 *
 * <p>The IV is generated fresh for every seal and prefixed to the ciphertext. Reusing an IV under
 * the same GCM key destroys both confidentiality and authenticity, so this is not a detail.
 */
public final class KeystoreCipher {

  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private KeystoreCipher() {}

  @Nonnull
  public static byte[] seal(@Nonnull SecretKey key, @Nonnull String plaintext)
      throws GeneralSecurityException {
    byte[] iv = new byte[IV_BYTES];
    RANDOM.nextBytes(iv);

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
    byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

    byte[] out = new byte[iv.length + ciphertext.length];
    System.arraycopy(iv, 0, out, 0, iv.length);
    System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
    return out;
  }

  @Nonnull
  public static String open(@Nonnull SecretKey key, @Nonnull byte[] sealed)
      throws GeneralSecurityException {
    if (sealed.length <= IV_BYTES) {
      throw new GeneralSecurityException("sealed blob is too short to contain an IV");
    }
    byte[] iv = Arrays.copyOfRange(sealed, 0, IV_BYTES);
    byte[] ciphertext = Arrays.copyOfRange(sealed, IV_BYTES, sealed.length);

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
    return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
  }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:database:test --tests '*KeystoreCipherTest*'`
Expected: PASS, 4/4.

- [ ] **Step 5: Implement `CredentialStore`**

Write `CredentialStore` to hold the Keystore alias lifecycle (`AndroidKeyStore`
provider, `KeyGenParameterSpec` with `setBlockModes(GCM)` /
`setEncryptionPaddings(NONE)`, **not** user-authentication-bound — playback must
work from a locked screen), and persist the sealed blob plus the plaintext base
URL and username to DataStore. Only the password is sealed.

Return `ListenableFuture` from every method and do the work on a single-threaded
executor; Keystore operations are not main-thread safe.

- [ ] **Step 6: Write the instrumented test**

`app/src/androidTest/java/app/muplay/CredentialStoreInstrumentedTest.java` —
save credentials, read them back, and assert the password survives a round trip
through the **real** hardware-backed keystore. Assert `clear()` genuinely removes
the key, not just the DataStore entry.

- [ ] **Step 7: Wire it into the nightly workflow**

Add the new instrumented test to the existing `connectedDebugAndroidTest` step in
`.github/workflows/nightly.yml`. It needs no Navidrome — but it does need a real
device, which is why it is nightly.

- [ ] **Step 8: Commit**

```bash
git add core/database app/src/androidTest .github/workflows/nightly.yml
git commit -m "feat(database): keystore-backed credential storage"
```

---

## Task 3: Library entities and role assignment

**Files:**
- Create: `core/database/src/main/java/app/muplay/database/entity/LibraryEntity.java`
- Create: `core/database/src/main/java/app/muplay/database/dao/LibraryDao.java`
- Modify: `core/database/src/main/java/app/muplay/database/MuPlayDatabase.java` (version 1 still — pre-release, no migration needed yet)
- Test: `core/database/src/test/java/app/muplay/database/LibraryDaoTest.java`

**Interfaces:**
- Consumes: `app.muplay.model.MusicLibrary`, `app.muplay.model.LibraryRole`
- Produces:
  - `LibraryDao.upsertAll(List<LibraryEntity>)`
  - `LibraryDao.setRole(int musicFolderId, LibraryRole role)`
  - `LibraryDao.observeAll()` → `Flowable<List<LibraryEntity>>`
  - `LibraryDao.idsWithRole(LibraryRole)` → `List<Integer>`

**Why library id is load-bearing.** Spec §4: Navidrome **hardcodes
`child.Type = "music"`** for every media file and always sets
`mediaType = song`. A Navidrome server will never tell a client that something
is an audiobook, and there is no server config for it. The library id is the
only mechanism available, which makes "which library is this" a first-class app
concept rather than an implementation detail.

The user tags each library once at setup. Everything downstream — shuffle scope,
resume behaviour, which browse tree to show — keys off that tag.

- [ ] **Step 1: Write the failing test**

```java
package app.muplay.database;

import static com.google.common.truth.Truth.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import app.muplay.database.dao.LibraryDao;
import app.muplay.database.entity.LibraryEntity;
import app.muplay.model.LibraryRole;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class LibraryDaoTest {

  private MuPlayDatabase db;
  private LibraryDao dao;

  @Before
  public void setUp() {
    db =
        Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(), MuPlayDatabase.class)
            .allowMainThreadQueries()
            .build();
    dao = db.libraryDao();
  }

  @After
  public void tearDown() {
    db.close();
  }

  @Test
  public void librariesStartUnassigned() {
    dao.upsertAll(
        List.of(
            new LibraryEntity(1, "Music", LibraryRole.UNASSIGNED),
            new LibraryEntity(2, "Audiobooks", LibraryRole.UNASSIGNED)));

    assertThat(dao.idsWithRole(LibraryRole.AUDIOBOOKS)).isEmpty();
  }

  @Test
  public void taggingALibraryIsWhatMakesItAnAudiobookLibrary() {
    dao.upsertAll(
        List.of(
            new LibraryEntity(1, "Music", LibraryRole.UNASSIGNED),
            new LibraryEntity(2, "Audiobooks", LibraryRole.UNASSIGNED)));

    dao.setRole(2, LibraryRole.AUDIOBOOKS);

    assertThat(dao.idsWithRole(LibraryRole.AUDIOBOOKS)).containsExactly(2);
    assertThat(dao.idsWithRole(LibraryRole.MUSIC)).isEmpty();
  }

  /**
   * A server re-scan re-reports the same libraries. Re-syncing must not silently reset the roles
   * the user chose — that would un-tag their audiobook library behind their back.
   */
  @Test
  public void resyncingPreservesUserAssignedRoles() {
    dao.upsertAll(List.of(new LibraryEntity(2, "Audiobooks", LibraryRole.UNASSIGNED)));
    dao.setRole(2, LibraryRole.AUDIOBOOKS);

    dao.mergeFromServer(List.of(new LibraryEntity(2, "Audiobooks (renamed)", LibraryRole.UNASSIGNED)));

    assertThat(dao.idsWithRole(LibraryRole.AUDIOBOOKS)).containsExactly(2);
    assertThat(dao.find(2).name()).isEqualTo("Audiobooks (renamed)");
  }
}
```

- [ ] **Step 2: Run it, confirm it fails, then implement**

`mergeFromServer` is the interesting one: it must update names and insert new
libraries while **leaving the `role` column alone** for ids that already exist.
An `@Upsert` of a full entity would clobber the role — implement it as an
explicit `INSERT OR IGNORE` followed by an `UPDATE ... SET name = :name`.

- [ ] **Step 3: Run the tests, then commit**

```bash
git add core/database
git commit -m "feat(database): library entities with user-assigned roles"
```

---

## Task 4: Browse endpoints and their DTOs

**Files:**
- Modify: `core/network/src/main/java/app/muplay/network/SubsonicApi.java`
- Modify: `core/network/src/main/java/app/muplay/network/SubsonicClient.java`
- Create: `core/network/src/main/java/app/muplay/network/dto/AlbumList2.java`
- Create: `core/network/src/main/java/app/muplay/network/dto/AlbumWithSongs.java`
- Create: `core/network/src/main/java/app/muplay/network/dto/SearchResult3.java`
- Create: `testing/src/main/resources/fixtures/getAlbumList2_navidrome.json`
- Create: `testing/src/main/resources/fixtures/getAlbum_navidrome.json`
- Create: `testing/src/main/resources/fixtures/search3_navidrome.json`
- Test: `core/network/src/test/java/app/muplay/network/BrowseEndpointsTest.java`
- Test: modify `core/network/src/test/java/app/muplay/network/FixtureContractTest.java`

**Interfaces:**
- Produces:
  - `SubsonicClient.getAlbumList2(String type, int size, int offset, @Nullable Integer musicFolderId)` → `ListenableFuture<List<Album>>`
  - `SubsonicClient.getAlbum(String albumId)` → `ListenableFuture<AlbumWithSongs>`
  - `SubsonicClient.search3(String query, @Nullable Integer musicFolderId)` → `ListenableFuture<SearchResult3>`

**Every new record needs `@JsonCreator` + `@JsonProperty`.** Not optional — see
Global Constraints. The ArchUnit rule will fail the build if you forget, which is
the point.

**Fixtures must be recorded, not invented.** Plan 1's fixtures were hand-written
from the brief, and the final review flagged that their content was never
server-proven. Do better here: bring up the Navidrome container
(`docker compose -f ci/navidrome.compose.yml up -d`, seeded via
`ci/seed-fixtures.sh` and `ci/configure-libraries.sh`), `curl` each endpoint, and
commit the **actual** response. Then `assertValid("/rest/getAlbumList2", json)`
proves the recorded shape against the vendored spec.

- [ ] **Step 1: Record the fixtures from the live container**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait
./ci/seed-fixtures.sh && ./ci/configure-libraries.sh
AUTH='u=admin&t=...&s=...&v=1.16.1&c=MuPlay&f=json'
curl -s "http://localhost:4533/rest/getAlbumList2.view?$AUTH&type=alphabeticalByName&size=10" \
  | python3 -m json.tool > testing/src/main/resources/fixtures/getAlbumList2_navidrome.json
```

Record `getAlbum` and `search3` the same way. Redact nothing — these are test
fixtures from a throwaway container with no real data.

- [ ] **Step 2: Extend `FixtureContractTest` to validate all three**

Each new fixture gets an `assertValid("/rest/<operationId>", …)` case. A fixture
that will not validate means the recorded shape and the published spec disagree —
**investigate which is wrong rather than editing the fixture until it passes.**
Navidrome deviating from the spec is a real finding worth writing down.

- [ ] **Step 3–6: TDD the DTOs and client methods**

Write `BrowseEndpointsTest` against MockWebServer using the recorded fixtures,
confirm it fails, then implement the records and the `SubsonicApi` methods.
Reuse `SubsonicClient`'s existing private `enqueue(Call, Function<Body,T>)`
helper — do not duplicate the callback plumbing or the error mapping.

- [ ] **Step 7: Commit**

```bash
git add core/network testing/src/main/resources/fixtures
git commit -m "feat(network): browse endpoints with recorded fixtures"
```

---

## Task 5: The sync engine

**Files:**
- Create: `core/database/src/main/java/app/muplay/database/SyncEngine.java`
- Create: `core/database/src/main/java/app/muplay/database/SyncState.java`
- Modify: `core/network/.../SubsonicApi.java` — add `getScanStatus`
- Test: `core/database/src/test/java/app/muplay/database/SyncEngineTest.java`

**Interfaces:**
- Produces:
  - `SyncEngine.syncIfStale()` → `ListenableFuture<SyncState>`
  - sealed `SyncState permits SyncState.UpToDate, SyncState.Synced, SyncState.Failed`

**Why a full reconcile and not a delta.** Spec §4 is blunt about this: nobody in
this ecosystem syncs properly. `getIndexes?ifModifiedSince=` is the only delta
primitive, Navidrome compares it against **one global watermark**, so you learn
*that* something changed and never *what* — and only for artists. **Deletions are
never reported at all.** A client that trusts deltas accumulates ghost albums
forever.

So: use `getScanStatus`, which Navidrome extends with a monotonic `lastScan`.
Store the last-seen value. When it moves, page through `getAlbumList2` and
reconcile the mirror against what came back — anything locally present and
remotely absent is a deletion.

> **Trap, spec §4:** Tempo's `getScanStatus()` implementation calls
> `startScan()`, re-triggering a full server scan on every poll. Do not
> reproduce that. `getScanStatus` is a **read**.

- [ ] **Step 1: Write the failing tests**

The four that matter:

```java
@Test
public void anUnchangedWatermarkSkipsTheReconcileEntirely() { … }

@Test
public void aMovedWatermarkTriggersAFullReconcile() { … }

/** The case deltas cannot express: an album gone from the server must vanish locally. */
@Test
public void anAlbumDeletedOnTheServerIsRemovedFromTheMirror() { … }

/** A half-completed sync must not leave the mirror in a state that looks complete. */
@Test
public void aFailureMidReconcileDoesNotAdvanceTheWatermark() { … }
```

The last one is the important one. If the watermark advances before the
reconcile commits, a failed sync is never retried and the mirror stays
permanently stale. Advance the watermark **only** after the transaction commits.

- [ ] **Step 2–5: Implement, run, commit**

`SyncState` is a sealed interface per the global constraints, so callers get
compiler-checked exhaustiveness rather than a boolean and a nullable message.

```bash
git add core/database core/network
git commit -m "feat(database): scan-watermark sync with full reconcile"
```

---

## Task 6: Library-scoped shuffle — the headline feature

**Files:**
- Create: `core/database/src/main/java/app/muplay/database/ShuffleService.java`
- Test: `core/database/src/test/java/app/muplay/database/ShuffleServiceTest.java`
- Test: `app/src/androidTest/java/app/muplay/ScopedShuffleLiveTest.java`

**Interfaces:**
- Produces: `ShuffleService.shuffleLibrary(int musicFolderId, int size)` → `ListenableFuture<List<Song>>`

**This is why MuPlay exists.** Symfonium cannot restrict random playback to a
library, which is the specific reason the user cannot use it: hitting shuffle
pulls audiobook chapters into a music session.

`getRandomSongs` honours `musicFolderId`. **`size` is capped at 500** by
Navidrome — ask for more and you silently get 500, so a caller that requests
1000 and assumes it got 1000 is wrong. Clamp explicitly and document why.

> **Trap, spec §4:** `getIndexes` and `getArtists` **discard the validation
> error** — an invalid `musicFolderId` silently returns **all** libraries.
> Never use those two to enforce a scope. This is a silent-wrong-answer failure,
> the worst kind, so the test below is a real guard rather than a formality.

- [ ] **Step 1: Write the failing tests**

```java
/** The whole feature in one assertion: no audiobook may appear in a music shuffle. */
@Test
public void shufflingTheMusicLibraryNeverReturnsAnAudiobook() { … }

/** Navidrome silently truncates at 500; a caller asking for more must not be misled. */
@Test
public void sizeIsClampedToFiveHundred() { … }

/** musicFolderId must be on the wire — omitting it is how the scope silently widens. */
@Test
public void theRequestCarriesTheMusicFolderId() { … }
```

- [ ] **Step 2: Implement and verify against the real server**

The unit tests prove the request is well-formed. `ScopedShuffleLiveTest` in the
nightly lane proves the *server* honours it: with the container seeded with a
Music library and an Audiobooks library, shuffle the music library 50 times and
assert the audiobook track never appears. That is the assertion the user
actually cares about, and only a real server can make it.

- [ ] **Step 3: Commit**

```bash
git add core/database app/src/androidTest
git commit -m "feat(database): library-scoped shuffle"
```

---

## Task 7: Browse UI

**Files:**
- Create: `feature/library/build.gradle`
- Create: `feature/library/src/main/java/app/muplay/library/SetupFragment.java`
- Create: `feature/library/src/main/java/app/muplay/library/LibraryFragment.java`
- Create: `feature/library/src/main/java/app/muplay/library/AlbumAdapter.java`
- Create: `feature/library/src/main/res/layout/*.xml`
- Modify: `app/src/main/AndroidManifest.xml`, `settings.gradle`
- Test: `feature/library/src/test/java/app/muplay/library/SetupViewModelTest.java`
- Test: `app/src/androidTest/java/app/muplay/BrowseJourneyTest.java`

**Views + XML, not Compose** — Compose has no Java API. ViewBinding, Material
Components 1.13, Navigation Component, single Activity.

**First run is the setup flow**: enter server URL and credentials → `ping` →
`getMusicFolders` → **the user tags each library Music or Audiobooks**. That tag
is what makes every downstream feature work, so the flow must not be skippable
and must not guess. Do not infer a role from a library's name — "Hörbücher" is
not "Audiobooks", and a wrong guess silently poisons shuffle scope.

- [ ] **Step 1–6: TDD the ViewModel, then the fragments**

Test the ViewModels with Robolectric — they hold the logic. Keep fragments thin
enough that they need no unit tests of their own; `BrowseJourneyTest` covers
them end to end on-device in the nightly lane.

- [ ] **Step 7: Commit**

```bash
git add feature settings.gradle app/src/main
git commit -m "feat(library): setup and browse UI"
```

---

## Definition of done

1. All tasks' tests pass; `./gradlew build` green.
2. PR gate still under 10 minutes with no emulator.
3. No new ArchUnit or NullAway suppressions.
4. JaCoCo branch coverage on `:core:*` has not decreased.
5. Every new fixture is **recorded from the live container**, not hand-written,
   and validated against the vendored OpenAPI spec.
6. The nightly lane proves scoped shuffle against a real Navidrome with two
   libraries — the assertion the user actually cares about.
7. Anything discovered to be wrong in the spec is corrected **in the spec**.
