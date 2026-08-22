# MuPlay Kotlin Plan 1 — Foundation + Subsonic Client

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Kotlin/Compose Android project that authenticates against a **real
Navidrome container**, negotiates its OpenSubsonic capabilities, proves every
response shape against the vendored OpenAPI spec, and has **both CI tiers live** —
including an emulator end-to-end journey.

**Architecture:** Convention plugins in `build-logic` keep module config in one
place. `core/network` is Retrofit + kotlinx.serialization over OkHttp, returning
suspend functions. `core/testing` owns the OpenAPI validator and fakes. The app
module is a Compose shell with Navigation 3. Tier 1 runs against a pinned
Navidrome container; tier 2 runs the same client on a real emulator.

**Tech Stack:** Kotlin 2.4.10, AGP 9.x, KSP, Compose BOM 2026.08.00, Material 3,
Navigation 3, Retrofit + kotlinx.serialization, OkHttp 5.5.0, Room 2.8.4, Hilt,
JUnit 5 (JVM) / JUnit 4 (device), Turbine, JaCoCo.

**Spec:** `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`

## Global Constraints

- **Kotlin 2.4.10**, JDK 21 toolchain, Compose for all UI. `.kts` build scripts.
- **KSP only — KAPT is dead**, KSP1 removed upstream.
- `compileSdk 37`, `targetSdk 36`, `minSdk 26`.
- `data class` for models; **sealed interfaces for state and results**.
- Immutable `UiState` as `StateFlow` + `collectAsStateWithLifecycle()`.
- Repositories are the only entry point to data. **No domain layer.**
- Convention plugins in `build-logic/convention`; no copy-pasted build scripts.
- Subsonic identifier **`c=MuPlay`**, protocol `v=1.16.1`.
- **No mock frameworks.** Fakes only, where the real thing cannot run.
- **Branch coverage ≥ 90%** per module, generated code excluded, JaCoCo merging
  JVM + emulator data.
- **Both tiers must be green to merge.** Tier 2 is the emulator.

---

## What already exists in this repo

`master` currently holds the **Java** implementation, tagged `java-prototype`.
Task 1 removes it. These files are **kept** and must survive:

- `ci/navidrome.compose.yml`, `ci/configure-libraries.sh`, `ci/seed-fixtures.sh`,
  `ci/fixtures/**`, `ci/fixtures.md5`
- `testing/src/main/resources/openapi/opensubsonic-1.16.1.json` → moves to
  `core/testing/src/main/resources/openapi/`
- `docs/**`, `LICENSE`, `README.md`

Hard-won facts encoded in those CI files — do not "simplify" them away:

- The admin password variable is **`ND_DEVAUTOCREATEADMINPASSWORD`**.
  `ND_DEFAULTADMINPASSWORD` does not exist.
- The image has **no `curl`**, and `/rest/ping.view` returns **200 even on auth
  failure** — the healthcheck matches the **response body**.
- Navidrome's library 1 is **pinned to its mount path and undeletable**; that is
  why `configure-libraries.sh` drives the REST API to create a second library.

---

## Task 1: Kotlin/Compose skeleton with convention plugins

**Files:**
- Delete: `app/src/**`, `core/model/**`, `core/network/**`, `testing/**`, `build.gradle`, `settings.gradle`, `config/**`
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`
- Create: `build-logic/settings.gradle.kts`, `build-logic/convention/build.gradle.kts`
- Create: `build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt`, `AndroidLibraryConventionPlugin.kt`, `AndroidComposeConventionPlugin.kt`, `JvmLibraryConventionPlugin.kt`, `AndroidHiltConventionPlugin.kt`
- Create: `app/build.gradle.kts`, `core/model/build.gradle.kts`, `core/network/build.gradle.kts`, `core/testing/build.gradle.kts`
- Move: the vendored OpenAPI spec into `core/testing/src/main/resources/openapi/`
- Create: `app/src/main/AndroidManifest.xml`, `MuPlayApplication.kt`, `MainActivity.kt`
- Test: `app/src/test/kotlin/app/muplay/ConventionTest.kt`

**Interfaces:**
- Produces: modules `:core:model`, `:core:network`, `:core:testing`, `:app`;
  convention plugin ids `muplay.android.application`, `muplay.android.library`,
  `muplay.android.compose`, `muplay.jvm.library`, `muplay.android.hilt`

**Why convention plugins first.** Ten modules are coming. Without them every
module's build file drifts independently, and the 90% coverage rule, the JVM
toolchain and the Compose setup get configured four different ways. Now in
Android does exactly this and it is the single highest-leverage build decision.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/app/muplay/ConventionTest.kt`:

```kotlin
package app.muplay

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the build itself. Every rule here exists because it silently rotted in a previous
 * incarnation of this project: build files drifting apart, a scan matching zero files and passing
 * vacuously, or a banned dependency arriving transitively.
 */
class ConventionTest {

  private fun repoRoot(): File {
    var dir = File(".").absoluteFile
    repeat(8) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile ?: return@repeat
    }
    error("could not locate the repository root from ${File(".").absolutePath}")
  }

  private fun moduleBuildFiles(): List<File> =
    repoRoot().walkTopDown()
      .onEnter { it.name != "build" && it.name != ".git" }
      .filter { it.name == "build.gradle.kts" }
      .filter { it.parentFile.name != "convention" }
      .toList()

  @Test
  fun `the scan finds build files at all`() {
    // A rule that silently scans nothing is the failure mode every rule here guards against.
    assertThat(moduleBuildFiles()).isNotEmpty()
  }

  @Test
  fun `no module configures android or kotlin blocks directly`() {
    // Module build files declare plugins and dependencies. Everything else belongs in a
    // convention plugin, or the ten modules drift apart one edit at a time.
    val offenders = moduleBuildFiles()
      .filter { it.parentFile.name != "build-logic" }
      .filter { f ->
        val text = f.readText()
        Regex("""^\s*(compileOptions|kotlinOptions|compilerOptions)\s*\{""", RegexOption.MULTILINE)
          .containsMatchIn(text)
      }
    assertThat(offenders).describedAs("configure these in a convention plugin").isEmpty()
  }

  @Test
  fun `no mock framework is on any classpath`() {
    val banned = listOf("mockito", "mockk", "easymock", "powermock")
    val catalogue = File(repoRoot(), "gradle/libs.versions.toml").readText().lowercase()
    banned.forEach { assertThat(catalogue).doesNotContain(it) }
    moduleBuildFiles().forEach { f ->
      val text = f.readText().lowercase()
      banned.forEach { assertThat(text).describedAs(f.path).doesNotContain(it) }
    }
  }

  @Test
  fun `no module uses kapt`() {
    moduleBuildFiles().forEach {
      assertThat(it.readText()).describedAs(it.path).doesNotContain("kapt")
    }
  }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest`
Expected: FAIL — nothing exists yet.

- [ ] **Step 3: Remove the Java implementation**

```bash
git rm -r --quiet app/src core/model core/network testing config build.gradle settings.gradle
git rm --quiet gradle/libs.versions.toml
mkdir -p core/testing/src/main/resources/openapi
git show java-prototype:testing/src/main/resources/openapi/opensubsonic-1.16.1.json \
  > core/testing/src/main/resources/openapi/opensubsonic-1.16.1.json
```

`ci/**`, `docs/**`, `LICENSE`, `README.md` and the Gradle wrapper stay. Verify
the wrapper still works before going further: `./gradlew --version`.

- [ ] **Step 4: Write the version catalogue**

`gradle/libs.versions.toml` — pin every version here, nowhere else:

```toml
[versions]
kotlin = "2.4.10"
agp = "9.0.0"
ksp = "2.4.10-2.0.4"
composeBom = "2026.08.00"
material3 = "1.4.0"
navigation3 = "1.1.6"
media3 = "1.11.0"
okhttp = "5.5.0"
retrofit = "2.11.0"
serialization = "1.11.0"
coroutines = "1.11.0"
room = "2.8.4"
hilt = "2.60.1"
coil = "3.5.0"
junit5 = "6.1.3"
assertj = "3.27.7"
turbine = "1.2.1"
androidxTest = "1.7.0"
androidxTestExt = "1.3.0"
jacoco = "0.8.12"
openapiValidator = "3.0.0"

[libraries]
compose-bom            = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui             = { module = "androidx.compose.ui:ui" }
compose-ui-tooling     = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
compose-material3      = { module = "androidx.compose.material3:material3", version.ref = "material3" }
activity-compose       = { module = "androidx.activity:activity-compose", version = "1.12.0" }
lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version = "2.10.0" }
lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version = "2.10.0" }
navigation3-runtime    = { module = "androidx.navigation3:navigation3-runtime", version.ref = "navigation3" }
navigation3-ui         = { module = "androidx.navigation3:navigation3-ui", version.ref = "navigation3" }
okhttp                 = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-coroutines      = { module = "com.squareup.okhttp3:okhttp-coroutines", version.ref = "okhttp" }
okhttp-mockwebserver   = { module = "com.squareup.okhttp3:mockwebserver3-junit5", version.ref = "okhttp" }
retrofit               = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-serialization = { module = "com.squareup.retrofit2:converter-kotlinx-serialization", version.ref = "retrofit" }
serialization-json     = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
coroutines-core        = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-test        = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
hilt-android           = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler          = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
junit-jupiter          = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit5" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj                = { module = "org.assertj:assertj-core", version.ref = "assertj" }
turbine                = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
androidx-test-runner   = { module = "androidx.test:runner", version.ref = "androidxTest" }
androidx-test-ext      = { module = "androidx.test.ext:junit", version.ref = "androidxTestExt" }
openapi-validator      = { module = "com.atlassian.oai:openapi-request-validator-core", version.ref = "openapiValidator" }

[plugins]
android-application    = { id = "com.android.application", version.ref = "agp" }
android-library        = { id = "com.android.library", version.ref = "agp" }
kotlin-android         = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm             = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose         = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization   = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp                    = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt                   = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

**Verify every coordinate resolves before moving on.** Several of these are
version-sensitive: the KSP version is `<kotlin>-<ksp>` and must match the Kotlin
version exactly; `mockwebserver3-junit5` and `converter-kotlinx-serialization`
are the modern artifact names. Check `maven-metadata.xml` for anything that does
not resolve and **record every substitution** as `plan said X, published is Y,
used Y because Z`.

- [ ] **Step 5: Write the convention plugins**

`build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt` sets:
JDK 21 toolchain, `compileSdk 37`, `minSdk 26`, Kotlin `jvmTarget` 21, JUnit 5
via `useJUnitPlatform()`, and the JaCoCo wiring. `AndroidApplicationConventionPlugin`
adds `targetSdk 36`. `AndroidComposeConventionPlugin` applies
`org.jetbrains.kotlin.plugin.compose` and the Compose BOM.
`AndroidHiltConventionPlugin` applies Hilt **with `ksp(...)`, never `kapt`**.

Register them in `build-logic/convention/build.gradle.kts` under the ids listed
in the Interfaces block.

- [ ] **Step 6: Write the module build files and the app shell**

Each module build file should contain **only** a `plugins {}` block referencing a
convention plugin and a `dependencies {}` block — the `ConventionTest` enforces
that. `MainActivity` sets `enableEdgeToEdge()` and hosts a Material 3 `Scaffold`
with a placeholder. Nothing else yet.

- [ ] **Step 7: Run the tests and commit**

Run: `./gradlew build`
Expected: PASS, 4/4 in `ConventionTest`.

```bash
git add -A
git commit -m "build: Kotlin/Compose skeleton with convention plugins"
```

---

## Task 2: Subsonic authentication

**Files:**
- Create: `core/model/src/main/kotlin/app/muplay/model/SubsonicCredentials.kt`
- Create: `core/network/src/main/kotlin/app/muplay/network/SubsonicAuth.kt`
- Test: `core/network/src/test/kotlin/app/muplay/network/SubsonicAuthTest.kt`

**Interfaces:**
- Produces:
  - `SubsonicCredentials(baseUrl: String, username: String, password: String)`
  - `SubsonicAuth.authParams(credentials, salt: String): Map<String, String>` →
    keys `u`, `t`, `s`, `v`, `c`, `f`
  - `SubsonicAuth.token(password: String, salt: String): String` — lowercase hex MD5
  - `SubsonicAuth.CLIENT_NAME = "MuPlay"`, `PROTOCOL_VERSION = "1.16.1"`

- [ ] **Step 1: Write the failing test**

```kotlin
package app.muplay.network

import app.muplay.model.SubsonicCredentials
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SubsonicAuthTest {

  private val credentials = SubsonicCredentials("https://music.example", "alice", "sesame")

  @Test
  fun `token matches the canonical Subsonic vector`() {
    // From the Subsonic API documentation. An independent oracle: this value was not
    // produced by the implementation under test.
    assertThat(SubsonicAuth.token("sesame", "c19b2d")).isEqualTo("26719a1196d2a940705a59634eb18eab")
  }

  @Test
  fun `token is lowercase hex and preserves leading zeros`() {
    // The classic bug: BigInteger or Integer.toHexString silently drops a leading zero byte,
    // which only fails for about one salt in sixteen. Assert the shape, not just one value.
    repeat(64) { i ->
      val token = SubsonicAuth.token("pw", "salt$i")
      assertThat(token).hasSize(32)
      assertThat(token).matches("[0-9a-f]{32}")
    }
  }

  @Test
  fun `non-ascii passwords hash as utf-8`() {
    // A platform-default charset here is a silent auth failure for real users.
    assertThat(SubsonicAuth.token("Ünïcödé-🎵", "abc"))
      .isEqualTo(SubsonicAuth.token("Ünïcödé-🎵", "abc"))
    assertThat(SubsonicAuth.token("Ünïcödé-🎵", "abc")).matches("[0-9a-f]{32}")
  }

  @Test
  fun `auth params carry the client identifier and protocol version`() {
    val params = SubsonicAuth.authParams(credentials, "c19b2d")
    assertThat(params).containsEntry("u", "alice")
    assertThat(params).containsEntry("s", "c19b2d")
    assertThat(params).containsEntry("t", "26719a1196d2a940705a59634eb18eab")
    assertThat(params).containsEntry("v", "1.16.1")
    assertThat(params).containsEntry("c", "MuPlay")
    assertThat(params).containsEntry("f", "json")
  }

  @Test
  fun `the password never appears in the parameters`() {
    // Plaintext auth must never be emitted, and no stray key may carry it.
    val params = SubsonicAuth.authParams(credentials, "c19b2d")
    assertThat(params).doesNotContainKey("p")
    assertThat(params.values).noneMatch { it.contains("sesame") }
  }

  @Test
  fun `credentials do not leak the password in toString`() {
    assertThat(credentials.toString()).doesNotContain("sesame")
  }
}
```

> `c=MuPlay` is not cosmetic. Navidrome's `Subsonic.LegacyClients` defaults to
> `DSub` and `MinimalClients` to `SubMusic`; a client whose identifier matches
> either gets the **entire OpenSubsonic field block stripped** from responses.

- [ ] **Step 2: Run it, confirm it fails, then implement**

`SubsonicCredentials` is a `data class` with a hand-written `toString()` that
omits the password. Note that a `data class` generates a `toString()` including
every property — overriding it is required, and the test above is what catches a
future regression.

Salt generation belongs to the caller so it can be made deterministic in tests;
production wiring must use `SecureRandom` and a fresh salt **per request**.

- [ ] **Step 3: Commit**

```bash
git add core/model core/network
git commit -m "feat(network): Subsonic token authentication"
```

---

## Task 3: The OpenAPI fixture validator

**Files:**
- Create: `core/testing/src/main/kotlin/app/muplay/testing/OpenApiFixtureValidator.kt`
- Test: `core/testing/src/test/kotlin/app/muplay/testing/OpenApiFixtureValidatorTest.kt`
- Uses: the vendored spec restored in Task 1

**Interfaces:**
- Produces: `OpenApiFixtureValidator.assertValid(endpointPath: String, jsonBody: String)`

**Why this exists.** Every later task asserts response shapes. If those
assertions are written against whatever the code produces, they prove nothing.
This validator is the **external oracle** that makes them meaningful.

**The path argument is the vendored spec's literal `paths` key: `/rest/<operationId>`** —
e.g. `/rest/ping`, `/rest/getMusicFolders`. Not `/ping`, no `.view` suffix.
Verified: the spec has 87 paths, all in that form.

- [ ] **Step 1: Write the failing test**

The suite must prove the validator **rejects**, not just accepts:

```kotlin
class OpenApiFixtureValidatorTest {
  private val validPing =
    """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.63.2","openSubsonic":true}}"""

  @Test fun `accepts a valid ping`() { OpenApiFixtureValidator.assertValid("/rest/ping", validPing) }

  @Test fun `rejects a missing required field`() { /* drop "status" -> expect failure */ }

  @Test fun `rejects an extra undefined nested field`() { /* add junk inside subsonic-response */ }

  @Test fun `rejects a wrong-typed field`() { /* openSubsonic as a string */ }

  @Test fun `rejects an unknown endpoint path naming it`() { /* "/rest/notAnEndpoint" */ }
}
```

A validator that accepts everything is worse than none — it manufactures
confidence. If a rejection case cannot be made to fail, say so with evidence
rather than quietly writing an accept-only suite.

- [ ] **Step 2: Implement**

Load the spec once, lazily, into a shared instance — it is ~453 KB and
re-parsing it per assertion would show up in the tier 1 budget. Build the
validator with **`withResolveCombinators(true)`**: the spec composes responses
with `allOf`, and without it even a valid fixture fails because each branch flags
the other's fields as additional properties. Verify that this does **not** relax
additional-properties strictness — the rejection tests above are what prove it.

Fail loudly and by name on an unknown path.

- [ ] **Step 3: Commit**

```bash
git add core/testing
git commit -m "test: OpenAPI fixture validator as an external oracle"
```

---

## Task 4: Response models and the Subsonic client

**Files:**
- Create: `core/network/src/main/kotlin/app/muplay/network/model/SubsonicResponse.kt`
- Create: `core/network/src/main/kotlin/app/muplay/network/SubsonicApi.kt`
- Create: `core/network/src/main/kotlin/app/muplay/network/SubsonicClient.kt`
- Create: `core/network/src/main/kotlin/app/muplay/network/SubsonicException.kt`
- Create: `core/model/src/main/kotlin/app/muplay/model/MusicLibrary.kt`, `LibraryRole.kt`
- Test: `core/network/src/test/kotlin/app/muplay/network/SubsonicClientTest.kt`

**Interfaces:**
- Produces:
  - `suspend SubsonicClient.ping(): ServerInfo`
  - `suspend SubsonicClient.getMusicFolders(): List<MusicLibrary>`
  - sealed `SubsonicException` with `SubsonicErrorException(code)` and `SubsonicHttpException(status)`
  - `MusicLibrary(id: Int, name: String, role: LibraryRole)`
  - `enum LibraryRole { MUSIC, AUDIOBOOKS, UNASSIGNED }`

**The trap this task exists to handle.** Subsonic returns **HTTP 200 even for
errors** — the failure lives in the JSON body as `"status":"failed"` with an
`error` object carrying a numeric code. A client that trusts the HTTP status
treats every failure as success, and that would poison every later plan.

- [ ] **Step 1: Write the failing tests against MockWebServer**

Real HTTP over a real socket, through the real Retrofit + kotlinx.serialization
stack. Cover at least:

```kotlin
@Test fun `ping parses server identity`()
@Test fun `a status failed body becomes a typed error, not a success`()   // code 40, HTTP 200
@Test fun `an unparseable body does not read as a Subsonic error`()       // must not degrade
@Test fun `getMusicFolders maps libraries with role UNASSIGNED`()
@Test fun `a musicFolder without a name gets a stable fallback`()
```

The second is the load-bearing one. Serve `HTTP 200` with
`{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}`
and assert a `SubsonicErrorException` with `code == 40`.

Detect failure on `error != null` **or** `status == "failed"` — the schema
requires both together, but a non-compliant server or a mangling proxy should not
read as success.

- [ ] **Step 2: Implement, then commit**

`@Serializable` data classes with `@SerialName("subsonic-response")` on the
envelope. Configure the `Json` instance with `ignoreUnknownKeys = true` —
servers add fields over time and an unknown one must never break a client.

```bash
git add core/network core/model
git commit -m "feat(network): Subsonic client with typed error mapping"
```

---

## Task 5: Capability negotiation

**Files:**
- Create: `core/model/src/main/kotlin/app/muplay/model/ServerCapabilities.kt`
- Create: `core/network/src/main/kotlin/app/muplay/network/CapabilityNegotiator.kt`
- Modify: `SubsonicApi`, `SubsonicResponse`
- Test: `core/network/src/test/kotlin/app/muplay/network/CapabilityNegotiatorTest.kt`

**Interfaces:**
- Produces:
  - `ServerCapabilities(isOpenSubsonic: Boolean, extensions: Map<String, List<Int>>)`
  - `ServerCapabilities.supports(name)` / `supports(name, version)`
  - `suspend CapabilityNegotiator.negotiate(): ServerCapabilities`

**The design point that must survive: extension support is a list of versions,
not a boolean.** OpenSubsonic extensions are versioned; a client that records
only "supported: yes" cannot tell v1 from v2 and will call an endpoint the
server does not implement. `supports(name, version)` is the real question.

Make `supports(name)` mean "advertised with at least one usable version", so it
cannot disagree with `supports(name, v)` when a server advertises an empty
version array — the schema permits that.

- [ ] **Steps: TDD the three tiers**

`ping` → is `openSubsonic` true → `getOpenSubsonicExtensions`. Both degraded
paths must be real and tested against MockWebServer:

- A plain Subsonic server negotiates to "no extensions" — it must not throw or hang.
- An OpenSubsonic server whose extensions call **fails** (404 or a Subsonic error)
  degrades to `isOpenSubsonic = true` with **no extensions** — *not* to
  `NONE`. `ping` established OpenSubsonic-ness independently; collapsing the two
  facts would make that server indistinguishable from a legacy one.
- A **transport** failure must propagate, not degrade. "The server said no" and
  "we could not ask" are different, and only the first justifies degrading.

Navidrome 0.63.2 advertises `transcodeOffset`, `formPost`, `songLyrics`,
`indexBasedQueue`. **`apiKeyAuthentication` is not among them** despite
third-party claims — do not add it to a fixture.

```bash
git commit -m "feat(network): three-tier OpenSubsonic capability negotiation"
```

---

## Task 6: Compose app shell

**Files:**
- Create: `core/designsystem/**` — theme, colour scheme, typography
- Create: `app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt`, navigation entries
- Create: `feature/setup/**` — the first-run screen
- Test: `feature/setup/src/test/kotlin/.../SetupViewModelTest.kt`

**Interfaces:**
- Produces: a running app that takes a server URL, username and password, calls
  `ping`, and reports success or a typed failure.

**Requirements that are easy to miss:**

- `enableEdgeToEdge()` and a `Scaffold` — edge-to-edge is **enforced** at API 35+,
  and `setDecorFitsSystemWindows`/`setStatusBarColor` are disabled.
- **Predictive back** is default-on; opt in and handle it.
- **Navigation 3** (`androidx.navigation3`), not Navigation Compose.
- `UiState` as a **sealed interface**, exposed as `StateFlow`, collected with
  `collectAsStateWithLifecycle()`.

The ViewModel holds the logic and gets the unit tests, using Turbine to assert
the state sequence. Keep composables thin enough that they need no unit tests —
tier 2 covers them.

```bash
git commit -m "feat(ui): Compose shell and first-run setup"
```

---

## Task 7: Tier 1 — fast gate with a real Navidrome

**Files:**
- Create: `.github/workflows/pr.yml`
- Modify: root `build.gradle.kts` — JaCoCo, 90% floor
- Test: `core/network/src/test/kotlin/app/muplay/network/LiveNavidromeTest.kt`

**This tier runs a real Navidrome container.** Docker is not an emulator — the
container starts in 5–11 s against a 10-minute budget, so anything whose subject
is Navidrome's behaviour is tested against a real server here, not a fixture.

Reuse `ci/navidrome.compose.yml`, `ci/seed-fixtures.sh` and
`ci/configure-libraries.sh` unchanged. They encode facts that cost real time to
learn — see "What already exists" above.

- [ ] **Steps**

1. Workflow triggers on `pull_request` and `push` to **`master`** (this repo's
   default branch — a workflow triggering on a branch that does not exist is a
   gate that silently never runs).
2. `timeout-minutes: 10`, so the budget is self-enforcing.
3. Jobs: static analysis → unit + integration → live-Navidrome → contract.
4. **Prove the live test can fail** before trusting it: point it at a wrong
   password and observe a real failure. A green light you have never seen go red
   is not evidence.
5. JaCoCo verification with a **90% branch floor**, generated code excluded,
   merging JVM and (later) emulator execution data. Measure the real numbers; do
   not invent a round one. A floor the check cannot fail is worse than none.
6. Upload reports for **every** job that can fail, not just tests.

```bash
git commit -m "ci: tier 1 gate with a real Navidrome container"
```

---

## Task 8: Tier 2 — emulator end-to-end, required to merge

**Files:**
- Create: `.github/workflows/e2e.yml`
- Create: `app/src/androidTest/kotlin/app/muplay/FirstRunJourneyTest.kt`
- Modify: `app/build.gradle.kts` — `testInstrumentationRunner`, androidTest deps
- Create: `app/src/debug/AndroidManifest.xml` — cleartext for the local container

**This tier must be green to merge.** It is not nightly and not advisory.

**Environment facts:** an API 37 AVD named `muplay37` exists locally
(`google_apis`, `x86_64`); `/dev/kvm` is present. Start headless
(`-no-window -no-audio -gpu swiftshader_indirect`) and wait on `adb wait-for-device`
plus `sys.boot_completed` — **never a fixed sleep**. Do not touch the unrelated
AVDs `familyguard29` / `familyguard34`. In CI, `reactivecircus/android-emulator-runner`
is the standard action.

Reach the container from the emulator via `adb reverse`. Spike S1 established
that `ACCESS_LOCAL_NETWORK` gating keys off **`targetSdk`**, not device API — at
`targetSdk 36` it is inert, so no permission is needed today. It becomes live at
37, and a blocked connection manifests as a **silent connect timeout**, not an
error.

Cleartext must be **debug-only** — the app talks to a public HTTPS Navidrome in
production. Confirm no `usesCleartextTraffic` reaches the release manifest.

- [ ] **The first journey**

`FirstRunJourneyTest`: launch the app, enter the container's URL and credentials,
assert the app reaches a connected state and lists **both** seeded libraries
(`Music` and `Audiobooks`) by name. That last assertion is a real contract on
server state, not on response shape — a wrong-but-running server fails it.

Use `createAndroidComposeRule` — Compose UI tests run on a real emulator, so
banning Robolectric costs nothing here.

- [ ] **Prove it can fail, then commit**

Point it at a stopped container and a wrong password; observe both failures.

```bash
git commit -m "ci: tier 2 emulator end-to-end gate"
```

---

## Definition of done

1. Both tiers green.
2. **Tier 2 contains the first-run journey**, proven able to fail.
3. Branch coverage ≥ 90% on every module, from real measurement.
4. No mock framework in the dependency graph — `ConventionTest` enforces it.
5. Every response shape asserted against the vendored OpenAPI spec.
6. Anything discovered to be wrong in the spec is corrected **in the spec**.
