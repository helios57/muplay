# MuPlay Plan 1 — Foundation + Subsonic Client

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Java Android project that authenticates against a real Navidrome
server, negotiates its OpenSubsonic capabilities, and proves every response shape
against the published OpenSubsonic OpenAPI spec — with a CI gate that runs in
under ten minutes without an emulator.

**Architecture:** Three Gradle modules (`:core:model`, `:core:network`,
`:app`) plus a `:testing` fixture module. The network layer is Retrofit 3 + Jackson
over OkHttp 5, returning Guava `ListenableFuture`. Every committed JSON fixture is
validated against a vendored copy of the OpenSubsonic OpenAPI spec, so response
shapes are asserted against an external oracle rather than against whatever the
code happens to produce. A pinned Navidrome container provides a live contract
test in the nightly lane.

**Tech Stack:** Java 17, AGP 9.3.x, Gradle 9.7.x, Retrofit 3, OkHttp 5, Jackson,
Guava, Hilt, JUnit 4, Truth, Robolectric 4.16.1, ArchUnit 1.5.0, NullAway,
JaCoCo, `openapi-request-validator-core` 3.0.0, Docker (nightly only).

**Spec:** `docs/superpowers/specs/2026-08-21-muplay-design.md`

## Global Constraints

- **Java 17 only. No Kotlin in any module**, including tests and build logic.
- Licence **MIT**. No GPL code may be copied — all prior art is architecture-only.
- `@NonNull`/`@Nullable` on every public signature; **NullAway fails the build**.
- Records for DTOs and domain models; sealed interfaces for state and results.
- `compileSdk 37`, `targetSdk 36`, `minSdk 26`.
- Subsonic client identifier is **`c=MuPlay`**, protocol version `v=1.16.1`.
- Navidrome **≥ 0.62.0**.
- Inject `java.time.Clock`; `System.currentTimeMillis()` banned outside `:di`.
- PR gate **≤ 10 minutes, no emulator**.

---

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle` | Module registry |
| `build.gradle` | Root config: Java 17, NullAway, no Kotlin plugin |
| `gradle/libs.versions.toml` | Version catalogue — single source of dependency truth |
| `core/model/.../SubsonicCredentials.java` | Server URL + username + password, immutable |
| `core/model/.../ServerCapabilities.java` | Negotiated extensions, versions **as a list, not a boolean** |
| `core/model/.../MusicLibrary.java` | A Navidrome library (`musicFolderId` + name + role) |
| `core/model/.../LibraryRole.java` | `MUSIC` / `AUDIOBOOKS` / `UNASSIGNED` |
| `core/network/.../SubsonicAuth.java` | Salt generation and `t = md5(password + salt)` |
| `core/network/.../SubsonicApi.java` | Retrofit interface |
| `core/network/.../SubsonicClient.java` | Facade: auth params, error mapping, capability gate |
| `core/network/.../dto/*.java` | Response records |
| `core/network/.../SubsonicErrorException.java` | Typed Subsonic error codes |
| `core/network/.../CapabilityNegotiator.java` | Three-tier `ping` → `openSubsonic` → extensions |
| `testing/.../OpenApiFixtureValidator.java` | Validates a fixture against the vendored spec |
| `testing/src/main/resources/openapi/opensubsonic-1.16.1.json` | Vendored spec |
| `testing/src/main/resources/fixtures/*.json` | Recorded Navidrome responses |
| `app/.../ArchitectureTest.java` | ArchUnit rules incl. the no-Kotlin check |
| `ci/navidrome.compose.yml` | Pinned Navidrome for nightly |
| `ci/fixtures/` | Committed audio fixtures (~183 KiB) |
| `.github/workflows/pr.yml` | The ≤10-minute gate |
| `.github/workflows/nightly.yml` | Emulator + container lane |

---

## Task 1: Project skeleton with Java-only enforcement

**Files:**
- Create: `settings.gradle`, `build.gradle`, `gradle/libs.versions.toml`
- Create: `core/model/build.gradle`, `core/network/build.gradle`, `app/build.gradle`, `testing/build.gradle`
- Create: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/app/muplay/ArchitectureTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: module structure `:core:model`, `:core:network`, `:app`, `:testing`;
  version catalogue accessor `libs.*`

- [ ] **Step 1: Write the failing architecture test**

`app/src/test/java/app/muplay/ArchitectureTest.java`:

```java
package app.muplay;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;
import static com.google.common.truth.Truth.assertThat;

public class ArchitectureTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("app.muplay");

  /** The project is Java-only. A stray Kotlin plugin must fail the build. */
  @Test
  public void noModuleAppliesTheKotlinPlugin() throws Exception {
    Path root = Path.of("..");
    List<Path> buildFiles;
    try (Stream<Path> walk = Files.walk(root, 4)) {
      buildFiles =
          walk.filter(p -> p.getFileName().toString().equals("build.gradle"))
              .filter(p -> !p.toString().contains("/build/"))
              .toList();
    }
    assertThat(buildFiles).isNotEmpty();
    for (Path p : buildFiles) {
      String text = Files.readString(p);
      assertThat(text).doesNotContain("kotlin");
    }
  }

  @Test
  public void noKotlinSourceFilesExist() throws Exception {
    Path root = Path.of("..");
    try (Stream<Path> walk = Files.walk(root)) {
      List<Path> ktFiles =
          walk.filter(p -> p.toString().endsWith(".kt"))
              .filter(p -> !p.toString().contains("/build/"))
              .toList();
      assertThat(ktFiles).isEmpty();
    }
  }

  @Test
  public void modelModuleDoesNotDependOnAndroid() {
    noClasses()
        .that()
        .resideInAPackage("app.muplay.model..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("android..", "androidx..")
        .check(CLASSES);
  }

  @Test
  public void nobodyCallsSystemCurrentTimeMillisOutsideDi() {
    noClasses()
        .that()
        .resideOutsideOfPackage("app.muplay.di..")
        .should()
        .callMethod(System.class, "currentTimeMillis")
        .because("inject java.time.Clock so tests are deterministic")
        .check(CLASSES);
  }

  @Test
  public void nobodyCallsThreadSleep() {
    noClasses()
        .should()
        .callMethod(Thread.class, "sleep", long.class)
        .because("sleeps make tests flaky; await a real signal instead")
        .check(CLASSES);
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*ArchitectureTest*'`
Expected: FAIL — no Gradle project exists yet.

- [ ] **Step 3: Create the version catalogue**

`gradle/libs.versions.toml`:

```toml
[versions]
agp = "9.3.1"
media3 = "1.11.0"
room = "2.8.4"
hilt = "2.60.1"
okhttp = "5.5.0"
retrofit = "3.0.0"
jackson = "2.20.0"
guava = "33.4.8-android"
archunit = "1.5.0"
robolectric = "4.16.1"
truth = "1.4.4"
nullaway = "0.12.7"
errorprone = "2.38.0"
openapiValidator = "3.0.0"

[libraries]
media3-exoplayer   = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
media3-session     = { module = "androidx.media3:media3-session", version.ref = "media3" }
media3-testutils   = { module = "androidx.media3:media3-test-utils", version.ref = "media3" }
media3-testutils-robolectric = { module = "androidx.media3:media3-test-utils-robolectric", version.ref = "media3" }
okhttp             = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
mockwebserver3     = { module = "com.squareup.okhttp3:mockwebserver3", version.ref = "okhttp" }
retrofit           = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-jackson   = { module = "com.squareup.retrofit2:converter-jackson", version.ref = "retrofit" }
jackson-databind   = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
guava              = { module = "com.google.guava:guava", version.ref = "guava" }
hilt-android       = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler      = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
archunit           = { module = "com.tngtech.archunit:archunit", version.ref = "archunit" }
robolectric        = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
truth              = { module = "com.google.truth:truth", version.ref = "truth" }
junit              = { module = "junit:junit", version = "4.13.2" }
nullaway           = { module = "com.uber.nullaway:nullaway", version.ref = "nullaway" }
errorprone-core    = { module = "com.google.errorprone:error_prone_core", version.ref = "errorprone" }
openapi-validator  = { module = "com.atlassian.oai:openapi-request-validator-core", version.ref = "openapiValidator" }
jsr305             = { module = "com.google.code.findbugs:jsr305", version = "3.0.2" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library     = { id = "com.android.library", version.ref = "agp" }
hilt                = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
errorprone          = { id = "net.ltgt.errorprone", version = "4.1.0" }
```

>  **Why Jackson and not Moshi.** Moshi cannot deserialise Java records without
> hand-written adapters, and its codegen path is Kotlin-only — both disqualifying
> here. Jackson has supported records natively since 2.12 and is pure Java.

- [ ] **Step 4: Create the root build file**

`build.gradle`:

```groovy
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.hilt) apply false
  alias(libs.plugins.errorprone) apply false
}

subprojects {
  apply plugin: 'net.ltgt.errorprone'

  dependencies {
    errorprone libs.errorprone.core
    errorprone libs.nullaway
    compileOnly libs.jsr305
  }

  tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += [
      '-Xlint:all',
      '-Werror',
    ]
    options.errorprone {
      check('NullAway', net.ltgt.gradle.errorprone.CheckSeverity.ERROR)
      option('NullAway:AnnotatedPackages', 'app.muplay')
    }
  }
}
```

- [ ] **Step 5: Create settings and module build files**

`settings.gradle`:

```groovy
pluginManagement {
  repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories { google(); mavenCentral() }
}
rootProject.name = 'MuPlay'
include ':app', ':core:model', ':core:network', ':testing'
```

`core/model/build.gradle` — a plain Java library, no Android:

```groovy
plugins { id 'java-library' }
java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
  api libs.jsr305
  testImplementation libs.junit
  testImplementation libs.truth
}
```

`core/network/build.gradle`:

```groovy
plugins { id 'com.android.library' }
android {
  namespace 'app.muplay.network'
  compileSdk 37
  defaultConfig { minSdk 26 }
  compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
  }
  testOptions.unitTests.includeAndroidResources = true
}
dependencies {
  api project(':core:model')
  api libs.guava
  implementation libs.okhttp
  implementation libs.retrofit
  implementation libs.retrofit.jackson
  implementation libs.jackson.databind
  testImplementation libs.junit
  testImplementation libs.truth
  testImplementation libs.robolectric
  testImplementation libs.mockwebserver3
  testImplementation project(':testing')
}
```

`app/build.gradle`:

```groovy
plugins {
  id 'com.android.application'
  id 'com.google.dagger.hilt.android'
}
android {
  namespace 'app.muplay'
  compileSdk 37
  defaultConfig {
    applicationId 'app.muplay'
    minSdk 26
    targetSdk 36
    versionCode 1
    versionName '0.1.0'
    testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner'
  }
  compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
  }
}
dependencies {
  implementation project(':core:network')
  implementation libs.hilt.android
  annotationProcessor libs.hilt.compiler
  testImplementation libs.junit
  testImplementation libs.truth
  testImplementation libs.archunit
}
```

`testing/build.gradle`:

```groovy
plugins { id 'java-library' }
java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
  api libs.openapi.validator
  api libs.truth
  api libs.junit
}
```

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-permission android:name="android.permission.INTERNET" />
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
  <application
      android:name=".MuPlayApplication"
      android:label="MuPlay"
      android:allowBackup="false" />
</manifest>
```

`app/src/main/java/app/muplay/MuPlayApplication.java`:

```java
package app.muplay;

import android.app.Application;
import dagger.hilt.android.HiltAndroidApp;

@HiltAndroidApp
public class MuPlayApplication extends Application {}
```

- [ ] **Step 6: Run the architecture test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*ArchitectureTest*'`
Expected: PASS — all five rules green.

- [ ] **Step 7: Commit**

```bash
git init
git add .
git commit -m "build: Java-only Android skeleton with ArchUnit enforcement"
```

---

## Task 2: Subsonic authentication

Navidrome supports only `p=` (plaintext or `enc:hex`) and `t`+`s`. There is no
API-key support in 0.63.2 despite third-party claims. We use token auth and keep
the interface open for an API key later.

**Files:**
- Create: `core/network/src/main/java/app/muplay/network/SubsonicAuth.java`
- Create: `core/model/src/main/java/app/muplay/model/SubsonicCredentials.java`
- Test: `core/network/src/test/java/app/muplay/network/SubsonicAuthTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `SubsonicCredentials.create(String baseUrl, String username, String password)`
  - `SubsonicAuth.authParams(SubsonicCredentials, Supplier<String> saltSupplier)`
    → `Map<String, String>` containing `u`, `t`, `s`, `v`, `c`, `f`
  - `SubsonicAuth.token(String password, String salt)` → lowercase hex MD5

- [ ] **Step 1: Write the failing test**

`core/network/src/test/java/app/muplay/network/SubsonicAuthTest.java`:

```java
package app.muplay.network;

import static com.google.common.truth.Truth.assertThat;

import app.muplay.model.SubsonicCredentials;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class SubsonicAuthTest {

  /** Vector from the Subsonic API documentation: md5("sesame" + "c19b2d") . */
  @Test
  public void token_matchesKnownVector() {
    assertThat(SubsonicAuth.token("sesame", "c19b2d"))
        .isEqualTo("26719a1196d2a940705a59634eb18eab");
  }

  @Test
  public void token_isLowercaseHexOf32Chars() {
    String t = SubsonicAuth.token("hunter2", "abcdef");
    assertThat(t).matches("[0-9a-f]{32}");
  }

  @Test
  public void authParams_identifiesAsMuPlay() {
    Map<String, String> p = paramsWithSalt("abcdef");
    // Navidrome strips OpenSubsonic fields for clients named DSub or SubMusic.
    assertThat(p).containsEntry("c", "MuPlay");
    assertThat(p).containsEntry("v", "1.16.1");
    assertThat(p).containsEntry("f", "json");
  }

  @Test
  public void authParams_neverContainThePassword() {
    Map<String, String> p = paramsWithSalt("abcdef");
    assertThat(p.values()).doesNotContain("sesame");
    assertThat(p.keySet()).doesNotContain("p");
  }

  @Test
  public void authParams_useAFreshSaltEachCall() {
    java.util.List<String> salts = new java.util.ArrayList<>();
    SubsonicAuth auth = new SubsonicAuth();
    for (int i = 0; i < 100; i++) {
      salts.add(auth.authParams(creds(), SubsonicAuth.randomSaltSupplier()).get("s"));
    }
    assertThat(Set.copyOf(salts)).hasSize(100);
  }

  @Test
  public void randomSalt_isAtLeastSixCharacters() {
    // The spec requires a salt of at least six characters.
    for (int i = 0; i < 50; i++) {
      assertThat(SubsonicAuth.randomSaltSupplier().get().length()).isAtLeast(6);
    }
  }

  private static SubsonicCredentials creds() {
    return SubsonicCredentials.create("https://music.example.com", "alice", "sesame");
  }

  private static Map<String, String> paramsWithSalt(String salt) {
    return new SubsonicAuth().authParams(creds(), () -> salt);
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:network:testDebugUnitTest --tests '*SubsonicAuthTest*'`
Expected: FAIL — `SubsonicAuth` and `SubsonicCredentials` do not exist.

- [ ] **Step 3: Write the credentials record**

`core/model/src/main/java/app/muplay/model/SubsonicCredentials.java`:

```java
package app.muplay.model;

import javax.annotation.Nonnull;

/** Immutable Subsonic server credentials. */
public record SubsonicCredentials(
    @Nonnull String baseUrl, @Nonnull String username, @Nonnull String password) {

  public SubsonicCredentials {
    if (baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be blank");
    }
    if (username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    // Trailing slashes break path concatenation against /rest/*.
    baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  @Nonnull
  public static SubsonicCredentials create(
      @Nonnull String baseUrl, @Nonnull String username, @Nonnull String password) {
    return new SubsonicCredentials(baseUrl, username, password);
  }

  /** Never log credentials. */
  @Override
  public String toString() {
    return "SubsonicCredentials{" + username + "@" + baseUrl + "}";
  }
}
```

- [ ] **Step 4: Write the auth implementation**

`core/network/src/main/java/app/muplay/network/SubsonicAuth.java`:

```java
package app.muplay.network;

import app.muplay.model.SubsonicCredentials;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Builds Subsonic authentication query parameters.
 *
 * <p>Navidrome 0.63.2 supports only {@code p=} and {@code t}+{@code s}; the
 * OpenSubsonic {@code apiKeyAuthentication} extension is not implemented. We use
 * token auth and never put the password on the wire.
 */
public final class SubsonicAuth {

  /** Identifies this client. Must not be "DSub" or "SubMusic" — Navidrome
   * strips the entire OpenSubsonic field block for those. */
  public static final String CLIENT_NAME = "MuPlay";

  public static final String PROTOCOL_VERSION = "1.16.1";

  private static final String SALT_ALPHABET =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private static final int SALT_LENGTH = 12;

  private static final SecureRandom RANDOM = new SecureRandom();

  @Nonnull
  public static Supplier<String> randomSaltSupplier() {
    return () -> {
      StringBuilder sb = new StringBuilder(SALT_LENGTH);
      for (int i = 0; i < SALT_LENGTH; i++) {
        sb.append(SALT_ALPHABET.charAt(RANDOM.nextInt(SALT_ALPHABET.length())));
      }
      return sb.toString();
    };
  }

  /** {@code t = md5(password + salt)}, lowercase hex. */
  @Nonnull
  public static String token(@Nonnull String password, @Nonnull String salt) {
    try {
      MessageDigest md5 = MessageDigest.getInstance("MD5");
      byte[] digest = md5.digest((password + salt).getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(32);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 is required by the Java platform", e);
    }
  }

  @Nonnull
  public Map<String, String> authParams(
      @Nonnull SubsonicCredentials credentials, @Nonnull Supplier<String> saltSupplier) {
    String salt = saltSupplier.get();
    Map<String, String> params = new LinkedHashMap<>();
    params.put("u", credentials.username());
    params.put("t", token(credentials.password(), salt));
    params.put("s", salt);
    params.put("v", PROTOCOL_VERSION);
    params.put("c", CLIENT_NAME);
    params.put("f", "json");
    return Map.copyOf(params);
  }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:network:testDebugUnitTest --tests '*SubsonicAuthTest*'`
Expected: PASS — all six tests.

- [ ] **Step 6: Commit**

```bash
git add core/model core/network
git commit -m "feat(network): Subsonic token authentication"
```

---

## Task 3: Vendor the OpenSubsonic spec and build the fixture validator

The OpenSubsonic project publishes a real OpenAPI 3.0.0 spec (87 paths, 195
schemas). Validating fixtures against it is what stops an agent from inventing a
response shape — the oracle is external to this codebase.

**Files:**
- Create: `testing/src/main/resources/openapi/opensubsonic-1.16.1.json` (downloaded)
- Create: `testing/src/main/java/app/muplay/testing/OpenApiFixtureValidator.java`
- Test: `testing/src/test/java/app/muplay/testing/OpenApiFixtureValidatorTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `OpenApiFixtureValidator.assertValid(String endpointPath, String jsonBody)`

- [ ] **Step 1: Vendor the spec**

The spec is a Netlify build artifact, not a versioned release, so it must be
vendored rather than fetched in CI.

```bash
mkdir -p testing/src/main/resources/openapi
curl -fsSL https://opensubsonic.netlify.app/docs/openapi/openapi.json \
  -o testing/src/main/resources/openapi/opensubsonic-1.16.1.json
python3 -c "import json;d=json.load(open('testing/src/main/resources/openapi/opensubsonic-1.16.1.json'));print(d['openapi'], d['info']['version'], len(d['paths']))"
```

Expected: `3.0.0 1.16.1 87`

- [ ] **Step 2: Write the failing validator test**

`testing/src/test/java/app/muplay/testing/OpenApiFixtureValidatorTest.java`:

```java
package app.muplay.testing;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class OpenApiFixtureValidatorTest {

  private static final String VALID_PING =
      """
      {"subsonic-response":{"status":"ok","version":"1.16.1",
       "type":"navidrome","serverVersion":"0.63.2","openSubsonic":true}}
      """;

  /** Missing the required "status" field. */
  private static final String INVALID_PING =
      """
      {"subsonic-response":{"version":"1.16.1",
       "type":"navidrome","serverVersion":"0.63.2","openSubsonic":true}}
      """;

  @Test
  public void acceptsAValidResponse() {
    new OpenApiFixtureValidator().assertValid("/ping", VALID_PING);
  }

  @Test
  public void rejectsAResponseMissingARequiredField() {
    OpenApiFixtureValidator validator = new OpenApiFixtureValidator();
    assertThrows(
        AssertionError.class, () -> validator.assertValid("/ping", INVALID_PING));
  }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :testing:test --tests '*OpenApiFixtureValidatorTest*'`
Expected: FAIL — `OpenApiFixtureValidator` does not exist.

- [ ] **Step 4: Write the validator**

`testing/src/main/java/app/muplay/testing/OpenApiFixtureValidator.java`:

```java
package app.muplay.testing;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.Response;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import javax.annotation.Nonnull;

/**
 * Validates a recorded Subsonic response against the vendored OpenSubsonic
 * OpenAPI spec.
 *
 * <p>This is an oracle external to this codebase: it asserts what the protocol
 * says a response looks like, not what our parser happens to accept.
 *
 * <p>Note the spec requires {@code type}, {@code serverVersion} and {@code
 * openSubsonic} on every response — fields a legacy Subsonic server would not
 * send. Validating against it therefore asserts OpenSubsonic compliance, which
 * is deliberate for a Navidrome client.
 */
public final class OpenApiFixtureValidator {

  private static final String SPEC = "/openapi/opensubsonic-1.16.1.json";

  private final OpenApiInteractionValidator validator;

  public OpenApiFixtureValidator() {
    this.validator =
        OpenApiInteractionValidator.createForInlineApiSpecification(readSpec()).build();
  }

  private static String readSpec() {
    try (var in = OpenApiFixtureValidator.class.getResourceAsStream(SPEC)) {
      if (in == null) {
        throw new IllegalStateException("Vendored spec not found on classpath: " + SPEC);
      }
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Could not read vendored OpenAPI spec", e);
    }
  }

  /**
   * @param endpointPath e.g. {@code "/ping"} — without the {@code /rest} prefix
   *     or the {@code .view} suffix.
   * @param jsonBody the full response body
   */
  public void assertValid(@Nonnull String endpointPath, @Nonnull String jsonBody) {
    Response response =
        SimpleResponse.Builder.ok()
            .withContentType("application/json")
            .withBody(jsonBody)
            .build();
    ValidationReport report =
        validator.validateResponse(endpointPath, Request.Method.GET, response);
    if (report.hasErrors()) {
      throw new AssertionError(
          "Response does not match the OpenSubsonic spec for "
              + endpointPath
              + ":\n"
              + report);
    }
  }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :testing:test --tests '*OpenApiFixtureValidatorTest*'`
Expected: PASS — both tests.

- [ ] **Step 6: Commit**

```bash
git add testing
git commit -m "test: vendor OpenSubsonic spec and add fixture validator"
```

---

## Task 4: Response DTOs and the Retrofit client

**Files:**
- Create: `core/network/src/main/java/app/muplay/network/dto/SubsonicResponse.java`
- Create: `core/network/src/main/java/app/muplay/network/dto/MusicFolderList.java`
- Create: `core/network/src/main/java/app/muplay/network/SubsonicApi.java`
- Create: `core/network/src/main/java/app/muplay/network/SubsonicErrorException.java`
- Create: `core/network/src/main/java/app/muplay/network/SubsonicClient.java`
- Create: `testing/src/main/resources/fixtures/ping_navidrome.json`
- Create: `testing/src/main/resources/fixtures/getMusicFolders_navidrome.json`
- Test: `core/network/src/test/java/app/muplay/network/SubsonicClientTest.java`
- Test: `core/network/src/test/java/app/muplay/network/FixtureContractTest.java`

**Interfaces:**
- Consumes: `SubsonicAuth.authParams`, `OpenApiFixtureValidator.assertValid`
- Produces:
  - `SubsonicClient.ping()` → `ListenableFuture<ServerInfo>`
  - `SubsonicClient.getMusicFolders()` → `ListenableFuture<List<MusicLibrary>>`
  - `SubsonicErrorException.code()` → int (Subsonic error code)

- [ ] **Step 1: Commit the fixtures**

`testing/src/main/resources/fixtures/ping_navidrome.json`:

```json
{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.63.2","openSubsonic":true}}
```

`testing/src/main/resources/fixtures/getMusicFolders_navidrome.json`:

```json
{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.63.2","openSubsonic":true,"musicFolders":{"musicFolder":[{"id":1,"name":"Music"},{"id":2,"name":"Audiobooks"}]}}}
```

- [ ] **Step 2: Write the failing tests**

`core/network/src/test/java/app/muplay/network/FixtureContractTest.java`:

```java
package app.muplay.network;

import app.muplay.testing.OpenApiFixtureValidator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Every committed fixture must match the published OpenSubsonic spec. */
public class FixtureContractTest {

  private final OpenApiFixtureValidator validator = new OpenApiFixtureValidator();

  @Test
  public void pingFixtureMatchesSpec() throws IOException {
    validator.assertValid("/ping", fixture("ping_navidrome.json"));
  }

  @Test
  public void getMusicFoldersFixtureMatchesSpec() throws IOException {
    validator.assertValid("/getMusicFolders", fixture("getMusicFolders_navidrome.json"));
  }

  static String fixture(String name) throws IOException {
    try (var in = FixtureContractTest.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) {
        throw new IOException("Missing fixture: " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
```

`core/network/src/test/java/app/muplay/network/SubsonicClientTest.java`:

```java
package app.muplay.network;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import app.muplay.model.LibraryRole;
import app.muplay.model.MusicLibrary;
import app.muplay.model.SubsonicCredentials;
import java.util.List;
import java.util.concurrent.ExecutionException;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.HttpUrl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SubsonicClientTest {

  private MockWebServer server;
  private SubsonicClient client;

  @Before
  public void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    HttpUrl base = server.url("/");
    client =
        SubsonicClient.create(
            SubsonicCredentials.create(
                base.toString().substring(0, base.toString().length() - 1),
                "alice",
                "sesame"));
  }

  @After
  public void tearDown() throws Exception {
    server.close();
  }

  @Test
  public void ping_sendsAuthParamsAndClientName() throws Exception {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(FixtureContractTest.fixture("ping_navidrome.json"))
            .build());

    client.ping().get();

    RecordedRequest request = server.takeRequest();
    HttpUrl url = request.getUrl();
    assertThat(url.encodedPath()).endsWith("/rest/ping");
    assertThat(url.queryParameter("c")).isEqualTo("MuPlay");
    assertThat(url.queryParameter("v")).isEqualTo("1.16.1");
    assertThat(url.queryParameter("f")).isEqualTo("json");
    assertThat(url.queryParameter("u")).isEqualTo("alice");
    assertThat(url.queryParameter("t")).matches("[0-9a-f]{32}");
    assertThat(url.queryParameter("s")).isNotEmpty();
    // The password must never appear on the wire.
    assertThat(url.toString()).doesNotContain("sesame");
    assertThat(url.queryParameter("p")).isNull();
  }

  @Test
  public void getMusicFolders_mapsToLibrariesUnassignedByDefault() throws Exception {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(FixtureContractTest.fixture("getMusicFolders_navidrome.json"))
            .build());

    List<MusicLibrary> libraries = client.getMusicFolders().get();

    assertThat(libraries).hasSize(2);
    assertThat(libraries.get(0).id()).isEqualTo(1);
    assertThat(libraries.get(0).name()).isEqualTo("Music");
    // Role is a user decision — Navidrome never says what a library contains.
    assertThat(libraries.get(0).role()).isEqualTo(LibraryRole.UNASSIGNED);
    assertThat(libraries.get(1).name()).isEqualTo("Audiobooks");
  }

  @Test
  public void subsonicErrorBecomesATypedException() {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                "{\"subsonic-response\":{\"status\":\"failed\",\"version\":\"1.16.1\","
                    + "\"type\":\"navidrome\",\"serverVersion\":\"0.63.2\","
                    + "\"openSubsonic\":true,"
                    + "\"error\":{\"code\":40,\"message\":\"Wrong username or password\"}}}")
            .build());

    ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.ping().get());
    SubsonicErrorException cause = (SubsonicErrorException) thrown.getCause();
    // 40 == wrong username or password. HTTP is still 200; Subsonic signals
    // errors in the body, which is a classic source of client bugs.
    assertThat(cause.code()).isEqualTo(40);
    assertThat(cause.getMessage()).contains("Wrong username or password");
  }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew :core:network:testDebugUnitTest`
Expected: FAIL — `SubsonicClient`, `MusicLibrary`, `LibraryRole` do not exist.

- [ ] **Step 4: Write the model types**

`core/model/src/main/java/app/muplay/model/LibraryRole.java`:

```java
package app.muplay.model;

/**
 * What a Navidrome library contains.
 *
 * <p>Navidrome hardcodes {@code child.Type = "music"} for every media file and
 * never signals that something is an audiobook, so this is a user decision made
 * once at setup — and it is the only mechanism available.
 */
public enum LibraryRole {
  MUSIC,
  AUDIOBOOKS,
  UNASSIGNED
}
```

`core/model/src/main/java/app/muplay/model/MusicLibrary.java`:

```java
package app.muplay.model;

import javax.annotation.Nonnull;

/** A Navidrome library, addressed by {@code musicFolderId} in the Subsonic API. */
public record MusicLibrary(int id, @Nonnull String name, @Nonnull LibraryRole role) {

  @Nonnull
  public MusicLibrary withRole(@Nonnull LibraryRole newRole) {
    return new MusicLibrary(id, name, newRole);
  }
}
```

- [ ] **Step 5: Write the DTOs, API interface and client**

`core/network/src/main/java/app/muplay/network/SubsonicErrorException.java`:

```java
package app.muplay.network;

import java.io.IOException;

/**
 * A Subsonic-level error. Note these arrive with HTTP 200 — the status lives in
 * the response body, not the status line.
 */
public final class SubsonicErrorException extends IOException {

  /** Wrong username or password. */
  public static final int CODE_WRONG_CREDENTIALS = 40;

  /** The requested data was not found. */
  public static final int CODE_NOT_FOUND = 70;

  private final int code;

  public SubsonicErrorException(int code, String message) {
    super("Subsonic error " + code + ": " + message);
    this.code = code;
  }

  public int code() {
    return code;
  }
}
```

`core/network/src/main/java/app/muplay/network/dto/SubsonicResponse.java`:

```java
package app.muplay.network.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

/** The envelope every Subsonic response is wrapped in. */
public record SubsonicResponse(@JsonProperty("subsonic-response") @Nullable Body body) {

  public record Body(
      @Nullable String status,
      @Nullable String version,
      @Nullable String type,
      @Nullable String serverVersion,
      boolean openSubsonic,
      @Nullable Error error,
      @Nullable MusicFolderList musicFolders) {}

  public record Error(int code, @Nullable String message) {}
}
```

`core/network/src/main/java/app/muplay/network/dto/MusicFolderList.java`:

```java
package app.muplay.network.dto;

import java.util.List;
import javax.annotation.Nullable;

public record MusicFolderList(@Nullable List<MusicFolder> musicFolder) {

  public record MusicFolder(int id, @Nullable String name) {}
}
```

`core/network/src/main/java/app/muplay/network/SubsonicApi.java`:

```java
package app.muplay.network;

import app.muplay.network.dto.SubsonicResponse;
import java.util.Map;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.QueryMap;

interface SubsonicApi {

  @GET("rest/ping")
  Call<SubsonicResponse> ping(@QueryMap Map<String, String> auth);

  @GET("rest/getMusicFolders")
  Call<SubsonicResponse> getMusicFolders(@QueryMap Map<String, String> auth);
}
```

`core/network/src/main/java/app/muplay/network/SubsonicClient.java`:

```java
package app.muplay.network;

import app.muplay.model.LibraryRole;
import app.muplay.model.MusicLibrary;
import app.muplay.model.SubsonicCredentials;
import app.muplay.network.dto.MusicFolderList;
import app.muplay.network.dto.SubsonicResponse;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.annotation.Nonnull;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

/** Facade over the Subsonic REST API. */
public final class SubsonicClient {

  private final SubsonicApi api;
  private final SubsonicAuth auth = new SubsonicAuth();
  private final SubsonicCredentials credentials;

  private SubsonicClient(SubsonicApi api, SubsonicCredentials credentials) {
    this.api = api;
    this.credentials = credentials;
  }

  @Nonnull
  public static SubsonicClient create(@Nonnull SubsonicCredentials credentials) {
    OkHttpClient http = new OkHttpClient.Builder().build();
    Retrofit retrofit =
        new Retrofit.Builder()
            .baseUrl(credentials.baseUrl() + "/")
            .client(http)
            .addConverterFactory(JacksonConverterFactory.create(mapper()))
            .build();
    return new SubsonicClient(retrofit.create(SubsonicApi.class), credentials);
  }

  private static ObjectMapper mapper() {
    // Servers add fields over time; unknown ones must never break a client.
    return new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  @Nonnull
  public ListenableFuture<ServerInfo> ping() {
    return enqueue(
        api.ping(auth.authParams(credentials, SubsonicAuth.randomSaltSupplier())),
        body ->
            new ServerInfo(
                Objects.requireNonNullElse(body.type(), ""),
                Objects.requireNonNullElse(body.serverVersion(), ""),
                body.openSubsonic()));
  }

  @Nonnull
  public ListenableFuture<List<MusicLibrary>> getMusicFolders() {
    return enqueue(
        api.getMusicFolders(auth.authParams(credentials, SubsonicAuth.randomSaltSupplier())),
        body -> {
          List<MusicLibrary> out = new ArrayList<>();
          MusicFolderList folders = body.musicFolders();
          if (folders != null && folders.musicFolder() != null) {
            for (MusicFolderList.MusicFolder f : folders.musicFolder()) {
              out.add(
                  new MusicLibrary(
                      f.id(),
                      Objects.requireNonNullElse(f.name(), "Library " + f.id()),
                      LibraryRole.UNASSIGNED));
            }
          }
          return List.copyOf(out);
        });
  }

  private <T> ListenableFuture<T> enqueue(
      Call<SubsonicResponse> call, Function<SubsonicResponse.Body, T> mapper) {
    SettableFuture<T> future = SettableFuture.create();
    call.enqueue(
        new Callback<>() {
          @Override
          public void onResponse(
              Call<SubsonicResponse> c, retrofit2.Response<SubsonicResponse> response) {
            SubsonicResponse envelope = response.body();
            if (!response.isSuccessful() || envelope == null || envelope.body() == null) {
              future.setException(new IOException("HTTP " + response.code()));
              return;
            }
            SubsonicResponse.Body body = envelope.body();
            SubsonicResponse.Error error = body.error();
            if (error != null) {
              future.setException(
                  new SubsonicErrorException(
                      error.code(), Objects.requireNonNullElse(error.message(), "")));
              return;
            }
            try {
              future.set(mapper.apply(body));
            } catch (RuntimeException e) {
              future.setException(e);
            }
          }

          @Override
          public void onFailure(Call<SubsonicResponse> c, Throwable t) {
            future.setException(t);
          }
        });
    return future;
  }

  /** Server identity from {@code ping}. */
  public record ServerInfo(
      @Nonnull String type, @Nonnull String serverVersion, boolean openSubsonic) {}
}
```

- [ ] **Step 6: Run all network tests to verify they pass**

Run: `./gradlew :core:network:testDebugUnitTest`
Expected: PASS — five tests across `SubsonicClientTest` and `FixtureContractTest`.

- [ ] **Step 7: Commit**

```bash
git add core testing
git commit -m "feat(network): Subsonic client with spec-validated fixtures"
```

---

## Task 5: Capability negotiation

Three-tier, after Feishin: `ping` → is `openSubsonic` present → `getOpenSubsonicExtensions`.
Store the **versions array, not a boolean** — `songLyrics` v1 and v2 differ, and
this decision cannot be reversed later without a migration.

**Files:**
- Create: `core/model/src/main/java/app/muplay/model/ServerCapabilities.java`
- Create: `core/network/src/main/java/app/muplay/network/CapabilityNegotiator.java`
- Modify: `core/network/src/main/java/app/muplay/network/SubsonicApi.java`
- Modify: `core/network/src/main/java/app/muplay/network/dto/SubsonicResponse.java`
- Create: `testing/src/main/resources/fixtures/getOpenSubsonicExtensions_navidrome.json`
- Test: `core/network/src/test/java/app/muplay/network/CapabilityNegotiatorTest.java`

**Interfaces:**
- Consumes: `SubsonicClient`
- Produces:
  - `ServerCapabilities.supports(String extension)` → boolean
  - `ServerCapabilities.supports(String extension, int version)` → boolean
  - `CapabilityNegotiator.negotiate()` → `ListenableFuture<ServerCapabilities>`

- [ ] **Step 1: Commit the fixture**

Navidrome 0.63.2 advertises exactly these. `apiKeyAuthentication` is **not**
present despite third-party claims.

`testing/src/main/resources/fixtures/getOpenSubsonicExtensions_navidrome.json`:

```json
{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.63.2","openSubsonic":true,"openSubsonicExtensions":[{"name":"transcodeOffset","versions":[1]},{"name":"formPost","versions":[1]},{"name":"songLyrics","versions":[1,2]},{"name":"indexBasedQueue","versions":[1]},{"name":"transcoding","versions":[1]},{"name":"playbackReport","versions":[1]}]}}
```

- [ ] **Step 2: Write the failing test**

`core/network/src/test/java/app/muplay/network/CapabilityNegotiatorTest.java`:

```java
package app.muplay.network;

import static com.google.common.truth.Truth.assertThat;

import app.muplay.model.ServerCapabilities;
import app.muplay.model.SubsonicCredentials;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.HttpUrl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CapabilityNegotiatorTest {

  private MockWebServer server;
  private CapabilityNegotiator negotiator;

  @Before
  public void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    HttpUrl base = server.url("/");
    String url = base.toString().substring(0, base.toString().length() - 1);
    negotiator =
        new CapabilityNegotiator(
            SubsonicClient.create(SubsonicCredentials.create(url, "alice", "sesame")));
  }

  @After
  public void tearDown() throws Exception {
    server.close();
  }

  @Test
  public void negotiate_recordsVersionsNotJustPresence() throws Exception {
    enqueue("ping_navidrome.json");
    enqueue("getOpenSubsonicExtensions_navidrome.json");

    ServerCapabilities caps = negotiator.negotiate().get();

    assertThat(caps.supports("songLyrics")).isTrue();
    assertThat(caps.supports("songLyrics", 1)).isTrue();
    // v2 differs materially from v1 — a boolean would lose this.
    assertThat(caps.supports("songLyrics", 2)).isTrue();
    assertThat(caps.supports("transcodeOffset", 1)).isTrue();
    assertThat(caps.supports("transcodeOffset", 2)).isFalse();
  }

  @Test
  public void negotiate_reportsIndexBasedQueueSupport() throws Exception {
    enqueue("ping_navidrome.json");
    enqueue("getOpenSubsonicExtensions_navidrome.json");

    ServerCapabilities caps = negotiator.negotiate().get();

    // savePlayQueue has a duplicate-track bug in Navidrome; we need the
    // index-based variant, so this capability is load-bearing.
    assertThat(caps.supports("indexBasedQueue", 1)).isTrue();
  }

  @Test
  public void negotiate_apiKeyAuthenticationIsNotAdvertisedByNavidrome() throws Exception {
    enqueue("ping_navidrome.json");
    enqueue("getOpenSubsonicExtensions_navidrome.json");

    ServerCapabilities caps = negotiator.negotiate().get();

    // Not implemented as of 0.63.2, despite third-party claims. If this ever
    // starts failing, Navidrome shipped it and we can drop password storage.
    assertThat(caps.supports("apiKeyAuthentication")).isFalse();
  }

  @Test
  public void negotiate_legacyServerWithoutOpenSubsonicYieldsEmptyCapabilities()
      throws Exception {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                "{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\","
                    + "\"type\":\"subsonic\",\"serverVersion\":\"6.0\","
                    + "\"openSubsonic\":false}}")
            .build());

    ServerCapabilities caps = negotiator.negotiate().get();

    // No second request should be made — nothing to ask.
    assertThat(server.getRequestCount()).isEqualTo(1);
    assertThat(caps.supports("songLyrics")).isFalse();
    assertThat(caps.isOpenSubsonic()).isFalse();
  }

  private void enqueue(String fixture) throws Exception {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(FixtureContractTest.fixture(fixture))
            .build());
  }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :core:network:testDebugUnitTest --tests '*CapabilityNegotiatorTest*'`
Expected: FAIL — `ServerCapabilities` and `CapabilityNegotiator` do not exist.

- [ ] **Step 4: Write `ServerCapabilities`**

`core/model/src/main/java/app/muplay/model/ServerCapabilities.java`:

```java
package app.muplay.model;

import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Negotiated OpenSubsonic capabilities.
 *
 * <p>Versions are stored as a list rather than a boolean because extensions are
 * versioned independently and differ materially between versions — {@code
 * songLyrics} v2 adds word-level timing that v1 has no representation for.
 */
public record ServerCapabilities(
    boolean isOpenSubsonic, @Nonnull Map<String, List<Integer>> extensions) {

  public static final ServerCapabilities NONE = new ServerCapabilities(false, Map.of());

  public ServerCapabilities {
    extensions = Map.copyOf(extensions);
  }

  public boolean supports(@Nonnull String extension) {
    return extensions.containsKey(extension);
  }

  public boolean supports(@Nonnull String extension, int version) {
    return extensions.getOrDefault(extension, List.of()).contains(version);
  }
}
```

- [ ] **Step 5: Extend the DTO and API, then write the negotiator**

Add to `SubsonicResponse.Body` — a new component after `musicFolders`:

```java
      @Nullable List<OpenSubsonicExtension> openSubsonicExtensions) {}

  public record OpenSubsonicExtension(
      @Nullable String name, @Nullable java.util.List<Integer> versions) {}
```

(Import `java.util.List` at the top of `SubsonicResponse.java`.)

Add to `SubsonicApi`:

```java
  @GET("rest/getOpenSubsonicExtensions")
  Call<SubsonicResponse> getOpenSubsonicExtensions(@QueryMap Map<String, String> auth);
```

Add to `SubsonicClient`:

```java
  @Nonnull
  public ListenableFuture<java.util.Map<String, List<Integer>>> getOpenSubsonicExtensions() {
    return enqueue(
        api.getOpenSubsonicExtensions(
            auth.authParams(credentials, SubsonicAuth.randomSaltSupplier())),
        body -> {
          java.util.Map<String, List<Integer>> out = new java.util.LinkedHashMap<>();
          List<SubsonicResponse.OpenSubsonicExtension> exts = body.openSubsonicExtensions();
          if (exts != null) {
            for (SubsonicResponse.OpenSubsonicExtension e : exts) {
              if (e.name() != null) {
                out.put(e.name(), List.copyOf(Objects.requireNonNullElse(e.versions(), List.of())));
              }
            }
          }
          return java.util.Map.copyOf(out);
        });
  }
```

`core/network/src/main/java/app/muplay/network/CapabilityNegotiator.java`:

```java
package app.muplay.network;

import app.muplay.model.ServerCapabilities;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import javax.annotation.Nonnull;

/**
 * Three-tier capability negotiation: {@code ping} establishes reachability and
 * whether the server speaks OpenSubsonic at all; only then is it worth asking
 * for the extension list.
 */
public final class CapabilityNegotiator {

  private final SubsonicClient client;

  public CapabilityNegotiator(@Nonnull SubsonicClient client) {
    this.client = client;
  }

  @Nonnull
  public ListenableFuture<ServerCapabilities> negotiate() {
    return FluentFuture.from(client.ping())
        .transformAsync(
            info -> {
              if (!info.openSubsonic()) {
                return Futures.immediateFuture(ServerCapabilities.NONE);
              }
              return FluentFuture.from(client.getOpenSubsonicExtensions())
                  .transform(
                      exts -> new ServerCapabilities(true, exts),
                      MoreExecutors.directExecutor());
            },
            MoreExecutors.directExecutor());
  }
}
```

- [ ] **Step 6: Add the fixture to the contract test**

Add to `FixtureContractTest`:

```java
  @Test
  public void getOpenSubsonicExtensionsFixtureMatchesSpec() throws IOException {
    validator.assertValid(
        "/getOpenSubsonicExtensions", fixture("getOpenSubsonicExtensions_navidrome.json"));
  }
```

- [ ] **Step 7: Run all network tests to verify they pass**

Run: `./gradlew :core:network:testDebugUnitTest`
Expected: PASS — all tests including the four negotiation cases.

- [ ] **Step 8: Commit**

```bash
git add core testing
git commit -m "feat(network): three-tier OpenSubsonic capability negotiation"
```

---

## Task 6: The PR gate

**Files:**
- Create: `.github/workflows/pr.yml`
- Create: `config/detekt` → not applicable; use `config/checkstyle/checkstyle.xml`
- Modify: root `build.gradle` to add JaCoCo

**Interfaces:**
- Consumes: all previous tasks
- Produces: a green PR gate under 10 minutes with no emulator

- [ ] **Step 1: Write the workflow**

`.github/workflows/pr.yml`:

```yaml
name: PR
on:
  pull_request:
  push:
    branches: [main]

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  gate:
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: zulu
          java-version: 17
      - uses: gradle/actions/setup-gradle@v6

      - name: Architecture rules
        run: ./gradlew :app:testDebugUnitTest --tests '*ArchitectureTest*'

      - name: Unit tests
        run: ./gradlew testDebugUnitTest

      - name: Coverage
        run: ./gradlew jacocoTestReport

      - name: Upload reports
        if: failure()
        uses: actions/upload-artifact@v7
        with:
          name: test-reports
          path: '**/build/reports/tests/**'
```

- [ ] **Step 2: Add JaCoCo to the root build**

Append to `build.gradle`'s `subprojects` block:

```groovy
  apply plugin: 'jacoco'

  tasks.withType(Test).configureEach {
    finalizedBy tasks.matching { it.name == 'jacocoTestReport' }
    maxParallelForks = Math.max(1, Runtime.runtime.availableProcessors().intdiv(2))
    forkEvery = 100
    maxHeapSize = '4g'
  }
```

- [ ] **Step 3: Verify the whole gate passes locally**

Run: `./gradlew testDebugUnitTest jacocoTestReport`
Expected: PASS. Note the wall-clock time; it must stay well under 10 minutes.

- [ ] **Step 4: Commit**

```bash
git add .github build.gradle
git commit -m "ci: PR gate with architecture, unit and coverage jobs"
```

---

## Task 7: Spikes S1 and S3 (blocking)

These answer questions the rest of the roadmap depends on. They produce a written
finding, not shipped code.

**Files:**
- Create: `docs/superpowers/spikes/2026-08-21-s1-local-network-permission.md`
- Create: `docs/superpowers/spikes/2026-08-21-s3-m4b-chapters-over-http.md`

- [ ] **Step 1: S1 — does `ACCESS_LOCAL_NETWORK` gate `10.0.2.2`?**

On an API 36 emulator, force the future behaviour on and see whether an app can
still reach the host loopback alias:

```bash
adb shell am compat enable RESTRICT_LOCAL_NETWORK app.muplay
adb reboot
# after boot, without granting the permission, attempt an HTTP GET to
# http://10.0.2.2:<port> from an instrumented test and record the result
```

Record: does the request succeed, time out, or fail with `EPERM`? Then grant
*Nearby devices* and retry.

**Why it matters:** if RFC1918 addresses are gated, every containerized-backend
test needs `ACCESS_LOCAL_NETWORK` in the **debug** manifest, and possibly a
different CI networking approach. This affects all seven plans.

- [ ] **Step 2: S3 — M4B chapters over HTTP**

Generate a chaptered M4B, serve it through Navidrome with `format=raw`, and
attempt extraction with `androidx.media3.inspector.MetadataRetriever`.

```bash
cat > /tmp/chapters.txt <<'EOF'
;FFMETADATA1
title=Test Audiobook
artist=Test Narrator
album=Test Audiobook
album_artist=Test Author

[CHAPTER]
TIMEBASE=1/1000
START=0
END=5000
title=Chapter One

[CHAPTER]
TIMEBASE=1/1000
START=5000
END=10000
title=Chapter Two
EOF

ffmpeg -y -f lavfi -i "sine=frequency=330:duration=15:sample_rate=44100" \
  -i /tmp/chapters.txt -map_metadata 1 -c:a aac -b:a 32k -ac 1 \
  -bitexact -movflags +faststart /tmp/book.m4b

ffprobe -v error -show_chapters -of json /tmp/book.m4b
```

Expected from ffprobe: 3 chapter entries.

> **Do not** add `-movflags +use_metadata_tags`. It writes `mdta/keys` atoms that
> taglib cannot read, and Navidrome will scan the file as `[Unknown Artist]` /
> `[Unknown Album]` — chapters survive but all other metadata is lost.

Then, in an instrumented test against the Navidrome container, retrieve track
groups for the streamed URL and assert at least one `Chapter` metadata entry with
title "Chapter One" and `startTimeMs == 0`.

**Why it matters:** this is the audiobook differentiator. If chapters cannot be
extracted from a remotely-served `faststart` file, Plan 4 needs a different
approach (server-side pre-extraction, or the split-file convention only).

- [ ] **Step 3: Write up both findings**

Each spike document states: the question, exactly what was run, the observed
result, and the decision. If a finding contradicts the spec, **update the spec**
— the spec is the artefact later plans are written from.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/spikes
git commit -m "docs: spike findings for local-network permission and M4B chapters"
```

---

## Task 8: Nightly lane with a real Navidrome

**Files:**
- Create: `ci/navidrome.compose.yml`
- Create: `ci/fixtures/` (committed audio, ~183 KiB)
- Create: `ci/seed-fixtures.sh`
- Create: `.github/workflows/nightly.yml`
- Test: `app/src/androidTest/java/app/muplay/LiveContractTest.java`

**Interfaces:**
- Consumes: `SubsonicClient`, `CapabilityNegotiator`
- Produces: a nightly job proving the client works against a real server

- [ ] **Step 1: Write the compose file**

`ci/navidrome.compose.yml`:

```yaml
services:
  navidrome:
    image: deluan/navidrome:0.63.2
    ports: ["4533:4533"]
    environment:
      # This is the real flag. ND_DEFAULTADMINPASSWORD does not exist, and the
      # `navidrome user create` CLI reads the password from a TTY, so piping
      # stdin does not work.
      ND_DEVAUTOCREATEADMINPASSWORD: testpass
      ND_SCANSCHEDULE: "0"
      ND_LOGLEVEL: warn
      ND_ENABLESHARING: "true"
    volumes:
      - ./fixtures:/music:ro
    healthcheck:
      # The image has wget and nc but NO curl, and /rest/ping.view returns 200
      # even on auth failure — so match the body, not the status code.
      test: ["CMD-SHELL", "wget -qO- 'http://localhost:4533/rest/ping.view?v=1.16.1&c=hc&f=json' | grep -q navidrome"]
      interval: 2s
      retries: 30
```

- [ ] **Step 2: Write the fixture generator**

`ci/seed-fixtures.sh` — run once locally and **commit the output**. ffmpeg is
not preinstalled on `ubuntu-latest`, so fixtures must be committed, not generated
in CI.

```bash
#!/usr/bin/env bash
set -euo pipefail
# -bitexact as an OUTPUT option is what makes this reproducible; -fflags
# +bitexact alone still leaves TAG:encoder=Lavf. Ogg/Opus embed a random
# stream serial without it.
OUT="$(dirname "$0")/fixtures"
mkdir -p "$OUT/Music/Test Artist/Test Album" "$OUT/Audiobooks/Test Author/Test Book"

for i in 1 2 3; do
  ffmpeg -y -f lavfi -i "sine=frequency=$((330 + i * 55)):duration=5:sample_rate=44100" \
    -c:a libmp3lame -b:a 64k -ac 1 -bitexact -map_metadata -1 \
    -metadata title="Track $i" -metadata artist="Test Artist" \
    -metadata album="Test Album" -metadata track="$i" \
    "$OUT/Music/Test Artist/Test Album/0$i - Track $i.mp3"
done

cp /tmp/book.m4b "$OUT/Audiobooks/Test Author/Test Book/Test Book.m4b"
find "$OUT" -type f -exec md5sum {} \; | sort -k2 > "$OUT/../fixtures.md5"
```

- [ ] **Step 3: Write the live contract test**

`app/src/androidTest/java/app/muplay/LiveContractTest.java`:

```java
package app.muplay;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.platform.app.InstrumentationRegistry;
import app.muplay.model.MusicLibrary;
import app.muplay.model.ServerCapabilities;
import app.muplay.model.SubsonicCredentials;
import app.muplay.network.CapabilityNegotiator;
import app.muplay.network.SubsonicClient;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Runs against the pinned Navidrome container via adb reverse. */
public class LiveContractTest {

  private static SubsonicClient client() {
    String url =
        InstrumentationRegistry.getArguments()
            .getString("TEST_SERVER_URL", "http://127.0.0.1:4533");
    return SubsonicClient.create(SubsonicCredentials.create(url, "admin", "testpass"));
  }

  @Test
  public void pingsARealNavidrome() throws Exception {
    SubsonicClient.ServerInfo info = client().ping().get(30, TimeUnit.SECONDS);
    assertThat(info.type()).isEqualTo("navidrome");
    assertThat(info.openSubsonic()).isTrue();
  }

  @Test
  public void discoversBothSeededLibraries() throws Exception {
    List<MusicLibrary> libraries = client().getMusicFolders().get(30, TimeUnit.SECONDS);
    assertThat(libraries.stream().map(MusicLibrary::name))
        .containsAtLeast("Music", "Audiobooks");
  }

  @Test
  public void advertisesIndexBasedQueue() throws Exception {
    ServerCapabilities caps =
        new CapabilityNegotiator(client()).negotiate().get(30, TimeUnit.SECONDS);
    assertThat(caps.isOpenSubsonic()).isTrue();
    assertThat(caps.supports("indexBasedQueue", 1)).isTrue();
  }
}
```

- [ ] **Step 4: Write the nightly workflow**

`.github/workflows/nightly.yml`:

```yaml
name: Nightly
on:
  schedule: [{ cron: '0 3 * * *' }]
  workflow_dispatch:

jobs:
  instrumented:
    runs-on: ubuntu-latest
    timeout-minutes: 55
    strategy:
      matrix:
        api-level: [30, 34]
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with: { distribution: zulu, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v6

      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' \
            | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules
          sudo udevadm trigger --name-match=kvm

      - name: Start Navidrome
        run: |
          docker compose -f ci/navidrome.compose.yml up -d --wait
          # Do NOT poll scanning==false: it is already false before the startup
          # scan begins. Poll the expected track count instead.
          Q='v=1.16.1&c=ci&f=json&u=admin&p=testpass'
          for i in $(seq 1 60); do
            n=$(wget -qO- "http://localhost:4533/rest/getScanStatus.view?$Q" \
                | grep -o '"count":[0-9]*' | head -1 | cut -d: -f2)
            [ "$n" = "4" ] && break
            sleep 1
          done
          test "$n" = "4"

      - uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: ${{ matrix.api-level }}
          arch: x86_64
          target: google_apis
          emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim
          disable-animations: true
          emulator-boot-timeout: 900
          script: |
            adb reverse tcp:4533 tcp:4533
            ./gradlew :app:connectedDebugAndroidTest
```

- [ ] **Step 5: Verify locally**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait
adb reverse tcp:4533 tcp:4533
./gradlew :app:connectedDebugAndroidTest
```

Expected: three live contract tests pass.

- [ ] **Step 6: Commit**

```bash
git add ci .github app/src/androidTest
git commit -m "ci: nightly lane against a pinned Navidrome container"
```

---

## Self-review notes

**Spec coverage.** This plan implements §4 (auth, capability negotiation, client
identifier), §9 (module structure, Java discipline, stack choices), §10 (PR gate,
contract oracle, ArchUnit, nightly container), and §11 spikes S1 and S3. Library
scoping, playback, audiobooks, casting and integrations are Plans 2–7 by design.

**Deliberately deferred within this plan.** Credential storage in Android Keystore
lands in Plan 2, with the settings UI that needs it — building a keystore layer
with no UI to exercise it would be untestable scaffolding.

**Type consistency.** `SubsonicClient.create` is the single constructor used in
Tasks 4, 5 and 8. `ServerCapabilities.supports` has both the one-arg and two-arg
forms used in Task 5's tests. `LibraryRole.UNASSIGNED` is the default set in Task
4 and consumed by Plan 2's setup flow.

**JSON library.** Jackson, not Moshi: Moshi cannot deserialise Java records
without hand-written adapters and its codegen is Kotlin-only. Jackson has native
record support and `FAIL_ON_UNKNOWN_PROPERTIES` is disabled so that server-side
field additions never break the client.
