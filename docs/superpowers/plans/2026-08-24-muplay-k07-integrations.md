# MuPlay Kotlin Plan 7 — Bindery and Lidarr integrations

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** From inside MuPlay, a user who runs Lidarr can ask it for an album and a user who runs
Bindery can ask it for a book — configuring the service, submitting the request, watching its
status, and being shown the result once Navidrome has scanned it in. Both integrations are
**independently optional**: either, both, or neither. A user who runs neither service sees no new
screen, no disabled button, no empty state and no HTTP request — and nothing in Plans 1–6 can
compile against a single symbol this plan introduces.

**Architecture:** Four new modules, all under `integrations/` and `feature/requests/`, plus exactly
one line of wiring in `:app`. `:integrations:core` owns what both services share and nothing else:
the sealed service identity, the base-URL policy that resolves the cleartext-HTTP tension, a
credential store built on **`:core:database`'s existing Keystore AES-GCM mechanism** (not a second
one), and a **separate one-table Room database** for submitted requests. `:integrations:lidarr` and
`:integrations:bindery` are two independent Retrofit clients that know nothing about each other.
`:feature:requests` is the only Compose surface, and it renders *nothing at all* when neither
service is configured. Severability is enforced structurally, by a `ConventionTest` rule that fails
the build if any module outside `integrations/` and `:feature:requests` names an `:integrations:*`
project — so "Plans 1–6 do not depend on this" is a checked fact rather than a promise.

**Tech Stack:** Kotlin 2.4.10, JDK 21, AGP 9.3.1, **KSP** (never KAPT), Room 2.8.4, Hilt 2.60.1,
OkHttp 5.5.0 + MockWebServer, Retrofit 2.11.0 + kotlinx.serialization 1.11.0, Compose BOM
2026.08.00 + Material 3 1.4.0, Navigation 3 1.1.6, DataStore 1.2.1, JUnit 5 (JVM) / JUnit 4
(device), AssertJ, Turbine, JaCoCo 0.8.12. **No new third-party library is introduced by this
plan** — every artifact above is already in `gradle/libs.versions.toml`.

**Spec:** `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md` — **§8 Optional integrations**
is the whole of this plan's mandate. §4 supplies the auth and credential patterns to imitate, §9
the module layout (`integrations/*  bindery, lidarr`), §10 the testing regime, §11 the non-goals,
§12 the risk table.

**Roadmap:** `docs/superpowers/plans/2026-08-22-muplay-kotlin-roadmap.md` — Plan 7, *"Request books
and music from inside the app"*, depends on Plan 2. The roadmap ranks it last and says out loud
that Plans 5–7 *"are independent of each other and **can be reordered or dropped**"*. This plan is
written so that dropping it is a `git rm -r integrations feature/requests` plus three deletions,
and Task 1 builds the check that keeps that true.

---

## Global Constraints

Copied verbatim from `docs/superpowers/plans/2026-08-22-muplay-kotlin-roadmap.md`'s **Global
constraints**. Every task inherits these.

- **Kotlin 2.4.10**, JDK 21 toolchain, **Compose** for all UI.
- Licence **MIT**. No GPL code may be copied.
- `compileSdk 37`, `targetSdk 36`, `minSdk 26`. Play requires 36 from 2026-08-31.
- **KSP only. KAPT is dead** and KSP1 has been removed upstream.
- `data class` for models; **sealed interfaces for state and results**.
- Immutable `UiState` as `StateFlow`, collected with `collectAsStateWithLifecycle()`.
- Repositories are the only entry point to data. **No domain layer** unless logic is genuinely
  shared across features.
- Convention plugins in `build-logic/convention`. No copy-pasted build scripts.
- Subsonic client identifier **`c=MuPlay`**, protocol `v=1.16.1`.
- Stream requests force **`format=raw` or `format=mp3`**. **Never Opus.**
- Media3 cache keys derive from the **track id alone** via `setCustomCacheKey`.
- Book positions are **local only**.
- **No mock frameworks.** Fakes only, and only where the real thing cannot run.
- **Coverage ≥ 90% per module**, generated code excluded, enforced by **JaCoCo** merging JVM and
  emulator execution data (Kover cannot collect instrumented coverage). The metric differs by kind
  of code: **branch** coverage for non-UI code, **line** coverage for Compose UI, because the
  Compose compiler emits synthetic branches inside author method bodies that no test can reach and
  no class-level exclusion can filter. Every floor is measured, never invented, and **must be able
  to fail**; a module with no floor entry warns loudly.
- **Two-tier merge gate, both required.** Tier 1 ≤ 10 minutes with a real Navidrome container but
  no emulator. **Tier 2 is emulator end-to-end and must be green to merge.**
- Inject a `Clock`; no direct wall-clock reads outside the injection point.

Additionally, binding on this plan specifically:

- **Dependency minimalism.** This plan adds **no** new third-party artifact. If a task appears to
  need one, it is wrong — say so in the task report rather than adding it.
- **No Robolectric**, no Roborazzi, no ktlint/detekt/spotless.
- **Cleartext HTTP is debug-only and must never reach the release manifest.** `verifyReleaseManifest`
  (`AndroidApplicationConventionPlugin`, wired into `check`) reads AGP's own **merged** release
  manifest and fails on `usesCleartextTraffic`. Task 1 resolves the tension this creates for
  LAN-hosted services explicitly, in code, rather than leaving it to the platform's error message.
- **Integration credentials are exactly as sensitive as the Navidrome password.** Sealed with the
  same AndroidKeystore AES-GCM mechanism, never logged, never written into a URL, never present in
  a committed fixture, and never in a crash report.

### Definition of done, per plan

Copied verbatim from the roadmap's **Definition of done, per plan**:

1. All tasks' tests pass; both tiers green.
2. **Tier 2 carries this plan's E2E journeys.** A plan is not done until its journeys are in the
   emulator suite.
3. Coverage ≥ 90% on every module the plan touches — **branch** for non-UI code, **line** for
   Compose UI. Every floor measured, and able to fail.
4. No mock framework has entered the dependency graph.
5. Every new external-API assumption is backed by a contract test against the vendored OpenAPI
   spec, or a live test against the Navidrome container.
6. Anything discovered to be wrong in the spec is corrected **in the spec**.

> **Item 5 is the sharpest constraint in this plan and it does not translate literally.** The
> vendored OpenSubsonic OpenAPI spec says nothing about Lidarr or Bindery, and the Navidrome
> container is not either service. The equivalent for this plan, and what every task below is
> written to satisfy, is: **every external-API assumption is backed by a live test against a real,
> pinned container of the service in question, or is explicitly marked unverified with a
> first-step procedure for verifying it.** Task 10 adds the Lidarr container to Tier 1 exactly the
> way Plan 1 added the Navidrome one. Where no container exists, the plan says so out loud instead
> of inventing an endpoint — see **Research provenance** below.

---

## The severability contract — read this before Task 1

The roadmap says Plans 5–7 *"can be reordered or dropped"*. That sentence is worth nothing unless
dropping this one is actually cheap, and "actually cheap" has three testable meanings. Every one of
them is a checked fact in this plan, not an aspiration.

**1. No module from Plans 1–6 may reference an `:integrations:*` symbol.** Enforced by a new
`ConventionTest` rule (Task 1) that scans every `build.gradle.kts` in the tree and fails if any
project outside `integrations/` and `feature/requests/` declares `project(":integrations:...")`.
This is a build-file scan for the same reason `no mock framework is declared in any build file`
is: a dependency has to be declared to be used, and a declaration is greppable in a way a use is
not.

**2. A user who runs neither service must see nothing.** Not a greyed-out row, not an empty state
saying "no requests yet", not a settings entry that opens onto a form. **Nothing.** The Requests
destination is absent from `MuPlayApp`'s navigation entirely when neither service is configured,
and the entry point that would reach it is not rendered. Task 9 tests this with a state that
configures *neither* service and asserts absence — and Task 10's Tier 2 journey asserts the same
thing on a real screen, because a Compose test that never composed the parent is exactly the
vacuous gate §10 exists to prevent.

**3. Neither service may be reachable from the other's code path.** `:integrations:lidarr` does not
depend on `:integrations:bindery` or vice versa. A user with only Lidarr configured must issue
**zero** HTTP requests to any Bindery host — asserted by `MockWebServer.requestCount == 0` on a
Bindery server that was started and never spoken to, which is a stronger claim than "the Bindery
UI was not shown".

### The trap this plan is most likely to fall into

Every test in this plan will want to configure both services in its setup, because that is the
convenient fixture. **A "service not configured" path that every test configures around is a path
no test exercises**, and it is the single most likely place for this plan to ship a defect: it is
the path a real user with only one service is on, permanently, and it is the path that decides
whether the feature degrades gracefully or crashes.

The rule for this plan, stated once here and repeated at each point of use: **every test class that
touches configuration state must exercise all four combinations** — neither, Lidarr only, Bindery
only, both — or explain in a comment why one of the four is genuinely not reachable from the code
under test. A test file that only ever constructs "both configured" is rejected at review.

---

## What earlier plans hand this plan — consume it, do not rebuild it

**This plan consumes symbols from Plan 1 and Plan 2 only.** It deliberately consumes **nothing**
from Plans 3, 4, 5 or 6: no `MuPlayer`, no `MediaController`, no `PlaybackConnection`, no chapter
extraction, no cast route. That is not an accident of scope — it is what makes the roadmap's
"reorderable" claim true, and it means this plan can be executed the day Plan 2 lands.

| Symbol | Module | Origin |
|---|---|---|
| `SubsonicCredentials(baseUrl, username, password)` — note its `toString()` redacts the password | `:core:model` | Plan 1. **Committed.** |
| `MusicLibrary(id: Int, name: String, role: LibraryRole)`, `LibraryRole.{MUSIC, AUDIOBOOKS, UNASSIGNED}` | `:core:model` | Plan 1/2. **Committed.** |
| `Album(id, libraryId, name, artistId, artistName, coverArtId, songCount, durationSeconds)` | `:core:model` | Plan 2. **Committed.** |
| `SearchResults(artists, albums, songs)` with `isEmpty` | `:core:model` | Plan 2. **Committed.** |
| `KeystoreCipher.seal(key: SecretKey, plaintext: String): ByteArray` / `.open(key, sealed): String` | `:core:database` | Plan 2 Task 1. **Committed.** Task 1 below reuses this object *unchanged*. |
| `CredentialStore(save/load/clear/credentials/keyExists)` | `:core:database` | Plan 2 Task 1–2. **Committed.** Task 1 below refactors its key handling and changes none of its behaviour or its public API. |
| `LibraryRepository.idsWithRole(role): List<Int>`, `.libraries: Flow<List<MusicLibrary>>` | `:core:database` | Plan 2 Task 4. |
| `BrowseRepository.search(libraryId: Int, query: String, limit: Int): SearchResults`, `.album(albumId): Album?` | `:core:database` | Plan 2 Task 5. |
| `SyncEngine.syncIfStale(): SyncState`; `SyncState.{UpToDate, ScanInProgress, Synced(libraries), Failed(cause)}` | `:core:database` | Plan 2 Task 6. Task 8 below **calls** it and does not modify it. |
| `MuPlayApp`, `StartDestination`, the Navigation 3 back stack | `:app` | Plan 2 Task 10. |
| `verifyReleaseManifest`, `VerifyMergedManifestTask`, `ConventionTest`, `coverageFloors`, `ci/mutation-probes.sh` | build/CI | Plan 1. **Committed.** |

If any of these landed under a different name than the row says, **use the real one and say so in
the task report**. Do not add a second provider, a second search, or a second sync engine.

### Hard facts about this repository, verified while this plan was written

- **`ConventionTest` will fail a new Gradle project that has no `coverageFloors` entry** (`every
  Gradle project has a coverage floor`, `app/src/test/kotlin/app/muplay/ConventionTest.kt`). Four
  new modules therefore need four measured entries, and Task 10 is where they are measured rather
  than invented.
- **`no module configures android or kotlin blocks directly`** allows only `namespace` in a
  module's own `android { }` block. Everything else goes in a convention plugin.
- **`no mock framework is declared in any build file or convention plugin`** scans build files for
  the banned coordinates. Hand-written fakes only.
- **`excludeByteBuddyFromInstrumentedTests`** (build-logic `Testing.kt`) already strips Byte Buddy
  from every `androidTest*` configuration project-wide, so a new module can put AssertJ on a device
  without rediscovering the `mergeExtDexDebugAndroidTest` failure.
- **`configureJUnit5` excludes tests tagged `"live"` from every ordinary `Test` task**, and only a
  task explicitly named otherwise includes them. `liveNavidromeTest` is the existing precedent
  (root `build.gradle.kts` + `LIVE_NAVIDROME_TEST_TASK_NAME` in `Testing.kt`, kept in sync by
  `ConventionTest`'s `the live-Navidrome test task name is not hand-synced into drift`). Task 10
  adds `liveLidarrTest` the same way — including the same drift check, because a second
  hand-synced constant with no check is the first one's mistake repeated.
- **`app/src/debug/AndroidManifest.xml` is the only place `usesCleartextTraffic` exists**, and
  `verifyReleaseManifest` proves it never reaches the merged release manifest. Task 1 is built on
  that fact and does not weaken it.
- **The Room convention plugin exports schemas to `<module>/schemas`** via a tracked
  `CommandLineArgumentProvider`. A second Room database in a different module gets its own schema
  directory for free.

### The defect class this plan is written against

Six review rounds on this project have found the same failure, each time one "unit" further out
than the last: **assertions that execute but do not discriminate.** Endpoint, then request
parameter, then type, then field, then collection order, then argument passthrough on a delegating
method. The rules that bind every test in this plan:

1. **The unit is the field.** For every field this plan's code assigns, an assertion must fail when
   that field becomes a constant.
2. **A value observed at exactly one value is not tested.** Vary only that argument, hold the rest,
   assert both observations. **And a value observed only as an empty list is not observed at all.**
3. **`allMatch`/`anyMatch`/`none` are vacuously true on an empty collection.** Map the field and
   assert the exact list with `containsExactly`.
4. **Order is a property** where order is meaningful.
5. **A delegating method must be proved to pass its argument through**, not merely to have been
   called. A repository method that forwards to a DAO can discard its argument and hardcode a value
   with every test still green — this project has shipped that exact defect.
6. **A gate reporting the absence of a problem must be provably incapable of staying quiet when it
   did not run.**
7. **Coverage floors cannot catch this class.** A constant field assignment removes no branch.
   Verification is at the bytecode level (`ci/mutation-probes.sh`), not by argument.

**The analogue that will bite this plan, named now so no task can pretend it was not warned:**

- **A test that asserts a request was *submitted* rather than that its body carried the right
  identifier.** `assertThat(server.requestCount).isEqualTo(1)` is satisfied by a client that POSTs
  an empty object. Every submit test in this plan reads the recorded request **body**, parses it,
  and asserts the specific field — at two different values.
- **A "service not configured" path that every test configures around.** See the severability
  contract above. Four combinations, every time.
- **A status mapper observed on one status.** A `RequestStatus` derived from a service's response
  must be observed at every value the service can produce, from real captured payloads, mapped and
  asserted as an exact list — not `anyMatch { it is Downloading }`.

---

## Cleartext HTTP: the tension, named and resolved

**The tension is real and it is this plan's sharpest design problem.** Self-hosted Lidarr and
Bindery instances overwhelmingly run as plain HTTP on a LAN — `http://192.168.1.20:8686` is the
normal case, not the exotic one. Meanwhile this project's constraints say cleartext HTTP is
**debug-only and must never reach the release manifest**, and `verifyReleaseManifest` enforces that
against AGP's own merged artifact. Those two facts pull in opposite directions, and every
self-hosted Android client that has faced this (Ultrasonic, Tempo, Jellyfin, Audiobookshelf) has
resolved it by shipping `android:usesCleartextTraffic="true"` — which opts the entire app out of
Android's network security defaults for **every** host it will ever contact, including the one
holding the user's Navidrome password.

### What is actually true about the platform

- `android:usesCleartextTraffic` defaults to **false** for apps targeting API 28 and above, and
  MuPlay targets 36.
- OkHttp consults the platform: `NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)`.
  When it returns false, the request fails with
  `java.net.UnknownServiceException: CLEARTEXT communication to <host> not permitted by network
  security policy` — thrown from deep inside the connection attempt, at request time, with no
  useful context for the user.
- `res/xml/network_security_config.xml` is **static XML read at install time.** There is no runtime
  API to add a permitted domain. A user's LAN address is not knowable at build time, and NSC's
  `<domain>` elements are hostnames, not CIDR ranges — so "permit cleartext for RFC 1918
  addresses" is **not expressible**. This is the fact that closes off the clever middle path, and
  it is why the resolution below is a policy decision rather than a configuration trick.

### The resolution

**Release builds are HTTPS-only for these integrations, the app says so at configuration time in
its own words, and the release manifest is not weakened.** Three parts, all in Task 1:

1. **`IntegrationBaseUrl` has no public constructor.** It is produced only by
   `IntegrationBaseUrl.parse(raw, policy)`, which returns a sealed result. `IntegrationCredentials`
   holds an `IntegrationBaseUrl`, not a `String`, so a Lidarr or Bindery client is *structurally
   incapable* of being handed a URL that never went through the policy. Same argument as
   `StreamFormat` in Plan 3 and non-null `musicFolderId: Int` in Plan 2: make the wrong value
   unrepresentable rather than checking for it at each call site.
2. **The policy is a value, chosen by variant source set, never by a `BuildConfig.DEBUG` branch.**
   `app/src/debug/kotlin/.../CleartextPolicyModule.kt` provides `CleartextPolicy.Allowed`;
   `app/src/release/kotlin/.../CleartextPolicyModule.kt` provides `CleartextPolicy.Forbidden`.
   There is no `if` anywhere, the release binary contains no reference to `Allowed`, and **both
   branches of behaviour are testable in Tier 1** by passing the policy directly to
   `IntegrationBaseUrl.parse` — which is exactly what a `BuildConfig.DEBUG` branch would have made
   impossible, since a JVM unit test of a release-only path cannot run.
3. **The two mechanisms cannot silently disagree.** The manifest permits cleartext in debug only;
   the policy permits cleartext in debug only. They are derived from different files, so Task 1
   adds a `ConventionTest` rule that reads both source sets and fails if the release module
   provides anything but `Forbidden`, or if either variant's file is missing. A policy that
   defaulted open because someone deleted a file is the failure this rule exists to catch.

The user-visible message when a release build is given an `http://` URL is written out in Task 1
and is deliberately actionable — it names reverse proxies, Tailscale and Caddy rather than saying
"invalid URL".

### What this costs, recorded rather than hidden

A user whose Lidarr is plain HTTP on a LAN **cannot configure it in a release build of MuPlay**.
That is a real cost and it is worth stating three ways:

- It is **the same rule the Navidrome connection already lives under**. Plan 1 shipped a release
  build that cannot talk to a cleartext Navidrome either. This plan does not add a new restriction;
  it declines to make an exception for the *optional* feature that would have needed one.
- It is the **conservative direction for a secret**. A Lidarr API key on a cleartext LAN is
  recoverable by anything on that LAN, and this app holds it beside a Navidrome password.
- **It is reversible by a later decision, with evidence.** If it turns out to block real use, the
  honest change is a `network_security_config.xml` with an explicit, enumerated, user-visible
  exception list — and a spec amendment saying so. It is not a silent
  `usesCleartextTraffic="true"`.

Task 10 writes this decision into spec §8, because a design decision that lives only in a plan is a
decision the next spec reader will make differently.

---

## Research provenance

<!--RESEARCH_PROVENANCE-->

---

## Task list

<!--TASK_LIST-->

---

## File Structure

<!--FILE_STRUCTURE-->

---

## Task 1: `:integrations:core` — the module, the severability rule, and a base URL that cannot carry a secret

**Files:**
- Create: `integrations/core/build.gradle.kts`
- Create: `integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationService.kt`
- Create: `integrations/core/src/main/kotlin/app/muplay/integrations/CleartextPolicy.kt`
- Create: `integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationBaseUrl.kt`
- Create: `integrations/core/src/test/kotlin/app/muplay/integrations/IntegrationBaseUrlTest.kt`
- Create: `app/src/debug/kotlin/app/muplay/di/CleartextPolicyModule.kt`
- Create: `app/src/release/kotlin/app/muplay/di/CleartextPolicyModule.kt`
- Modify: `settings.gradle.kts` — `include(":integrations:core")`
- Modify: `build.gradle.kts` — a `":integrations:core"` entry in `coverageFloors`
- Modify: `app/src/test/kotlin/app/muplay/ConventionTest.kt` — two new rules
- Modify: `app/build.gradle.kts` — `implementation(project(":integrations:core"))`

**Interfaces:**
- Consumes: nothing from any earlier plan except the build-logic convention plugins
  (`muplay.android.library`, `muplay.android.hilt`) and `libs.okhttp`.
- Produces:
  - `enum class IntegrationService { LIDARR, BINDERY }` with `val displayName: String`
  - `sealed interface CleartextPolicy` with `data object Allowed` and `data object Forbidden`
  - `class IntegrationBaseUrl private constructor(val value: String)` — `value` always ends in
    `/`, never carries a query, a fragment or userinfo; `toString()` returns `value`; `equals`
    and `hashCode` delegate to `value`
  - `IntegrationBaseUrl.Companion.parse(raw: String, policy: CleartextPolicy): BaseUrlResult`
  - `sealed interface BaseUrlResult` with `data class Valid(val url: IntegrationBaseUrl)`,
    `data object Blank`, `data object MissingScheme`, `data object Malformed`,
    `data class CleartextForbidden(val host: String)`
  - `BaseUrlResult.message(service: IntegrationService): String?` — `null` for `Valid`, an
    actionable sentence for every failure

### Why the base URL is its own type with a private constructor

Two separate requirements meet on this one value and neither can be met by a check at the call
site.

**Requirement 1 — a credential must never end up in a URL.** The brief for this plan says it
outright, and the reason is concrete: a URL is the thing that ends up in an OkHttp log line, in a
`MockWebServer` recorded request, in a committed fixture, and in the `message` of an
`IOException` that a crash reporter uploads. Lidarr *does* accept its API key as a query parameter
(see **Research provenance**), which means a user pasting a URL out of their browser's address bar
after clicking around the Lidarr UI can hand this app a string with the key already in it. So
`parse` **discards the query, the fragment and any userinfo unconditionally** — not "validates
they are absent", *discards* — and a test asserts a pasted `?apikey=` does not survive into the
stored value.

**Requirement 2 — the cleartext policy must be unbypassable.** See *Cleartext HTTP: the tension,
named and resolved* above. If the policy were a check performed by each client, there would be two
clients, two checks, and eventually three. Because `IntegrationCredentials` (Task 2) holds an
`IntegrationBaseUrl` and `IntegrationBaseUrl` has no public constructor, a Lidarr or Bindery client
**cannot be constructed** around a URL that did not go through the policy. This is the same
structural move as Plan 3's `StreamFormat` (Opus is unrepresentable) and Plan 2's non-null
`musicFolderId: Int` (a blank scope is unrepresentable).

OkHttp's own `HttpUrl` parser does the parsing, deliberately: the string this produces is the one
Retrofit will call `baseUrl()` with, so validation and use must agree about what a URL is. A
hand-rolled `Regex` would eventually disagree with the thing that actually connects.

### Why `MissingScheme` is a separate result from `Malformed`

`"192.168.1.20:8686"` is what a user types. `HttpUrl.parse` returns `null` for it, exactly as it
does for `"not a url at all"`, so collapsing the two produces the message *"that is not a valid
URL"* for a string the user reasonably believes is one. Splitting them costs one branch and buys a
message that says *"start the address with `https://`"*. This project has a standing preference for
the specific error over the general one — `SetupFailureReason` in `:feature:setup` is the same
shape.

**Trap: `http://` under `CleartextPolicy.Allowed` is still not a free pass.** It is permitted only
because the debug variant's manifest also permits it. If someone ever removes
`app/src/debug/AndroidManifest.xml`'s `usesCleartextTraffic`, `parse` would happily accept a URL
that then fails at request time with `UnknownServiceException`. Step 9's `ConventionTest` rule is
what keeps the two files in agreement, and it is written in this task rather than in Task 10
because a rule added after the code it governs has already been reviewed once with nobody looking
at it.

- [ ] **Step 1: Create the module and register it**

`settings.gradle.kts` — add below the existing `include` lines:

```kotlin
include(":integrations:core")
```

`integrations/core/build.gradle.kts`:

```kotlin
plugins {
  id("muplay.android.library")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.integrations"
}

dependencies {
  // OkHttp's own URL parser, not a Regex: the string `IntegrationBaseUrl` produces is handed
  // straight to `Retrofit.Builder().baseUrl(...)`, which parses it with this same class. A
  // separate validator would eventually disagree with the thing that actually connects.
  implementation(libs.okhttp)
  implementation(libs.coroutines.core)
}
```

`build.gradle.kts` — add to `coverageFloors`. The numbers are **measured in Step 8 of this task**
and re-measured in Task 10; the entry's shape has to exist now or `ConventionTest`'s
`every Gradle project has a coverage floor` fails from this commit onward:

```kotlin
  // `:integrations:core`. `IntegrationBaseUrl`'s parse cascade is pure Kotlin over OkHttp's URL
  // parser with no Android dependency at all -- which is why it is a Tier-1-enforceable BRANCH
  // floor and why it lives in this module rather than inside either client. Measured in Task 1
  // Step 8; re-measured in Task 10 once the credential store and the request store are in.
  ":integrations:core" to listOf(
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.integrations.IntegrationBaseUrl*"),
    ),
  ),
```

`app/build.gradle.kts` — add to `dependencies`:

```kotlin
  // The only edge from :app into integrations/. Task 9 adds :feature:requests beside it; nothing
  // else in the tree may name an :integrations:* project, and `ConventionTest`'s
  // `nothing outside integrations depends on an integration` rule (Step 9) enforces that.
  implementation(project(":integrations:core"))
```

- [ ] **Step 2: Confirm the module resolves before writing anything else**

Run: `./gradlew :integrations:core:dependencies --configuration debugRuntimeClasspath | grep -i okhttp`
Expected: `com.squareup.okhttp3:okhttp:5.5.0` and its `okio` transitive, and **nothing else new** —
in particular no Retrofit and no kotlinx.serialization. This module is deliberately not an HTTP
client; the two service modules are.

- [ ] **Step 3: Write the failing base-URL test**

`integrations/core/src/test/kotlin/app/muplay/integrations/IntegrationBaseUrlTest.kt`:

```kotlin
package app.muplay.integrations

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The whole contract of the one value both integrations are built on.
 *
 * Two separate jobs are tested here and they are tested separately on purpose: **a credential
 * must never survive into the stored URL**, and **the cleartext policy must be unbypassable**.
 * Each failure member is observed at a real input, and every accepted URL is observed at *two*
 * different inputs, because a `parse` that returned a constant `IntegrationBaseUrl` would satisfy
 * a single-observation test of either job.
 */
class IntegrationBaseUrlTest {

  private fun valid(raw: String, policy: CleartextPolicy = CleartextPolicy.Forbidden): String {
    val result = IntegrationBaseUrl.parse(raw, policy)
    assertThat(result).isInstanceOf(BaseUrlResult.Valid::class.java)
    return (result as BaseUrlResult.Valid).url.value
  }

  @Test
  fun `an https url is accepted and normalised to end in a slash`() {
    // Two observations of the host, so a hardcoded return value fails one of them.
    assertThat(valid("https://lidarr.example.com")).isEqualTo("https://lidarr.example.com/")
    assertThat(valid("https://books.example.net")).isEqualTo("https://books.example.net/")
  }

  @Test
  fun `a url that already ends in a slash is not given a second one`() {
    assertThat(valid("https://lidarr.example.com/")).isEqualTo("https://lidarr.example.com/")
  }

  /**
   * The reverse-proxy case. Servarr apps support a `urlBase` setting, so a real deployment is
   * commonly at `https://home.example.com/lidarr` rather than at a host root — and Retrofit
   * resolves a relative path against a base URL by *replacing* the last path segment unless the
   * base ends in `/`. Getting this wrong turns `api/v1/system/status` into
   * `https://home.example.com/api/v1/system/status`, silently dropping the prefix.
   */
  @Test
  fun `a url base path is preserved and terminated with a slash`() {
    assertThat(valid("https://home.example.com/lidarr")).isEqualTo("https://home.example.com/lidarr/")
    assertThat(valid("https://home.example.com/books")).isEqualTo("https://home.example.com/books/")
  }

  @Test
  fun `a non-default port is preserved`() {
    // Two ports, not one: a `parse` that hardcoded :8686 passes a single-observation test.
    assertThat(valid("https://nas.local:8686")).isEqualTo("https://nas.local:8686/")
    assertThat(valid("https://nas.local:9090")).isEqualTo("https://nas.local:9090/")
  }

  /**
   * The requirement this whole type exists for. Lidarr accepts its API key as a query parameter,
   * so a URL copied out of a browser address bar can arrive with the key already in it. `parse`
   * does not *reject* that — a rejection would be a dead end for a user who did nothing wrong —
   * it **discards** the query, so the secret cannot reach DataStore, a log line, a recorded
   * request or a crash report.
   */
  @Test
  fun `a query string is discarded, including one carrying an api key`() {
    assertThat(valid("https://lidarr.example.com/?apikey=SUPERSECRET"))
      .isEqualTo("https://lidarr.example.com/")
    assertThat(valid("https://lidarr.example.com/?apikey=SUPERSECRET")).doesNotContain("SUPERSECRET")
    assertThat(valid("https://lidarr.example.com/settings/general?x=1&y=2"))
      .isEqualTo("https://lidarr.example.com/settings/general/")
  }

  @Test
  fun `a fragment is discarded`() {
    assertThat(valid("https://lidarr.example.com/#/settings/general"))
      .isEqualTo("https://lidarr.example.com/")
  }

  /**
   * `https://user:hunter2@host` is the other way a secret rides on a URL, and OkHttp parses it
   * happily. Discarded for the same reason as the query.
   */
  @Test
  fun `userinfo is discarded`() {
    val parsed = valid("https://luc:hunter2@lidarr.example.com/")

    assertThat(parsed).isEqualTo("https://lidarr.example.com/")
    assertThat(parsed).doesNotContain("hunter2")
    assertThat(parsed).doesNotContain("luc")
  }

  @Test
  fun `surrounding whitespace is trimmed rather than rejected`() {
    // Pasting from a notes app or a terminal brings a trailing newline with it.
    assertThat(valid("  https://lidarr.example.com \n")).isEqualTo("https://lidarr.example.com/")
  }

  @Test
  fun `a blank url is Blank, and that is distinct from malformed`() {
    assertThat(IntegrationBaseUrl.parse("", CleartextPolicy.Forbidden)).isEqualTo(BaseUrlResult.Blank)
    assertThat(IntegrationBaseUrl.parse("   ", CleartextPolicy.Forbidden)).isEqualTo(BaseUrlResult.Blank)
  }

  /**
   * `192.168.1.20:8686` is what a user types, and `HttpUrl.parse` returns null for it exactly as
   * it does for a genuine non-URL. Collapsing the two would print "that is not a valid URL" at
   * someone who typed something entirely reasonable.
   */
  @Test
  fun `a url with no scheme is MissingScheme, not Malformed`() {
    assertThat(IntegrationBaseUrl.parse("192.168.1.20:8686", CleartextPolicy.Allowed))
      .isEqualTo(BaseUrlResult.MissingScheme)
    assertThat(IntegrationBaseUrl.parse("lidarr.example.com", CleartextPolicy.Allowed))
      .isEqualTo(BaseUrlResult.MissingScheme)
  }

  @Test
  fun `a non-http scheme is Malformed`() {
    assertThat(IntegrationBaseUrl.parse("ftp://lidarr.example.com", CleartextPolicy.Allowed))
      .isEqualTo(BaseUrlResult.Malformed)
    assertThat(IntegrationBaseUrl.parse("https://", CleartextPolicy.Allowed))
      .isEqualTo(BaseUrlResult.Malformed)
  }

  /**
   * The two halves of the cleartext resolution, each observed. Neither is satisfied by a `parse`
   * that ignores its `policy` argument: the first would fail if `parse` always allowed, the
   * second if it always forbade. **This is the argument-passthrough rule applied to a policy
   * object** — the defect class this project has already shipped six times is a method that
   * accepts an argument and then hardcodes the value.
   */
  @Test
  fun `http is accepted when the policy allows cleartext`() {
    assertThat(valid("http://192.168.1.20:8686", CleartextPolicy.Allowed))
      .isEqualTo("http://192.168.1.20:8686/")
    assertThat(valid("http://nas.local:8686", CleartextPolicy.Allowed))
      .isEqualTo("http://nas.local:8686/")
  }

  @Test
  fun `http is refused when the policy forbids cleartext, and the host is reported`() {
    // The host comes back in the result so the message can name it. Two different hosts, so a
    // hardcoded host fails.
    assertThat(IntegrationBaseUrl.parse("http://192.168.1.20:8686", CleartextPolicy.Forbidden))
      .isEqualTo(BaseUrlResult.CleartextForbidden("192.168.1.20"))
    assertThat(IntegrationBaseUrl.parse("http://nas.local:8686", CleartextPolicy.Forbidden))
      .isEqualTo(BaseUrlResult.CleartextForbidden("nas.local"))
  }

  @Test
  fun `https is accepted under both policies`() {
    // The policy must gate `http` and *only* `http`. Without this, a `parse` that refused
    // everything under `Forbidden` would still pass the test above it.
    assertThat(valid("https://lidarr.example.com", CleartextPolicy.Forbidden))
      .isEqualTo("https://lidarr.example.com/")
    assertThat(valid("https://lidarr.example.com", CleartextPolicy.Allowed))
      .isEqualTo("https://lidarr.example.com/")
  }

  @Test
  fun `the scheme check is case-insensitive`() {
    assertThat(valid("HTTPS://lidarr.example.com", CleartextPolicy.Forbidden))
      .isEqualTo("https://lidarr.example.com/")
    assertThat(IntegrationBaseUrl.parse("HTTP://nas.local", CleartextPolicy.Forbidden))
      .isEqualTo(BaseUrlResult.CleartextForbidden("nas.local"))
  }

  @Test
  fun `two urls with the same value are equal and hash alike`() {
    // The type is used as a map key and compared in tests; identity equality would make both
    // silently wrong.
    val a = (IntegrationBaseUrl.parse("https://a.example.com", CleartextPolicy.Forbidden) as BaseUrlResult.Valid).url
    val b = (IntegrationBaseUrl.parse("https://a.example.com/", CleartextPolicy.Forbidden) as BaseUrlResult.Valid).url
    val c = (IntegrationBaseUrl.parse("https://b.example.com", CleartextPolicy.Forbidden) as BaseUrlResult.Valid).url

    assertThat(a).isEqualTo(b)
    assertThat(a.hashCode()).isEqualTo(b.hashCode())
    assertThat(a).isNotEqualTo(c)
  }

  /**
   * Every failure member carries a message and every message names the service, so the same type
   * serves both configuration screens without either of them writing copy of its own.
   *
   * Asserted as an **exact mapped list**, not with `allMatch`: `allMatch` over a collection is
   * vacuously true if the collection is empty, and `isNotNull` on each would be satisfied by one
   * shared string.
   */
  @Test
  fun `every failure has a distinct actionable message and Valid has none`() {
    val failures = listOf(
      BaseUrlResult.Blank,
      BaseUrlResult.MissingScheme,
      BaseUrlResult.Malformed,
      BaseUrlResult.CleartextForbidden("nas.local"),
    )

    val messages = failures.map { it.message(IntegrationService.LIDARR) }

    assertThat(messages).doesNotContainNull()
    assertThat(messages).doesNotHaveDuplicates()
    // The service name is interpolated, so the same result under the other service reads
    // differently -- proving the argument is used rather than accepted and dropped.
    assertThat(BaseUrlResult.MissingScheme.message(IntegrationService.LIDARR))
      .contains("Lidarr").contains("https://")
    assertThat(BaseUrlResult.MissingScheme.message(IntegrationService.BINDERY))
      .contains("Bindery").contains("https://")
    // The cleartext message must name the host and say what to do about it, or it is a dead end.
    assertThat(BaseUrlResult.CleartextForbidden("nas.local").message(IntegrationService.LIDARR))
      .contains("nas.local")
      .contains("HTTPS")
    val valid = IntegrationBaseUrl.parse("https://a.example.com", CleartextPolicy.Forbidden)
    assertThat(valid.message(IntegrationService.LIDARR)).isNull()
  }

  @Test
  fun `the service display names are the ones a user reads`() {
    assertThat(IntegrationService.entries.map { it.displayName })
      .containsExactly("Lidarr", "Bindery")
  }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./gradlew :integrations:core:testDebugUnitTest --tests '*IntegrationBaseUrlTest*'`
Expected: FAIL — `Unresolved reference: IntegrationBaseUrl`.

- [ ] **Step 5: Implement `IntegrationService` and `CleartextPolicy`**

`integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationService.kt`:

```kotlin
package app.muplay.integrations

/**
 * The optional services MuPlay can request media from.
 *
 * An `enum`, not a sealed interface: this project reserves sealed interfaces for *state and
 * results* (roadmap global constraints), and this is a closed identity set with no per-member
 * data — the same shape as `LibraryRole` in `:core:model`.
 *
 * The order of the entries is load-bearing in exactly one place: every configuration screen and
 * every list renders `IntegrationService.entries` in declaration order, so both services appear
 * in the same order everywhere without any screen sorting them itself.
 */
enum class IntegrationService(val displayName: String) {
  LIDARR("Lidarr"),
  BINDERY("Bindery"),
}
```

`integrations/core/src/main/kotlin/app/muplay/integrations/CleartextPolicy.kt`:

```kotlin
package app.muplay.integrations

/**
 * Whether this build may talk to an integration over unencrypted HTTP.
 *
 * **This is a value, injected, and never a `BuildConfig.DEBUG` branch.** The debug and release
 * variants of `:app` provide different members from variant-specific source sets
 * (`app/src/debug/kotlin/...` and `app/src/release/kotlin/...`), which has two properties a
 * runtime branch would not have:
 *
 * - the release binary contains no reference to [Allowed] at all, so there is nothing to flip;
 * - **both behaviours are testable from a plain JVM unit test**, by passing the member directly to
 *   [IntegrationBaseUrl.parse]. A `BuildConfig.DEBUG` branch has one arm that no JVM test can ever
 *   reach, which is this project's definition of a gate that cannot fire.
 *
 * [Allowed] is only ever correct in a build whose *manifest* also permits cleartext. The two are
 * kept in agreement by `ConventionTest`'s
 * `the cleartext policy and the cleartext manifest cannot disagree`, not by convention.
 */
sealed interface CleartextPolicy {

  /** Debug builds only. `app/src/debug/AndroidManifest.xml` permits cleartext to match. */
  data object Allowed : CleartextPolicy

  /**
   * Release builds. An `http://` integration URL is refused at configuration time, with a message
   * the user can act on — rather than being accepted and then failing at request time with
   * OkHttp's `UnknownServiceException: CLEARTEXT communication to <host> not permitted by network
   * security policy`, which is thrown from inside a connection attempt and means nothing to
   * anyone.
   */
  data object Forbidden : CleartextPolicy
}
```

- [ ] **Step 6: Implement `IntegrationBaseUrl` and `BaseUrlResult`**

`integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationBaseUrl.kt`:

```kotlin
package app.muplay.integrations

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The base URL of a configured integration: always absolute, always `http`/`https`, always
 * terminated with `/`, and **never carrying a query, a fragment or userinfo**.
 *
 * The constructor is private and [parse] is the only way to get one. That is the whole design:
 * `IntegrationCredentials` holds this type rather than a `String`, so no client in this plan can
 * be constructed around a URL that has not been through the cleartext policy and the
 * secret-stripping below. Checking at each call site would work until the third call site.
 *
 * Not a `value class`: a `value class` over `String` erases to `String` at every JVM boundary,
 * which would let a plain `String` be passed where one of these is expected through any reflective
 * or generic path, and would make the private constructor much weaker than it looks.
 */
class IntegrationBaseUrl private constructor(val value: String) {

  override fun toString(): String = value

  override fun equals(other: Any?): Boolean =
    this === other || (other is IntegrationBaseUrl && value == other.value)

  override fun hashCode(): Int = value.hashCode()

  companion object {

    /**
     * Parses [raw] under [policy].
     *
     * The query, the fragment and any userinfo are **discarded rather than rejected**. Lidarr
     * accepts its API key as a query parameter, so a URL copied out of a browser address bar can
     * arrive with the key in it; rejecting that would be a dead end for a user who did nothing
     * wrong, while keeping it would put a secret into DataStore, into every OkHttp log line and
     * into the message of any `IOException` a crash reporter uploads.
     *
     * The path is kept, because Servarr applications support a `urlBase` and are commonly proxied
     * at `https://home.example.com/lidarr`. The trailing slash is added for Retrofit, which
     * resolves a relative path by replacing the last segment of a base URL that lacks one.
     */
    fun parse(raw: String, policy: CleartextPolicy): BaseUrlResult {
      val trimmed = raw.trim()
      if (trimmed.isEmpty()) return BaseUrlResult.Blank
      if (!hasHttpScheme(trimmed)) return BaseUrlResult.MissingScheme
      val parsed: HttpUrl = trimmed.toHttpUrlOrNull() ?: return BaseUrlResult.Malformed
      if (parsed.scheme == "http" && policy == CleartextPolicy.Forbidden) {
        return BaseUrlResult.CleartextForbidden(parsed.host)
      }
      return BaseUrlResult.Valid(IntegrationBaseUrl(normalise(parsed)))
    }

    /**
     * True when [candidate] starts with an `http`/`https` scheme, case-insensitively.
     *
     * Checked *before* [toHttpUrlOrNull] rather than inferred from its `null`, because
     * `HttpUrl` returns `null` for `"192.168.1.20:8686"` and for `"not a url"` alike — and those
     * two deserve different messages. `HttpUrl` accepts no other scheme, so a `ftp://` string
     * falls through to [BaseUrlResult.Malformed] via the `null`, which is the right answer for it.
     */
    private fun hasHttpScheme(candidate: String): Boolean {
      val lower = candidate.lowercase()
      return lower.startsWith("http://") || lower.startsWith("https://")
    }

    /** Strips every credential-bearing component, then guarantees the trailing slash. */
    private fun normalise(parsed: HttpUrl): String {
      val stripped = parsed.newBuilder()
        .username("")
        .password("")
        .query(null)
        .fragment(null)
        .build()
        .toString()
      return if (stripped.endsWith("/")) stripped else "$stripped/"
    }
  }
}

/**
 * The outcome of parsing a user-entered integration URL.
 *
 * A sealed interface with one success member and four distinct failures, rather than a nullable
 * return: every failure has a different thing the user should do about it, and `null` cannot say
 * which. [message] is here rather than in the UI so both configuration screens produce identical
 * copy without either of them owning it.
 */
sealed interface BaseUrlResult {

  data class Valid(val url: IntegrationBaseUrl) : BaseUrlResult

  /** Nothing was entered. Not an error to shout about; the save button is simply not enabled. */
  data object Blank : BaseUrlResult

  /** Something that looks like a host was entered with no `https://` in front of it. */
  data object MissingScheme : BaseUrlResult

  /** A scheme was present but the rest does not parse, or the scheme is not `http`/`https`. */
  data object Malformed : BaseUrlResult

  /**
   * An `http://` URL in a build where cleartext is [CleartextPolicy.Forbidden]. [host] is carried
   * so the message can name it — a message that says "unencrypted connections are not allowed"
   * without saying to *what* is not actionable.
   */
  data class CleartextForbidden(val host: String) : BaseUrlResult
}

/**
 * The sentence to show under the URL field, or `null` when there is nothing wrong.
 *
 * The [service] name is interpolated into every message so the copy reads correctly on both
 * configuration screens. The [BaseUrlResult.CleartextForbidden] message names concrete tools
 * because "use HTTPS" is not advice a self-hoster can act on at 11pm — see the plan's
 * *Cleartext HTTP* section for why this build cannot simply permit cleartext instead.
 */
fun BaseUrlResult.message(service: IntegrationService): String? = when (this) {
  is BaseUrlResult.Valid -> null
  BaseUrlResult.Blank -> "Enter the address of your ${service.displayName} server."
  BaseUrlResult.MissingScheme ->
    "Start the ${service.displayName} address with https:// — for example " +
      "https://${service.displayName.lowercase()}.example.com."
  BaseUrlResult.Malformed -> "That is not an address MuPlay can reach ${service.displayName} at."
  is BaseUrlResult.CleartextForbidden ->
    "MuPlay will not send your ${service.displayName} API key over an unencrypted connection, so " +
      "http://$host cannot be used. Put ${service.displayName} behind HTTPS — a reverse proxy " +
      "such as Caddy or nginx, or a private network such as Tailscale — and enter that address " +
      "instead."
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :integrations:core:testDebugUnitTest --tests '*IntegrationBaseUrlTest*'`
Expected: PASS, 17/17.

- [ ] **Step 8: Measure the floor, then prove it can fail**

Run:

```bash
./gradlew :integrations:core:jacocoTestReport
python3 - <<'PY'
import xml.etree.ElementTree as ET
t = ET.parse('integrations/core/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml')
for cls in t.iter('class'):
    name = cls.get('name').replace('/', '.')
    if 'IntegrationBaseUrl' not in name and 'CleartextPolicy' not in name and 'IntegrationService' not in name:
        continue
    for c in cls.findall('counter'):
        if c.get('type') in ('BRANCH', 'LINE'):
            m, cv = int(c.get('missed')), int(c.get('covered'))
            print(f"{name:70s} {c.get('type'):6s} {cv}/{cv+m}")
PY
```

Write the **measured** BRANCH ratio into `coverageFloors`, rounded **down** to two decimals, never
up and never to a round number that was not measured.

Then prove the floor is not decorative: comment out the
`a url with no scheme is MissingScheme, not Malformed` test, re-run
`./gradlew jacocoJvmCoverageVerification`, and **confirm it goes red**. Restore the test. A floor
whose matched classes carry no counters of its own kind passes at every minimum and gates nothing —
this project has shipped that once already.

- [ ] **Step 9: Add the two `ConventionTest` rules**

`app/src/test/kotlin/app/muplay/ConventionTest.kt` — two new tests. Read the file's existing
helpers first; it already has a `buildFiles`-style scan the first rule below should reuse rather
than duplicate.

```kotlin
  /**
   * The severability contract, as a check rather than a promise.
   *
   * The roadmap says Plans 5-7 "are independent of each other and can be reordered or dropped".
   * For Plan 7 that is only true while nothing else in the tree can compile against it, and the
   * cheapest honest way to know is that a dependency has to be **declared** to be used. A build
   * file naming `project(":integrations:...")` is greppable; a stray import is not.
   *
   * `:app` and `:feature:requests` are the two permitted consumers, and that is the whole
   * severability surface: deleting this plan is `git rm -r integrations feature/requests`, plus
   * these two edges, plus the `settings.gradle.kts` includes and the `coverageFloors` entries.
   */
  @Test
  fun `nothing outside integrations depends on an integration`() {
    val root = repoRoot()
    val permitted = setOf("app", "feature/requests")
    val offenders = moduleBuildFiles()
      .filter { file ->
        val path = file.parentFile.relativeTo(root).invariantSeparatorsPath
        !path.startsWith("integrations/") && path !in permitted
      }
      .filter { it.readText().contains("project(\":integrations:") }
      .map { it.relativeTo(root).invariantSeparatorsPath }

    assertThat(offenders)
      .describedAs(
        "Plan 7's integrations must stay severable: only :app and :feature:requests may depend " +
          "on an :integrations:* project. Anything else makes 'this plan can be dropped' false.",
      )
      .isEmpty()
  }

  /**
   * The cleartext policy and the cleartext manifest are written in two different files and must
   * say the same thing. Nothing in the type system connects them.
   *
   * `verifyReleaseManifest` already proves `usesCleartextTraffic` never reaches the merged release
   * manifest. This proves the *policy* side: the release source set provides `Forbidden`, the
   * debug source set provides `Allowed`, and both files exist. A missing file would not fail the
   * build loudly -- Hilt would fail to find a binding, which reads as an unrelated DI error -- and
   * a release module that provided `Allowed` would compile, pass every test, and ship an app that
   * accepts a cleartext URL it can then never connect to.
   */
  @Test
  fun `the cleartext policy and the cleartext manifest cannot disagree`() {
    val root = repoRoot()
    val debugModule = File(root, "app/src/debug/kotlin/app/muplay/di/CleartextPolicyModule.kt")
    val releaseModule = File(root, "app/src/release/kotlin/app/muplay/di/CleartextPolicyModule.kt")
    val debugManifest = File(root, "app/src/debug/AndroidManifest.xml")

    assertThat(debugModule).exists()
    assertThat(releaseModule).exists()

    assertThat(releaseModule.readText())
      .describedAs("the release variant must provide CleartextPolicy.Forbidden and nothing else")
      .contains("CleartextPolicy.Forbidden")
      .doesNotContain("CleartextPolicy.Allowed")

    assertThat(debugModule.readText())
      .describedAs("the debug variant provides Allowed, and only because the debug manifest does")
      .contains("CleartextPolicy.Allowed")
      .doesNotContain("CleartextPolicy.Forbidden")

    assertThat(debugManifest.readText())
      .describedAs(
        "CleartextPolicy.Allowed is only correct while the debug manifest also permits cleartext; " +
          "if this attribute is removed, the debug policy must become Forbidden in the same commit",
      )
      .contains("usesCleartextTraffic")
  }
```

`moduleBuildFiles()` and `repoRoot()` are the file's **existing** private helpers, read from
`app/src/test/kotlin/app/muplay/ConventionTest.kt` while this plan was written; reuse them rather
than adding a third scan. Note `moduleBuildFiles()` excludes `build-logic/convention` — which is
correct here, since a convention plugin naming an `:integrations:*` project would be a different
and much stranger problem. If the helpers have been renamed by the time this task runs, use the
real names and say so in the task report.

- [ ] **Step 10: Write the two variant Hilt modules**

`app/src/debug/kotlin/app/muplay/di/CleartextPolicyModule.kt`:

```kotlin
package app.muplay.di

import app.muplay.integrations.CleartextPolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The **debug** variant's cleartext policy.
 *
 * Permitted here and only here, because `app/src/debug/AndroidManifest.xml` is the only manifest
 * in this repository that carries `usesCleartextTraffic`, and because the Tier 1 live-container
 * tests and the Tier 2 emulator journey both talk to services over plain HTTP on localhost.
 *
 * There is a file with this exact fully-qualified name in `app/src/release/kotlin/` providing
 * `Forbidden`. Variant source sets are mutually exclusive, so exactly one of the two is compiled
 * into any given build, and the release binary contains no reference to `Allowed`.
 * `ConventionTest`'s `the cleartext policy and the cleartext manifest cannot disagree` is what
 * keeps this pair honest.
 */
@Module
@InstallIn(SingletonComponent::class)
object CleartextPolicyModule {

  @Provides
  @Singleton
  fun provideCleartextPolicy(): CleartextPolicy = CleartextPolicy.Allowed
}
```

`app/src/release/kotlin/app/muplay/di/CleartextPolicyModule.kt`:

```kotlin
package app.muplay.di

import app.muplay.integrations.CleartextPolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The **release** variant's cleartext policy: unencrypted HTTP is refused at configuration time.
 *
 * The consequence is stated plainly in the plan and in spec section 8: a user whose Lidarr or
 * Bindery is plain HTTP on a LAN cannot configure it in a release build. That is the same rule the
 * Navidrome connection already lives under — this build has never been able to reach a cleartext
 * Navidrome either — and it is the conservative direction for a value stored beside a password.
 * The alternative every comparable app ships, `android:usesCleartextTraffic="true"`, opts the
 * whole application out of Android's network security defaults for *every* host it will ever
 * contact.
 */
@Module
@InstallIn(SingletonComponent::class)
object CleartextPolicyModule {

  @Provides
  @Singleton
  fun provideCleartextPolicy(): CleartextPolicy = CleartextPolicy.Forbidden
}
```

- [ ] **Step 11: Run the full check**

Run: `./gradlew :app:testDebugUnitTest --tests '*ConventionTest*' :integrations:core:test :app:verifyReleaseManifest`
Expected: PASS. `verifyReleaseManifest` still passes — this task added no manifest attribute.

Then compile the release variant, which is the only thing that proves the release-only Hilt module
actually compiles and binds:

Run: `./gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL. If it fails with a missing `CleartextPolicy` binding, the release
source set is not being picked up — check the directory name is exactly `src/release/kotlin`.

- [ ] **Step 12: Commit**

```bash
git add settings.gradle.kts build.gradle.kts app/build.gradle.kts integrations/core \
  app/src/debug/kotlin app/src/release/kotlin app/src/test/kotlin/app/muplay/ConventionTest.kt
git commit -m "feat(integrations): a base URL that cannot carry a secret, and a severability rule

:integrations:core is the first module of Plan 7. IntegrationBaseUrl has a private
constructor and one factory, so no client can be built around a URL that has not been
through the cleartext policy -- and parse() discards query, fragment and userinfo
unconditionally, because Lidarr accepts its API key as a query parameter and a URL copied
from a browser address bar can arrive with it in.

Cleartext is resolved by variant source set rather than by a BuildConfig.DEBUG branch: the
release binary contains no reference to CleartextPolicy.Allowed, and both behaviours are
testable from a JVM unit test. ConventionTest gains two rules -- one keeping the policy and
the debug manifest in agreement, one keeping this plan severable by failing the build if
anything outside integrations/ and feature/requests names an :integrations:* project."
```

---

## Task 2: Integration credentials — the same seal as the Navidrome password, one key per service

**Files:**
- Create: `core/database/src/main/kotlin/app/muplay/database/KeystoreKeys.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/CredentialStore.kt` — delegate key
  handling to `KeystoreKeys`; **no public API and no behaviour changes**
- Create: `integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationCredentials.kt`
- Create: `integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationCredentialStore.kt`
- Create: `integrations/core/src/main/kotlin/app/muplay/integrations/di/IntegrationsDataModule.kt`
- Create: `integrations/core/src/androidTest/kotlin/app/muplay/integrations/IntegrationCredentialStoreTest.kt`
- Modify: `integrations/core/build.gradle.kts` — DataStore, `:core:database`, the instrumented
  test dependencies
- Modify: `build.gradle.kts` — `:integrations:core` gains its instrumented floors (measured in
  Task 10)

**Interfaces:**
- Consumes: `app.muplay.database.KeystoreCipher.seal/open` (unchanged), `IntegrationService`,
  `IntegrationBaseUrl`, `CleartextPolicy` (Task 1)
- Produces:
  - `object KeystoreKeys` in `:core:database` with
    `exists(alias: String): Boolean`, `find(alias: String): SecretKey?`,
    `getOrCreate(alias: String): SecretKey`, `delete(alias: String)`
  - `sealed interface IntegrationCredentials` with `val service: IntegrationService` and
    `val baseUrl: IntegrationBaseUrl`; **one member in this task**,
    `data class Lidarr(baseUrl: IntegrationBaseUrl, apiKey: String)`
  - `@Qualifier annotation class IntegrationPreferences`
  - `class IntegrationCredentialStore @Inject constructor(dataStore)` with
    `val configured: Flow<Map<IntegrationService, IntegrationCredentials>>`,
    `suspend fun load(service: IntegrationService): IntegrationCredentials?`,
    `suspend fun save(credentials: IntegrationCredentials)`,
    `suspend fun clear(service: IntegrationService)`
  - `IntegrationCredentialStore.Companion.keyAlias(service: IntegrationService): String` and
    `keyExists(service: IntegrationService): Boolean` — the second is for tests, and exists for the
    same reason `CredentialStore.keyExists()` does

### Why one Keystore alias **per service**, not one for both

`clear(LIDARR)` must not be able to make Bindery unreadable. With a shared key, "forget Lidarr"
either leaves a key behind that still opens Bindery's blob (so `clear` does not mean what a user
thinks it means for the last service removed) or destroys it (so forgetting one service silently
signs the user out of the other). Neither is acceptable, and neither failure is visible in a test
that only ever configures one service.

One alias per service makes the independence structural: `app.muplay.integrations.lidarr` and
`app.muplay.integrations.bindery` are separate keys with separate lifetimes. Step 5's test
configures **both**, clears **one**, and asserts three things at once — the other still loads, the
other's alias still exists, and the cleared one's alias does not. That is the severability contract
expressed in the credential layer.

### Why `KeystoreKeys` is extracted rather than copy-pasted

`CredentialStore` already contains ~30 lines of alias plumbing: create-or-fetch, exists, delete,
`KeyStore.getInstance("AndroidKeyStore").apply { load(null) }`. A second store needs exactly the
same thing. This project's build-logic layer exists precisely because "every module that puts
AssertJ on a device would otherwise rediscover the same failure" (`excludeByteBuddyFromInstrumentedTests`),
and the same argument applies one level down.

**The severability cost is real and small, and is recorded here rather than discovered later:**
deleting Plan 7 leaves `KeystoreKeys` behind with one caller. That is a 40-line refactor's worth of
residue, not a feature's worth, and `CredentialStore` is better for it either way — its key
handling is now testable in isolation, which it was not.

**What must not change:** `CredentialStore`'s public API (`save`, `load`, `clear`, `credentials`,
`keyExists`), its alias (`app.muplay.credentials`), its DataStore keys, and its behaviour on a
missing key or an unopenable blob. `CredentialStoreTest` is not edited in this task. If it needs
editing, the refactor is wrong.

### Why the secret is sealed but the base URL is not

Exactly the reasoning `CredentialStore` already documents for the Navidrome password: the URL and
the account name are not secrets, and having them readable makes a support question answerable.
The API key is a bearer credential — anything holding it can command the user's download client —
so it gets the AES-GCM seal. And because Task 1's `parse` strips query, fragment and userinfo, the
plaintext base URL **cannot** contain a secret; that is what makes storing it in the clear safe
rather than merely convenient.

- [ ] **Step 1: Write the failing `KeystoreKeys` contract, as an instrumented test**

This is device-only code: `AndroidKeyStore` does not exist on the JVM, which is precisely why
`KeystoreCipher` was built to take a `SecretKey` instead of fetching one. Add to
`core/database/src/androidTest/kotlin/app/muplay/database/CredentialStoreTest.kt`'s sibling — a new
file `core/database/src/androidTest/kotlin/app/muplay/database/KeystoreKeysTest.kt`:

```kotlin
package app.muplay.database

import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * `KeystoreKeys` against the real `AndroidKeyStore` provider, which is the only provider whose
 * behaviour matters — a software AES provider on the JVM accepts things Keystore rejects, and this
 * project has already been bitten by exactly that (see `KeystoreCipher.seal`'s doc).
 *
 * Two aliases throughout, never one. The whole reason this object exists is that two stores now
 * hold keys side by side, and an implementation that ignored its `alias` argument and used a
 * single hardcoded one would pass every single-alias test ever written. That is the
 * argument-passthrough defect this project has shipped before.
 */
class KeystoreKeysTest {

  private val first = "app.muplay.test.first"
  private val second = "app.muplay.test.second"

  @Before fun clean() = deleteBoth()
  @After fun tidy() = deleteBoth()

  private fun deleteBoth() {
    KeystoreKeys.delete(first)
    KeystoreKeys.delete(second)
  }

  @Test
  fun `find returns null for an alias that was never created, and does not create it`() {
    assertThat(KeystoreKeys.find(first)).isNull()
    // The important half: a `find` that quietly created the key would make `exists` meaningless
    // and would make CredentialStore.read()'s "no key means signed out" branch unreachable.
    assertThat(KeystoreKeys.exists(first)).isFalse()
  }

  @Test
  fun `getOrCreate creates a key that find then returns, and is stable across calls`() {
    val created = KeystoreKeys.getOrCreate(first)

    assertThat(KeystoreKeys.exists(first)).isTrue()
    // Keystore keys have no extractable material, so identity is compared through a round trip.
    val sealed = KeystoreCipher.seal(created, "hunter2")
    assertThat(KeystoreCipher.open(KeystoreKeys.getOrCreate(first), sealed)).isEqualTo("hunter2")
    assertThat(KeystoreCipher.open(checkNotNull(KeystoreKeys.find(first)), sealed)).isEqualTo("hunter2")
  }

  @Test
  fun `two aliases are two different keys`() {
    // The argument-passthrough proof. An implementation that hardcoded one alias would open this
    // blob with the wrong key and this test would fail -- which is the point.
    val sealedByFirst = KeystoreCipher.seal(KeystoreKeys.getOrCreate(first), "secret-one")
    KeystoreKeys.getOrCreate(second)

    assertThat(KeystoreCipher.open(checkNotNull(KeystoreKeys.find(first)), sealedByFirst))
      .isEqualTo("secret-one")
    assertThatThrownBy { KeystoreCipher.open(checkNotNull(KeystoreKeys.find(second)), sealedByFirst) }
      .isInstanceOf(javax.crypto.AEADBadTagException::class.java)
  }

  @Test
  fun `delete removes only the alias it was given`() {
    KeystoreKeys.getOrCreate(first)
    KeystoreKeys.getOrCreate(second)

    KeystoreKeys.delete(first)

    // Two observations, opposite directions, from one call. A `delete` that cleared everything
    // passes the first assertion and fails the second.
    assertThat(KeystoreKeys.exists(first)).isFalse()
    assertThat(KeystoreKeys.exists(second)).isTrue()
  }

  @Test
  fun `deleting an alias that does not exist is not an error`() {
    // `clear()` on a never-configured service is a normal path, not an exceptional one.
    KeystoreKeys.delete("app.muplay.test.never-created")
    assertThat(KeystoreKeys.exists("app.muplay.test.never-created")).isFalse()
  }
}
```

Add the AssertJ `assertThatThrownBy` import at the top:
`import org.assertj.core.api.Assertions.assertThatThrownBy`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests '*KeystoreKeysTest*'`
Expected: FAIL — `Unresolved reference: KeystoreKeys`. (An emulator must be running; see
`ci/prepare-emulator.sh`.)

- [ ] **Step 3: Implement `KeystoreKeys` and make `CredentialStore` delegate**

`core/database/src/main/kotlin/app/muplay/database/KeystoreKeys.kt`:

```kotlin
package app.muplay.database

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The AES-GCM keys this application holds in the Android Keystore, addressed by alias.
 *
 * Extracted from [CredentialStore] when a second store needed the same plumbing. The keys never
 * leave the Keystore — [getOrCreate] and [find] return a handle, not key material — and none of
 * them is user-authentication-bound (`setUserAuthenticationRequired` is never called), because
 * background playback and background request polling must both work from a locked screen.
 *
 * One alias per *thing being protected*, never one shared alias: [delete] must be able to mean
 * "forget this one service" without touching another, and a shared key makes that impossible to
 * express. See `IntegrationCredentialStore`'s doc for the concrete failure a shared key produces.
 */
object KeystoreKeys {

  private const val ANDROID_KEY_STORE = "AndroidKeyStore"
  private const val KEY_SIZE_BITS = 256

  /** Whether [alias] holds a key. Never creates one — see [find]. */
  fun exists(alias: String): Boolean = keyStore().containsAlias(alias)

  /**
   * The key at [alias], or `null` if there is none.
   *
   * Deliberately does not create. "No key" is a meaningful state for a caller — it means the same
   * thing as "nothing is stored", i.e. the user has to configure this again — and a `find` that
   * created on demand would turn a readable state into an unreadable blob's worth of confusion.
   */
  fun find(alias: String): SecretKey? {
    val keyStore = keyStore()
    if (!keyStore.containsAlias(alias)) return null
    return (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
  }

  /** The key at [alias], generating a new AES-256-GCM one if there is none. */
  fun getOrCreate(alias: String): SecretKey = find(alias) ?: generate(alias)

  /**
   * Destroys the key at [alias], if there is one.
   *
   * Destroying the key, rather than only deleting the ciphertext, is what makes "forget this"
   * mean it: a key left behind still opens any surviving copy of the blob — a backup, a forensic
   * image — which is not what a user means by signing out.
   */
  fun delete(alias: String) {
    val keyStore = keyStore()
    if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
  }

  private fun generate(alias: String): SecretKey {
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
    generator.init(
      KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(KEY_SIZE_BITS)
        .build(),
    )
    return generator.generateKey()
  }

  private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
}
```

Now edit `CredentialStore.kt`. **Behaviour must not change.** Replace its private `secretKey()`, its
inline `androidKeyStore()`/`getEntry` in `read`, its `clear`'s delete block, and its companion's
`keyExists`/`androidKeyStore` with delegations, and delete the now-unused imports
(`KeyGenParameterSpec`, `KeyProperties`, `KeyStore`, `KeyGenerator`, `SecretKey`):

```kotlin
  suspend fun clear() {
    dataStore.edit { it.clear() }
    KeystoreKeys.delete(KEY_ALIAS)
  }

  private fun read(preferences: Preferences): SubsonicCredentials? {
    val baseUrl = preferences[BASE_URL] ?: return null
    val username = preferences[USERNAME] ?: return null
    val sealed = preferences[SEALED_PASSWORD] ?: return null
    val key = KeystoreKeys.find(KEY_ALIAS) ?: return null
    // A blob that will not open is indistinguishable, to a caller, from nothing being stored:
    // both mean "you have to log in again". Surfacing a GeneralSecurityException from a Flow
    // collected by the UI would crash the screen instead.
    return runCatching { KeystoreCipher.open(key, Base64.getDecoder().decode(sealed)) }
      .map { password -> SubsonicCredentials(baseUrl, username, password) }
      .getOrNull()
  }
```

and in `save`, `KeystoreCipher.seal(secretKey(), ...)` becomes
`KeystoreCipher.seal(KeystoreKeys.getOrCreate(KEY_ALIAS), ...)`. In the companion:

```kotlin
    /** Whether the Keystore alias exists. Used by `CredentialStoreTest` to prove `clear()` means it. */
    fun keyExists(): Boolean = KeystoreKeys.exists(KEY_ALIAS)
```

- [ ] **Step 4: Run both suites to verify nothing regressed**

Run: `./gradlew :core:database:connectedDebugAndroidTest`
Expected: PASS — `KeystoreKeysTest` green **and** `CredentialStoreTest` green **with no edits to
it**. If `CredentialStoreTest` needed a change, the refactor changed behaviour; revert and redo it.

- [ ] **Step 5: Write the failing integration-credential-store test**

`integrations/core/src/androidTest/kotlin/app/muplay/integrations/IntegrationCredentialStoreTest.kt`:

```kotlin
package app.muplay.integrations

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The credential store, against the real Android Keystore and a real DataStore file.
 *
 * **Every test here exercises more than one configuration state.** The plan's severability
 * contract names "a service-not-configured path that every test configures around" as the single
 * most likely defect in this plan, and this is the layer where that path is born: `load` returning
 * `null`, `configured` returning an empty map, and `clear` on one service leaving the other alone
 * are the three behaviours the whole feature's optionality rests on.
 */
class IntegrationCredentialStoreTest {

  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var store: IntegrationCredentialStore

  private fun url(raw: String): IntegrationBaseUrl =
    (IntegrationBaseUrl.parse(raw, CleartextPolicy.Allowed) as BaseUrlResult.Valid).url

  private val lidarr = IntegrationCredentials.Lidarr(
    baseUrl = url("https://lidarr.example.com"),
    apiKey = "0123456789abcdef0123456789abcdef",
  )

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    file = File(context.filesDir, "integration-credentials-test.preferences_pb")
    file.delete()
    dataStore = PreferenceDataStoreFactory.create { file }
    store = IntegrationCredentialStore(dataStore)
    IntegrationService.entries.forEach { KeystoreKeysAccess.delete(it) }
  }

  @After
  fun tearDown() {
    file.delete()
    IntegrationService.entries.forEach { KeystoreKeysAccess.delete(it) }
  }

  /** A tiny device-side helper so the test can assert on aliases without duplicating them. */
  private object KeystoreKeysAccess {
    fun delete(service: IntegrationService) =
      app.muplay.database.KeystoreKeys.delete(IntegrationCredentialStore.keyAlias(service))
  }

  @Test
  fun `nothing is configured before anything is saved`() = runTest {
    // The path every other test would configure around. `configured` must be *observed empty*,
    // and `load` must be observed null for each service individually -- an empty map alone would
    // be satisfied by a `load` that threw.
    assertThat(store.configured.first()).isEmpty()
    assertThat(store.load(IntegrationService.LIDARR)).isNull()
    assertThat(store.load(IntegrationService.BINDERY)).isNull()
  }

  @Test
  fun `a saved credential round-trips every field`() = runTest {
    store.save(lidarr)

    val loaded = store.load(IntegrationService.LIDARR)

    // Field by field, not `isEqualTo(lidarr)` alone: `isEqualTo` on a data class is one
    // assertion whose failure message names the whole object, and the point of this project's
    // field rule is that each field is individually unable to be a constant.
    assertThat(loaded).isInstanceOf(IntegrationCredentials.Lidarr::class.java)
    loaded as IntegrationCredentials.Lidarr
    assertThat(loaded.baseUrl.value).isEqualTo("https://lidarr.example.com/")
    assertThat(loaded.apiKey).isEqualTo("0123456789abcdef0123456789abcdef")
    assertThat(loaded.service).isEqualTo(IntegrationService.LIDARR)
  }

  @Test
  fun `a second save with different values replaces the first`() = runTest {
    // The two-observations rule for a persisted value: a store that wrote the first value and
    // then ignored later writes passes the round-trip test above.
    store.save(lidarr)
    store.save(lidarr.copy(baseUrl = url("https://other.example.com"), apiKey = "ffffffff"))

    val loaded = store.load(IntegrationService.LIDARR) as IntegrationCredentials.Lidarr
    assertThat(loaded.baseUrl.value).isEqualTo("https://other.example.com/")
    assertThat(loaded.apiKey).isEqualTo("ffffffff")
  }

  @Test
  fun `the api key is not readable from the preferences file`() = runTest {
    store.save(lidarr)

    // The seal, proven at the bytes rather than argued. DataStore's file is a protobuf, so the
    // key would appear as a plain UTF-8 substring if it were stored unsealed.
    val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
    assertThat(bytes).doesNotContain("0123456789abcdef0123456789abcdef")
    // ...and the base URL *is* readable, which is the deliberate half: it is not a secret, and
    // `IntegrationBaseUrl.parse` guarantees it carries none.
    assertThat(bytes).contains("https://lidarr.example.com/")
  }

  @Test
  fun `toString never contains the api key`() {
    // The crash-report path. `SubsonicCredentials` does the same thing for the same reason.
    assertThat(lidarr.toString()).doesNotContain("0123456789abcdef0123456789abcdef")
    assertThat(lidarr.toString()).contains("lidarr.example.com")
  }

  @Test
  fun `configured reports exactly the services that are configured`() = runTest {
    // Three of the four combinations, in order, on one store. The fourth (both) is the test
    // below, which needs Task 7's Bindery member to exist -- see its comment.
    assertThat(store.configured.first().keys).isEmpty()

    store.save(lidarr)
    assertThat(store.configured.first().keys).containsExactly(IntegrationService.LIDARR)

    store.clear(IntegrationService.LIDARR)
    assertThat(store.configured.first().keys).isEmpty()
  }

  @Test
  fun `clearing one service destroys only its key and only its entries`() = runTest {
    store.save(lidarr)
    assertThat(IntegrationCredentialStore.keyExists(IntegrationService.LIDARR)).isTrue()

    store.clear(IntegrationService.LIDARR)

    assertThat(store.load(IntegrationService.LIDARR)).isNull()
    // Destroying the key, not just the ciphertext: a key left behind still opens a backup copy.
    assertThat(IntegrationCredentialStore.keyExists(IntegrationService.LIDARR)).isFalse()
  }

  @Test
  fun `clearing a service that was never configured is a no-op, not a failure`() = runTest {
    store.clear(IntegrationService.BINDERY)
    assertThat(store.configured.first()).isEmpty()
  }

  @Test
  fun `the two services use two different keystore aliases`() = runTest {
    // The independence property, at the alias level. Asserted as an exact mapped list rather
    // than "they are different", so an implementation that returned one constant alias fails and
    // so does one that returned aliases in the wrong order.
    assertThat(IntegrationService.entries.map { IntegrationCredentialStore.keyAlias(it) })
      .containsExactly("app.muplay.integrations.lidarr", "app.muplay.integrations.bindery")
  }

  @Test
  fun `a credential whose key was destroyed out from under it reads as not configured`() = runTest {
    store.save(lidarr)
    app.muplay.database.KeystoreKeys.delete(IntegrationCredentialStore.keyAlias(IntegrationService.LIDARR))

    // Not an exception through a Flow the UI collects. "You have to configure this again" is the
    // only thing a caller can do about it -- exactly what `CredentialStore.read` already decided
    // for the Navidrome password.
    assertThat(store.load(IntegrationService.LIDARR)).isNull()
    assertThat(store.configured.first()).isEmpty()
  }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :integrations:core:connectedDebugAndroidTest --tests '*IntegrationCredentialStoreTest*'`
Expected: FAIL — `Unresolved reference: IntegrationCredentials`.

- [ ] **Step 7: Add the module's new dependencies**

`integrations/core/build.gradle.kts` — the `dependencies` block becomes:

```kotlin
dependencies {
  // `api`, not `implementation`: `IntegrationCredentialStore`'s public signatures return
  // `IntegrationCredentials`, whose members hold `IntegrationBaseUrl` -- all declared in this
  // module -- but `KeystoreKeys` and `KeystoreCipher` come from `:core:database` and appear in no
  // public signature here, so that one stays `implementation`.
  implementation(project(":core:database"))

  implementation(libs.okhttp)
  implementation(libs.coroutines.core)
  implementation(libs.datastore.preferences)

  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.coroutines.test)
  // Byte Buddy is stripped from every androidTest configuration project-wide by
  // `excludeByteBuddyFromInstrumentedTests` in build-logic; nothing is needed here for it.
  androidTestImplementation(libs.assertj)
}
```

- [ ] **Step 8: Implement `IntegrationCredentials`**

`integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationCredentials.kt`:

```kotlin
package app.muplay.integrations

/**
 * What MuPlay needs in order to talk to one configured integration.
 *
 * A **sealed interface with one member per service**, not a single data class with a
 * lowest-common-denominator `secret: String`. The two services do not authenticate the same way,
 * and a shape that pretends they do would force one of them to store an empty string in a field it
 * has no use for — the kind of "almost right" model that produces a runtime check where an
 * exhaustive `when` belongs.
 *
 * **Only [Lidarr] exists as of this task.** Task 7 adds the Bindery member once its
 * authentication mechanism is established against a real instance rather than guessed. That is
 * safe to defer precisely because this is sealed: adding a member makes every `when` over this
 * type a compile error until it is handled, so nothing can silently forget the new service.
 *
 * [baseUrl] is an [IntegrationBaseUrl] rather than a `String`, which is what makes it impossible
 * to construct a credential around a URL that has not been through the cleartext policy and the
 * secret-stripping in [IntegrationBaseUrl.parse].
 */
sealed interface IntegrationCredentials {

  val service: IntegrationService
  val baseUrl: IntegrationBaseUrl

  /**
   * Lidarr authenticates every API request with a single API key, sent as an `X-Api-Key` **header**
   * — never as a query parameter, even though Lidarr accepts one there. See
   * `:integrations:lidarr`'s `LidarrAuthInterceptor` for that decision and the assertion that
   * pins it.
   */
  data class Lidarr(
    override val baseUrl: IntegrationBaseUrl,
    val apiKey: String,
  ) : IntegrationCredentials {

    override val service: IntegrationService get() = IntegrationService.LIDARR

    /**
     * Redacts the key. The same control `SubsonicCredentials` carries, for the same reason: this
     * object ends up in a log line or a crash report through any `Throwable` message that
     * interpolates it, and nobody writes that interpolation deliberately.
     */
    override fun toString(): String = "Lidarr(baseUrl=$baseUrl, apiKey=<redacted>)"
  }
}
```

- [ ] **Step 9: Implement the store and its Hilt module**

`integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationCredentialStore.kt`:

```kotlin
package app.muplay.integrations

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.muplay.database.KeystoreCipher
import app.muplay.database.KeystoreKeys
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Where the optional integrations' credentials live.
 *
 * The **same mechanism** as [app.muplay.database.CredentialStore], deliberately and not
 * coincidentally: an AES-GCM key held in the Android Keystore, ciphertext in DataStore, the base
 * URL in the clear because it is not a secret. A Lidarr API key can command a user's download
 * client and sits beside their Navidrome password on the same device; it does not get a weaker
 * store because the feature it serves is optional.
 *
 * **One Keystore alias per service.** With a shared key, `clear(LIDARR)` would either leave a key
 * behind that still opens Bindery's blob — so "forget this" would not mean it for the last service
 * removed — or destroy it, silently signing the user out of a service they did not ask to forget.
 * Neither failure is visible to a test that configures one service, which is why
 * `IntegrationCredentialStoreTest` never configures only one.
 *
 * Keys are not user-authentication-bound, for the same reason [app.muplay.database.CredentialStore]'s
 * is not: request-status polling runs in the background, from a locked screen.
 */
@Singleton
class IntegrationCredentialStore @Inject constructor(
  @IntegrationPreferences private val dataStore: DataStore<Preferences>,
) {

  /**
   * Every configured service and its credentials, keyed by service.
   *
   * A service is *configured* only when its base URL, its secret and its Keystore key are all
   * present and the blob opens. Anything less reads as not configured — see
   * [app.muplay.database.CredentialStore]'s own `read` for why an unopenable blob and a missing
   * one are the same fact to a caller.
   */
  val configured: Flow<Map<IntegrationService, IntegrationCredentials>> =
    dataStore.data.map { preferences ->
      IntegrationService.entries
        .mapNotNull { service -> read(preferences, service)?.let { service to it } }
        .toMap()
    }

  suspend fun load(service: IntegrationService): IntegrationCredentials? = configured.first()[service]

  suspend fun save(credentials: IntegrationCredentials) {
    val service = credentials.service
    val sealed = KeystoreCipher.seal(KeystoreKeys.getOrCreate(keyAlias(service)), secretOf(credentials))
    dataStore.edit { preferences ->
      preferences[baseUrlKey(service)] = credentials.baseUrl.value
      preferences[sealedSecretKey(service)] = Base64.getEncoder().encodeToString(sealed)
    }
  }

  /**
   * Forgets [service] entirely: its DataStore entries **and** its Keystore key.
   *
   * Only [service]'s. The other service's entries and key are untouched, which is the whole point
   * of the per-service alias.
   */
  suspend fun clear(service: IntegrationService) {
    dataStore.edit { preferences ->
      preferences.remove(baseUrlKey(service))
      preferences.remove(sealedSecretKey(service))
    }
    KeystoreKeys.delete(keyAlias(service))
  }

  private fun read(preferences: Preferences, service: IntegrationService): IntegrationCredentials? {
    val rawUrl = preferences[baseUrlKey(service)] ?: return null
    val sealed = preferences[sealedSecretKey(service)] ?: return null
    val key = KeystoreKeys.find(keyAlias(service)) ?: return null
    val secret =
      runCatching { KeystoreCipher.open(key, Base64.getDecoder().decode(sealed)) }.getOrNull()
        ?: return null
    // Re-parsed rather than trusted. The stored string was produced by `parse` under whatever
    // policy was in force when it was written, and a debug-built profile restored onto a release
    // build would otherwise smuggle a cleartext URL past the policy. `Allowed` is deliberately
    // NOT used here.
    val url = (IntegrationBaseUrl.parse(rawUrl, CleartextPolicy.Forbidden) as? BaseUrlResult.Valid)
      ?.url ?: return null
    return when (service) {
      IntegrationService.LIDARR -> IntegrationCredentials.Lidarr(url, secret)
      // Task 7 replaces this with the real Bindery member. Until then a Bindery entry cannot be
      // written (there is no member to write) and therefore cannot be read.
      IntegrationService.BINDERY -> null
    }
  }

  private fun secretOf(credentials: IntegrationCredentials): String = when (credentials) {
    is IntegrationCredentials.Lidarr -> credentials.apiKey
  }

  companion object {

    /** The Keystore alias holding [service]'s secret. One per service — see the class doc. */
    fun keyAlias(service: IntegrationService): String = when (service) {
      IntegrationService.LIDARR -> "app.muplay.integrations.lidarr"
      IntegrationService.BINDERY -> "app.muplay.integrations.bindery"
    }

    /** Whether [service]'s Keystore key exists. For tests, exactly like `CredentialStore.keyExists`. */
    fun keyExists(service: IntegrationService): Boolean = KeystoreKeys.exists(keyAlias(service))

    private fun baseUrlKey(service: IntegrationService) =
      stringPreferencesKey("${service.name.lowercase()}_base_url")

    private fun sealedSecretKey(service: IntegrationService) =
      stringPreferencesKey("${service.name.lowercase()}_sealed_secret")
  }
}
```

> **A ruling worth reading twice.** `read` re-parses the stored URL under
> `CleartextPolicy.Forbidden`, not under the injected policy. That means a URL saved by a debug
> build is silently dropped by a release build rather than used. This is deliberate: the stored
> string is the one place the policy could be bypassed (an app-data restore, a rooted device, a
> `adb run-as` write), and dropping is the safe direction. It also means a developer switching
> between variants on one device will find a debug-configured cleartext service missing in
> release — which is the correct, and honest, observable consequence of the policy.

`integrations/core/src/main/kotlin/app/muplay/integrations/di/IntegrationsDataModule.kt`:

```kotlin
package app.muplay.integrations.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.muplay.integrations.IntegrationPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * The integrations' own object graph.
 *
 * A **separate DataStore file** from `:core:database`'s `credentials.preferences_pb`, and a
 * qualified binding so the two `DataStore<Preferences>` instances cannot be confused. Two reasons,
 * both concrete: DataStore throws `IllegalStateException: There are multiple DataStores active for
 * the same file` if two instances share a path, and severability means deleting this plan should
 * delete this file rather than leave orphan keys inside the one holding the Navidrome password.
 */
@Module
@InstallIn(SingletonComponent::class)
object IntegrationsDataModule {

  @Provides
  @Singleton
  @IntegrationPreferences
  fun provideIntegrationDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create {
      File(context.filesDir, "integrations.preferences_pb")
    }
}
```

And the qualifier, in `IntegrationCredentialStore.kt`'s package (put it at the bottom of
`IntegrationCredentials.kt` or its own file — one file either way):

```kotlin
package app.muplay.integrations

import javax.inject.Qualifier

/**
 * Distinguishes the integrations' DataStore from `:core:database`'s unqualified one. Without it
 * Hilt sees two bindings for `DataStore<Preferences>` and fails the build — which is the correct
 * behaviour, and this qualifier is the answer to it rather than a workaround.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IntegrationPreferences
```

- [ ] **Step 10: Run the instrumented suite to verify it passes**

Run: `./gradlew :integrations:core:connectedDebugAndroidTest`
Expected: PASS, 10/10.

- [ ] **Step 11: Add a mutation probe for the alias passthrough**

`ci/mutation-probes.sh` — add a `STORE` constant and one probe. Read the file's header first; it is
explicit about what a green run does and does not mean.

```python
STORE = "integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationCredentialStore.kt"
```

```python
    # ---- Plan 7: the per-service alias, which is the whole independence property ---------------
    # A `keyAlias` that ignored its argument would make `clear(LIDARR)` destroy Bindery's key too,
    # and no test that configures a single service could see it.
    ("integrations/keyAlias-service", STORE,
     'IntegrationService.BINDERY -> "app.muplay.integrations.bindery"',
     'IntegrationService.BINDERY -> "app.muplay.integrations.lidarr"',
     "the two services use two different keystore aliases", 1),
```

> **Note the limit honestly:** `ci/mutation-probes.sh` runs the **JVM** suites only and needs no
> container. `IntegrationCredentialStoreTest` is an *instrumented* test, so this probe cannot be
> verified by that script as written. Do **one** of two things and say which in the task report:
> either move the pure-`keyAlias` assertion into a JVM test in `:integrations:core/src/test` (it
> touches no Android API — `keyAlias` is a `when` over an enum) and probe that, **or** add the
> probe under a clearly-marked instrumented section and extend the script to run it. The first is
> smaller and is the recommended one; a probe that silently never runs is precisely the defect this
> whole regime exists to prevent.

- [ ] **Step 12: Commit**

```bash
git add core/database integrations/core build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(integrations): credentials sealed the same way, with one keystore key per service

KeystoreKeys is extracted from CredentialStore so a second store reuses the mechanism
rather than copying it; CredentialStore's public API, alias, DataStore keys and behaviour
are unchanged and CredentialStoreTest is untouched.

One Keystore alias per integration, because a shared key makes clear(LIDARR) either leave a
key that still opens Bindery's blob or destroy it -- and neither failure is visible to a
test that configures a single service. Every test here exercises more than one configuration
state, including the not-configured one the rest of this plan depends on."
```

---

## Task 3: The request store — a separate one-table database, and a repository that cannot drop its argument

**Files:**
- Create: `integrations/core/src/main/kotlin/app/muplay/integrations/RequestStatus.kt`
- Create: `integrations/core/src/main/kotlin/app/muplay/integrations/MediaRequest.kt`
- Create: `integrations/core/src/main/kotlin/app/muplay/integrations/db/MediaRequestEntity.kt`
- Create: `integrations/core/src/main/kotlin/app/muplay/integrations/db/MediaRequestDao.kt`
- Create: `integrations/core/src/main/kotlin/app/muplay/integrations/db/IntegrationRequestsDatabase.kt`
- Create: `integrations/core/src/main/kotlin/app/muplay/integrations/MediaRequestRepository.kt`
- Create: `integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationsClock.kt`
- Create: `integrations/core/src/test/kotlin/app/muplay/integrations/RequestStatusTest.kt`
- Create: `integrations/core/src/androidTest/kotlin/app/muplay/integrations/MediaRequestRepositoryTest.kt`
- Modify: `integrations/core/build.gradle.kts` — `muplay.android.room`, Turbine
- Modify: `integrations/core/src/main/kotlin/app/muplay/integrations/di/IntegrationsDataModule.kt`
- Modify: `build.gradle.kts` — floors
- Modify: `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `IntegrationService` (Task 1)
- Produces:
  - `sealed interface RequestStatus` with `data object Requested`,
    `data class Downloading(val percentComplete: Int?)`, `data object Imported`,
    `data class Arrived(val albumId: String)`, `data class Failed(val reason: String)`
  - `RequestStatus.storedName: String` and `RequestStatus.storedDetail: String?`; and
    `RequestStatus.Companion.fromStored(name: String, detail: String?): RequestStatus`
  - `data class MediaRequest(id, service, externalId, title, subtitle, remoteId, status, requestedAtEpochMs, updatedAtEpochMs)`
  - `MediaRequest.Companion.idFor(service: IntegrationService, externalId: String): String`
  - `@Entity(tableName = "media_requests") data class MediaRequestEntity(...)`
  - `interface MediaRequestDao` with `observeAll()`, `observeByService(service: String)`,
    `find(id: String)`, `upsert(entity)`, `updateStatus(id, name, detail, updatedAtEpochMs)`,
    `delete(id: String)`
  - `abstract class IntegrationRequestsDatabase : RoomDatabase` with `requestDao()`, version **1**
  - `class MediaRequestRepository @Inject constructor(dao, clock)` with
    `fun requests(): Flow<List<MediaRequest>>`,
    `fun requests(service: IntegrationService): Flow<List<MediaRequest>>`,
    `suspend fun record(service, externalId, title, subtitle, remoteId): MediaRequest`,
    `suspend fun setStatus(id: String, status: RequestStatus)`,
    `suspend fun forget(id: String)`
  - `@Qualifier annotation class IntegrationsClock`

### Why a **separate** Room database, not a table in `MuPlayDatabase`

Two reasons, and the second is the one that would otherwise cost real time.

**Severability.** Deleting this plan should delete its storage. A table in `MuPlayDatabase` would
survive as an orphan, with a migration in the history that no code explains.

**Schema-version collision.** `MuPlayDatabase` is at version 4 as of Plan 2, and Plans 3, 4, 5 and
6 are written and executed independently of this one — the roadmap says Plans 5–7 can be
*reordered*. Any version number this plan claimed for `MuPlayDatabase` would be a guess about what
the other plans did to it, and a wrong guess is a migration conflict discovered at execution time
by whoever went second. A database of this plan's own starts at version 1 and stays there no matter
what order the plans land in. That is not an aesthetic preference; it is the mechanical reason
this plan is genuinely reorderable.

The cost is a second SQLite file and a second `RoomDatabase` class. Room supports this with no
ceremony; the convention plugin exports the schema to `integrations/core/schemas/` automatically.

### Why the `Clock` is qualified

Plan 3 provides an **unqualified** `@Singleton java.time.Clock` from `:core:media`'s `MediaModule`.
Two unqualified bindings of the same type is a Hilt build failure, and this plan must be executable
whether Plan 3 has landed or not — the roadmap says Plan 7 depends on Plan **2**. `@IntegrationsClock`
costs one annotation and removes the ordering dependency completely. `java.time.Clock`, not
`kotlinx-datetime`: it is in the JDK, it is available from `minSdk 26` with no desugaring, the
stored column is epoch millis anyway, and adding a datetime library would break this plan's
no-new-dependency rule for no gain — the same call Plans 3 and 4 already made.

### The defect this task exists to prevent

`requests(service)` forwards to `dao.observeByService(service.name)`. **A repository method that
forwards to a DAO can discard its argument and hardcode a value with every test still green** —
this project shipped exactly that defect and it is round six of the same class. The proof is not
"the DAO was called": it is **two rows of different services in one database, queried twice, each
query returning exactly the other's complement.** Step 3's
`requests filtered by service returns that service's rows and only those` is that proof, and the
mutation probe in Step 9 is what keeps it honest.

- [ ] **Step 1: Write the failing status round-trip test (JVM)**

`integrations/core/src/test/kotlin/app/muplay/integrations/RequestStatusTest.kt`:

```kotlin
package app.muplay.integrations

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The persisted form of [RequestStatus], round-tripped.
 *
 * Every member is exercised, and the members that carry data are exercised at **two** values.
 * A `fromStored` that returned a constant `Requested` would satisfy any test that only checked
 * one member, and a status column that silently collapsed to one value is the failure mode where
 * a user's finished download shows as still queued forever.
 */
class RequestStatusTest {

  private fun roundTrip(status: RequestStatus): RequestStatus =
    RequestStatus.fromStored(status.storedName, status.storedDetail)

  @Test
  fun `every member round-trips to an equal value`() {
    val all = listOf(
      RequestStatus.Requested,
      RequestStatus.Downloading(percentComplete = null),
      RequestStatus.Downloading(percentComplete = 0),
      RequestStatus.Downloading(percentComplete = 73),
      RequestStatus.Imported,
      RequestStatus.Arrived(albumId = "al-1"),
      RequestStatus.Arrived(albumId = "al-2"),
      RequestStatus.Failed(reason = "no results"),
      RequestStatus.Failed(reason = "download client rejected the release"),
    )

    // The exact mapped list, not `allMatch { roundTrip(it) == it }`. `allMatch` over an empty
    // list is vacuously true, and this project has been bitten by that shape specifically.
    assertThat(all.map(::roundTrip)).containsExactlyElementsOf(all)
  }

  @Test
  fun `the stored names are the exact strings the database holds`() {
    // Pinned as literals, because these strings are on disk. Renaming a member must not silently
    // orphan every row a user already has.
    assertThat(
      listOf(
        RequestStatus.Requested,
        RequestStatus.Downloading(null),
        RequestStatus.Imported,
        RequestStatus.Arrived("al-1"),
        RequestStatus.Failed("x"),
      ).map { it.storedName },
    ).containsExactly("REQUESTED", "DOWNLOADING", "IMPORTED", "ARRIVED", "FAILED")
  }

  @Test
  fun `the detail column carries the member's data and nothing else`() {
    // Two observations per data-carrying member: a `storedDetail` hardcoded to any single string
    // fails at least one of these.
    assertThat(RequestStatus.Requested.storedDetail).isNull()
    assertThat(RequestStatus.Imported.storedDetail).isNull()
    assertThat(RequestStatus.Downloading(null).storedDetail).isNull()
    assertThat(RequestStatus.Downloading(7).storedDetail).isEqualTo("7")
    assertThat(RequestStatus.Downloading(99).storedDetail).isEqualTo("99")
    assertThat(RequestStatus.Arrived("al-1").storedDetail).isEqualTo("al-1")
    assertThat(RequestStatus.Arrived("al-2").storedDetail).isEqualTo("al-2")
    assertThat(RequestStatus.Failed("a").storedDetail).isEqualTo("a")
    assertThat(RequestStatus.Failed("b").storedDetail).isEqualTo("b")
  }

  @Test
  fun `a downloading percentage that is not a number reads as unknown rather than crashing`() {
    // Defensive, and reachable: the column is a TEXT column and a future writer could put
    // anything in it. `toIntOrNull` is the branch; this is the assertion that makes it real.
    assertThat(RequestStatus.fromStored("DOWNLOADING", "not-a-number"))
      .isEqualTo(RequestStatus.Downloading(percentComplete = null))
  }

  @Test
  fun `an unrecognised stored status reads as a failure that names itself`() {
    // This row can only exist through database corruption or a downgrade. Reading it as
    // `Requested` would tell the user their request is still in progress forever; reading it as a
    // named failure tells them, and tells whoever reads the bug report, exactly what happened.
    val status = RequestStatus.fromStored("SOMETHING_ELSE", null)

    assertThat(status).isInstanceOf(RequestStatus.Failed::class.java)
    assertThat((status as RequestStatus.Failed).reason).contains("SOMETHING_ELSE")
  }

  @Test
  fun `an ARRIVED row with no album id is a failure, not an Arrived with an empty id`() {
    // `Arrived("")` would render a "Play it" button that navigates nowhere. A missing detail on a
    // status that requires one is corruption, and it says so.
    assertThat(RequestStatus.fromStored("ARRIVED", null))
      .isInstanceOf(RequestStatus.Failed::class.java)
  }

  @Test
  fun `the request id is derived from the service and the external id`() {
    // Two observations on each half of the key. A hardcoded id would make every request in the
    // database collide onto one row -- and a hardcoded *service* half would make a Lidarr and a
    // Bindery request for the same external id collide, which is the subtler one.
    assertThat(MediaRequest.idFor(IntegrationService.LIDARR, "mbid-1")).isEqualTo("LIDARR:mbid-1")
    assertThat(MediaRequest.idFor(IntegrationService.LIDARR, "mbid-2")).isEqualTo("LIDARR:mbid-2")
    assertThat(MediaRequest.idFor(IntegrationService.BINDERY, "mbid-1")).isEqualTo("BINDERY:mbid-1")
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :integrations:core:testDebugUnitTest --tests '*RequestStatusTest*'`
Expected: FAIL — `Unresolved reference: RequestStatus`.

- [ ] **Step 3: Write the failing repository test (instrumented, real Room)**

Real in-memory Room and real SQL — rung 2 of the spec's test hierarchy, not a fake DAO.

`integrations/core/src/androidTest/kotlin/app/muplay/integrations/MediaRequestRepositoryTest.kt`:

```kotlin
package app.muplay.integrations

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.muplay.integrations.db.IntegrationRequestsDatabase
import app.muplay.integrations.db.MediaRequestEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The request store, against real Room and real SQL.
 *
 * The two things being proven here are the ones a fake DAO could not prove: that the **order** the
 * repository promises is produced by the query rather than by insertion luck, and that
 * `requests(service)` **passes its argument through** instead of returning everything or returning
 * one hardcoded service's rows.
 */
class MediaRequestRepositoryTest {

  private lateinit var database: IntegrationRequestsDatabase
  private lateinit var repository: MediaRequestRepository

  /** A clock the test moves by hand, so `requestedAt` is a value and never a race. */
  private class SteppingClock(private var millis: Long) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId) = this
    override fun instant(): Instant = Instant.ofEpochMilli(millis)
    fun advanceTo(newMillis: Long) { millis = newMillis }
  }

  private val clock = SteppingClock(1_000L)

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database = Room.inMemoryDatabaseBuilder(context, IntegrationRequestsDatabase::class.java).build()
    repository = MediaRequestRepository(database.requestDao(), clock)
  }

  @After
  fun tearDown() = database.close()

  @Test
  fun `nothing is stored before anything is recorded`() = runTest {
    // The empty state, observed. It is also what the UI renders when a service is configured but
    // has never been asked for anything, and it must not be reachable only by accident.
    assertThat(repository.requests().first()).isEmpty()
    assertThat(repository.requests(IntegrationService.LIDARR).first()).isEmpty()
  }

  @Test
  fun `record writes every field and returns what it wrote`() = runTest {
    clock.advanceTo(1_700_000_000_000L)

    val returned = repository.record(
      service = IntegrationService.LIDARR,
      externalId = "mbid-abc",
      title = "Kind of Blue",
      subtitle = "Miles Davis",
      remoteId = "42",
    )

    val stored = repository.requests().first().single()

    // Field by field on both the return value and the stored row, so neither can be a constant.
    assertThat(returned.id).isEqualTo("LIDARR:mbid-abc")
    assertThat(returned.service).isEqualTo(IntegrationService.LIDARR)
    assertThat(returned.externalId).isEqualTo("mbid-abc")
    assertThat(returned.title).isEqualTo("Kind of Blue")
    assertThat(returned.subtitle).isEqualTo("Miles Davis")
    assertThat(returned.remoteId).isEqualTo("42")
    assertThat(returned.status).isEqualTo(RequestStatus.Requested)
    assertThat(returned.requestedAtEpochMs).isEqualTo(1_700_000_000_000L)
    assertThat(returned.updatedAtEpochMs).isEqualTo(1_700_000_000_000L)
    assertThat(stored).isEqualTo(returned)
  }

  @Test
  fun `record writes the values it is given, not a fixed row`() = runTest {
    // The second observation of every argument. Without this, a `record` that hardcoded its
    // title, subtitle and external id passes the test above.
    clock.advanceTo(2_000L)
    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "Artist A", remoteId = "1")
    clock.advanceTo(3_000L)
    repository.record(IntegrationService.LIDARR, "mbid-2", "B", "Artist B", remoteId = null)

    val rows = repository.requests().first()

    assertThat(rows.map { it.externalId }).containsExactly("mbid-2", "mbid-1")
    assertThat(rows.map { it.title }).containsExactly("B", "A")
    assertThat(rows.map { it.subtitle }).containsExactly("Artist B", "Artist A")
    assertThat(rows.map { it.remoteId }).containsExactly(null, "1")
    assertThat(rows.map { it.requestedAtEpochMs }).containsExactly(3_000L, 2_000L)
  }

  /**
   * **Order is a property.** Newest first, and proven by inserting in an order that the query has
   * to undo: without an `ORDER BY`, SQLite returns rows in rowid order, which for these three
   * inserts is exactly the *opposite* of what is asserted.
   */
  @Test
  fun `requests come back newest first regardless of insertion order`() = runTest {
    clock.advanceTo(1_000L); repository.record(IntegrationService.LIDARR, "a", "A", "x", null)
    clock.advanceTo(3_000L); repository.record(IntegrationService.LIDARR, "b", "B", "x", null)
    clock.advanceTo(2_000L); repository.record(IntegrationService.LIDARR, "c", "C", "x", null)

    assertThat(repository.requests().first().map { it.externalId }).containsExactly("b", "c", "a")
  }

  /**
   * The argument-passthrough proof. Two services, two queries, each returning exactly the other's
   * complement — so a `requests(service)` that ignored its argument fails whichever way it was
   * hardcoded, and one that returned everything fails both.
   */
  @Test
  fun `requests filtered by service returns that service's rows and only those`() = runTest {
    clock.advanceTo(1_000L)
    repository.record(IntegrationService.LIDARR, "mbid-1", "An album", "An artist", null)
    clock.advanceTo(2_000L)
    repository.record(IntegrationService.BINDERY, "book-1", "A book", "An author", null)

    assertThat(repository.requests(IntegrationService.LIDARR).first().map { it.externalId })
      .containsExactly("mbid-1")
    assertThat(repository.requests(IntegrationService.BINDERY).first().map { it.externalId })
      .containsExactly("book-1")
    assertThat(repository.requests().first().map { it.externalId })
      .containsExactly("book-1", "mbid-1")
  }

  @Test
  fun `the same external id under two services is two rows, not one`() = runTest {
    // The subtler half of the composite key. Two services can legitimately use the same
    // identifier space, and a key of `externalId` alone would silently overwrite.
    repository.record(IntegrationService.LIDARR, "shared", "Album", "Artist", null)
    repository.record(IntegrationService.BINDERY, "shared", "Book", "Author", null)

    assertThat(repository.requests().first().map { it.id })
      .containsExactlyInAnyOrder("LIDARR:shared", "BINDERY:shared")
  }

  @Test
  fun `re-requesting the same thing updates the row rather than duplicating it`() = runTest {
    clock.advanceTo(1_000L)
    repository.record(IntegrationService.LIDARR, "mbid-1", "Old title", "Artist", remoteId = null)
    clock.advanceTo(5_000L)
    repository.record(IntegrationService.LIDARR, "mbid-1", "New title", "Artist", remoteId = "42")

    val rows = repository.requests().first()

    assertThat(rows).hasSize(1)
    assertThat(rows.single().title).isEqualTo("New title")
    assertThat(rows.single().remoteId).isEqualTo("42")
    // The original request time survives; only `updatedAt` moves. A user's "requested on" date
    // must not jump every time a poll refreshes the row.
    assertThat(rows.single().requestedAtEpochMs).isEqualTo(1_000L)
    assertThat(rows.single().updatedAtEpochMs).isEqualTo(5_000L)
  }

  @Test
  fun `setStatus changes only the row it names, and stamps updatedAt`() = runTest {
    clock.advanceTo(1_000L)
    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "x", null)
    repository.record(IntegrationService.LIDARR, "mbid-2", "B", "x", null)

    clock.advanceTo(9_000L)
    repository.setStatus("LIDARR:mbid-1", RequestStatus.Downloading(percentComplete = 40))

    val byId = repository.requests().first().associateBy { it.id }

    // Two rows, one changed, one not: a `setStatus` that ignored its id and updated everything
    // passes a single-row test.
    assertThat(byId.getValue("LIDARR:mbid-1").status)
      .isEqualTo(RequestStatus.Downloading(percentComplete = 40))
    assertThat(byId.getValue("LIDARR:mbid-1").updatedAtEpochMs).isEqualTo(9_000L)
    assertThat(byId.getValue("LIDARR:mbid-2").status).isEqualTo(RequestStatus.Requested)
    assertThat(byId.getValue("LIDARR:mbid-2").updatedAtEpochMs).isEqualTo(1_000L)
  }

  @Test
  fun `setStatus round-trips a status that carries data`() = runTest {
    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "x", null)

    repository.setStatus("LIDARR:mbid-1", RequestStatus.Arrived(albumId = "al-99"))

    assertThat(repository.requests().first().single().status)
      .isEqualTo(RequestStatus.Arrived(albumId = "al-99"))
  }

  @Test
  fun `setStatus on an id that does not exist changes nothing and does not throw`() = runTest {
    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "x", null)

    repository.setStatus("LIDARR:nope", RequestStatus.Imported)

    assertThat(repository.requests().first().single().status).isEqualTo(RequestStatus.Requested)
  }

  @Test
  fun `forget removes only the row it names`() = runTest {
    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "x", null)
    repository.record(IntegrationService.LIDARR, "mbid-2", "B", "x", null)

    repository.forget("LIDARR:mbid-1")

    assertThat(repository.requests().first().map { it.id }).containsExactly("LIDARR:mbid-2")
  }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./gradlew :integrations:core:connectedDebugAndroidTest --tests '*MediaRequestRepositoryTest*'`
Expected: FAIL — `Unresolved reference: IntegrationRequestsDatabase`.

- [ ] **Step 5: Add Room to the module**

`integrations/core/build.gradle.kts`:

```kotlin
plugins {
  id("muplay.android.library")
  id("muplay.android.room")
  id("muplay.android.hilt")
}
```

and add to `dependencies`:

```kotlin
  testImplementation(libs.coroutines.test)
  androidTestImplementation(libs.turbine)
```

- [ ] **Step 6: Implement the status and the model**

`integrations/core/src/main/kotlin/app/muplay/integrations/RequestStatus.kt`:

```kotlin
package app.muplay.integrations

/**
 * Where one submitted request has got to.
 *
 * A sealed interface, per this project's "sealed interfaces for state and results" rule, and the
 * members are deliberately about **what the user can see next**, not about either service's
 * internal vocabulary. Lidarr and Bindery describe their pipelines differently; each client maps
 * its own vocabulary onto these five, and the mapping is tested against real captured payloads
 * rather than invented (see Tasks 6 and 7).
 *
 * [Imported] and [Arrived] are two different facts and collapsing them would be a bug of exactly
 * the kind spec section 4 warns about for capability negotiation. "The service says the files are
 * on disk" is not "Navidrome has scanned them and you can press play"; the gap between the two is
 * a whole scan cycle, and Task 8 is the code that closes it.
 *
 * Persisted as a `(name, detail)` pair of TEXT columns rather than as a Room `TypeConverter` over
 * a JSON blob: two columns are queryable, greppable in a bug report and readable in a database
 * dump, and this type has one data field per member at most.
 */
sealed interface RequestStatus {

  /** The service accepted the request and has not started fetching anything yet. */
  data object Requested : RequestStatus

  /** The download client is working. [percentComplete] is `null` when the service does not say. */
  data class Downloading(val percentComplete: Int?) : RequestStatus

  /** The service reports the files are in place. Navidrome has **not** necessarily seen them. */
  data object Imported : RequestStatus

  /** The mirror has it: [albumId] is a Navidrome album id and the UI can navigate to it. */
  data class Arrived(val albumId: String) : RequestStatus

  /** The service, or this client, could not complete the request. [reason] is shown to the user. */
  data class Failed(val reason: String) : RequestStatus

  /** The value stored in the `status` column. Stable on disk; renaming a member does not change it. */
  val storedName: String
    get() = when (this) {
      Requested -> "REQUESTED"
      is Downloading -> "DOWNLOADING"
      Imported -> "IMPORTED"
      is Arrived -> "ARRIVED"
      is Failed -> "FAILED"
    }

  /** The value stored in the `status_detail` column, or `null` for members that carry no data. */
  val storedDetail: String?
    get() = when (this) {
      Requested -> null
      is Downloading -> percentComplete?.toString()
      Imported -> null
      is Arrived -> albumId
      is Failed -> reason
    }

  companion object {

    /**
     * Reconstitutes a status from its two stored columns.
     *
     * Every unreadable case becomes a [Failed] that **names what it saw**, rather than a plausible
     * default. A corrupt row read as [Requested] tells the user their request is still in progress
     * forever and tells whoever reads the bug report nothing at all; a corrupt row read as
     * `Failed("unrecognised stored status \"X\"")` tells both.
     */
    fun fromStored(name: String, detail: String?): RequestStatus = when (name) {
      "REQUESTED" -> Requested
      "DOWNLOADING" -> Downloading(percentComplete = detail?.toIntOrNull())
      "IMPORTED" -> Imported
      "ARRIVED" ->
        if (detail.isNullOrEmpty()) Failed("stored ARRIVED row carried no album id") else Arrived(detail)
      "FAILED" -> Failed(detail ?: "the request failed")
      else -> Failed("unrecognised stored status \"$name\"")
    }
  }
}
```

`integrations/core/src/main/kotlin/app/muplay/integrations/MediaRequest.kt`:

```kotlin
package app.muplay.integrations

/**
 * One thing the user asked an integration for.
 *
 * [id] is `"<SERVICE>:<externalId>"`, which makes re-requesting the same album an *update* rather
 * than a duplicate row, and keeps two services that happen to use the same identifier space from
 * colliding onto one row. [externalId] is whatever the service identifies the work by — for Lidarr
 * a MusicBrainz id; see `:integrations:bindery` for Bindery's.
 *
 * [remoteId] is the id the *service* assigned after accepting the request, and is `null` until it
 * does. It is what status polling looks the request up by, and it is separate from [externalId]
 * for a reason: one is the identity of the work in the world, the other is the identity of a row
 * in someone's database.
 */
data class MediaRequest(
  val id: String,
  val service: IntegrationService,
  val externalId: String,
  val title: String,
  val subtitle: String,
  val remoteId: String?,
  val status: RequestStatus,
  val requestedAtEpochMs: Long,
  val updatedAtEpochMs: Long,
) {
  companion object {
    fun idFor(service: IntegrationService, externalId: String): String = "${service.name}:$externalId"
  }
}
```

- [ ] **Step 7: Implement the database, entity, DAO and clock qualifier**

`integrations/core/src/main/kotlin/app/muplay/integrations/db/MediaRequestEntity.kt`:

```kotlin
package app.muplay.integrations.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per submitted request.
 *
 * [service] is stored as the enum's `name`, not its ordinal: an ordinal is a number whose meaning
 * changes when someone reorders the enum, and `IntegrationService`'s declaration order is
 * deliberately load-bearing for rendering.
 *
 * `status` and `statusDetail` are two plain TEXT columns rather than a serialised
 * `RequestStatus` — see that type's own doc for why.
 */
@Entity(tableName = "media_requests")
data class MediaRequestEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(index = true) val service: String,
  val externalId: String,
  val title: String,
  val subtitle: String,
  val remoteId: String?,
  val status: String,
  val statusDetail: String?,
  val requestedAtEpochMs: Long,
  val updatedAtEpochMs: Long,
)
```

`integrations/core/src/main/kotlin/app/muplay/integrations/db/MediaRequestDao.kt`:

```kotlin
package app.muplay.integrations.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaRequestDao {

  /**
   * Newest first. The `ORDER BY` is not decoration: without it SQLite returns rowid order, which
   * is insertion order, which is not what the UI promises — and `MediaRequestRepositoryTest`
   * inserts in an order that makes the difference visible.
   */
  @Query("SELECT * FROM media_requests ORDER BY requestedAtEpochMs DESC, id ASC")
  fun observeAll(): Flow<List<MediaRequestEntity>>

  @Query(
    "SELECT * FROM media_requests WHERE service = :service ORDER BY requestedAtEpochMs DESC, id ASC",
  )
  fun observeByService(service: String): Flow<List<MediaRequestEntity>>

  @Query("SELECT * FROM media_requests WHERE id = :id")
  suspend fun find(id: String): MediaRequestEntity?

  @Upsert
  suspend fun upsert(entity: MediaRequestEntity)

  @Query(
    "UPDATE media_requests SET status = :status, statusDetail = :statusDetail, " +
      "updatedAtEpochMs = :updatedAtEpochMs WHERE id = :id",
  )
  suspend fun updateStatus(id: String, status: String, statusDetail: String?, updatedAtEpochMs: Long)

  @Query("DELETE FROM media_requests WHERE id = :id")
  suspend fun delete(id: String)
}
```

`integrations/core/src/main/kotlin/app/muplay/integrations/db/IntegrationRequestsDatabase.kt`:

```kotlin
package app.muplay.integrations.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The integrations' own database. **Not a table in `MuPlayDatabase`**, and the reason is in the
 * plan: this plan is meant to be droppable and reorderable, and a version number claimed inside
 * `MuPlayDatabase` would be a guess about what Plans 3-6 did to it. Version 1, forever, whatever
 * order the plans land in.
 */
@Database(entities = [MediaRequestEntity::class], version = 1)
abstract class IntegrationRequestsDatabase : RoomDatabase() {
  abstract fun requestDao(): MediaRequestDao

  companion object {
    const val DATABASE_NAME: String = "muplay-integration-requests.db"
  }
}
```

`integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationsClock.kt`:

```kotlin
package app.muplay.integrations

import javax.inject.Qualifier

/**
 * The clock this plan's code reads time from.
 *
 * Qualified, because Plan 3's `:core:media` provides an **unqualified** `@Singleton
 * java.time.Clock` and two unqualified bindings of one type is a Hilt build failure. This plan
 * depends on Plan 2, not on Plan 3, and must build whether or not Plan 3 has landed — a qualifier
 * costs one annotation and removes the ordering dependency entirely.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IntegrationsClock
```

- [ ] **Step 8: Implement the repository and extend the Hilt module**

`integrations/core/src/main/kotlin/app/muplay/integrations/MediaRequestRepository.kt`:

```kotlin
package app.muplay.integrations

import app.muplay.integrations.db.MediaRequestDao
import app.muplay.integrations.db.MediaRequestEntity
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The only entry point to stored requests, per this project's repository rule.
 *
 * **Every method here that takes an argument is a place this project has previously shipped a
 * defect**: a delegating method that forwards to a DAO can discard its argument and hardcode a
 * value with the whole suite green at 100% branch coverage. `MediaRequestRepositoryTest` proves
 * passthrough by observing two disjoint results from the same method, never by observing that the
 * DAO was called.
 */
@Singleton
class MediaRequestRepository @Inject constructor(
  private val dao: MediaRequestDao,
  @IntegrationsClock private val clock: Clock,
) {

  fun requests(): Flow<List<MediaRequest>> = dao.observeAll().map { rows -> rows.map(::toModel) }

  fun requests(service: IntegrationService): Flow<List<MediaRequest>> =
    dao.observeByService(service.name).map { rows -> rows.map(::toModel) }

  /**
   * Records a request that a service has **already accepted**, and returns the stored row.
   *
   * Called after the submit succeeds, never before: a row that exists for a submit that failed is
   * a request the user believes they made and nobody is fulfilling.
   *
   * Re-recording the same `(service, externalId)` updates the existing row and **keeps its
   * original `requestedAtEpochMs`**, so a status poll that refreshes a row does not make the
   * user's "requested on" date jump forward.
   */
  suspend fun record(
    service: IntegrationService,
    externalId: String,
    title: String,
    subtitle: String,
    remoteId: String?,
  ): MediaRequest {
    val id = MediaRequest.idFor(service, externalId)
    val now = clock.millis()
    val existing = dao.find(id)
    val entity = MediaRequestEntity(
      id = id,
      service = service.name,
      externalId = externalId,
      title = title,
      subtitle = subtitle,
      remoteId = remoteId,
      status = existing?.status ?: RequestStatus.Requested.storedName,
      statusDetail = existing?.statusDetail ?: RequestStatus.Requested.storedDetail,
      requestedAtEpochMs = existing?.requestedAtEpochMs ?: now,
      updatedAtEpochMs = now,
    )
    dao.upsert(entity)
    return toModel(entity)
  }

  suspend fun setStatus(id: String, status: RequestStatus) {
    dao.updateStatus(
      id = id,
      status = status.storedName,
      statusDetail = status.storedDetail,
      updatedAtEpochMs = clock.millis(),
    )
  }

  suspend fun forget(id: String) = dao.delete(id)

  private fun toModel(entity: MediaRequestEntity) = MediaRequest(
    id = entity.id,
    // `valueOf` would throw on a row written by a future version of this app that added a third
    // service. A row we cannot interpret is dropped by the caller rather than crashing the list,
    // which is why this is a lookup and not a parse -- see `requests()`'s `mapNotNull` below.
    service = IntegrationService.entries.first { it.name == entity.service },
    externalId = entity.externalId,
    title = entity.title,
    subtitle = entity.subtitle,
    remoteId = entity.remoteId,
    status = RequestStatus.fromStored(entity.status, entity.statusDetail),
    requestedAtEpochMs = entity.requestedAtEpochMs,
    updatedAtEpochMs = entity.updatedAtEpochMs,
  )
}
```

> **Read the comment above and then fix the code it describes.** As written, `first { }` throws
> `NoSuchElementException` on an unknown service string — the comment claims a behaviour the code
> does not have. Change `toModel` to return `MediaRequest?` using `firstOrNull`, and make both
> `requests()` overloads use `mapNotNull(::toModel)`; `record` can keep a non-null return because
> it constructs the row itself. Then add this test to `MediaRequestRepositoryTest`:
>
> ```kotlin
>   @Test
>   fun `a row naming a service this build does not know is skipped, not fatal`() = runTest {
>     database.requestDao().upsert(
>       MediaRequestEntity(
>         id = "SOMETHING:1", service = "SOMETHING", externalId = "1", title = "t",
>         subtitle = "s", remoteId = null, status = "REQUESTED", statusDetail = null,
>         requestedAtEpochMs = 1L, updatedAtEpochMs = 1L,
>       ),
>     )
>     repository.record(IntegrationService.LIDARR, "mbid-1", "A", "x", null)
>
>     // The known row still renders; the unknown one is simply not there.
>     assertThat(repository.requests().first().map { it.id }).containsExactly("LIDARR:mbid-1")
>   }
> ```
>
> This is deliberate in the plan: an inconsistency between a comment and its code is the exact
> shape of the defect that survives review, and finding it is cheaper as an instruction than as a
> bug.

`IntegrationsDataModule` gains three providers:

```kotlin
  @Provides
  @Singleton
  fun provideRequestsDatabase(@ApplicationContext context: Context): IntegrationRequestsDatabase =
    Room.databaseBuilder(
      context,
      IntegrationRequestsDatabase::class.java,
      IntegrationRequestsDatabase.DATABASE_NAME,
    ).build()

  @Provides
  fun provideMediaRequestDao(database: IntegrationRequestsDatabase): MediaRequestDao =
    database.requestDao()

  /** See `IntegrationsClock` for why this binding is qualified. */
  @Provides
  @Singleton
  @IntegrationsClock
  fun provideClock(): Clock = Clock.systemUTC()
```

- [ ] **Step 9: Run both suites, then add the passthrough probe**

Run: `./gradlew :integrations:core:test :integrations:core:connectedDebugAndroidTest`
Expected: PASS.

`ci/mutation-probes.sh` — add, with the same caveat Task 2's probe carries about instrumented
tests:

```python
REPO = "integrations/core/src/main/kotlin/app/muplay/integrations/MediaRequestRepository.kt"
```

```python
    # ---- Plan 7: the delegating method that can drop its argument -----------------------------
    ("integrations/requests-by-service-passthrough", REPO,
     "dao.observeByService(service.name)", 'dao.observeByService("LIDARR")',
     "requests filtered by service returns that service's rows and only those", 1),
    ("integrations/setStatus-id-passthrough", REPO,
     "      id = id,\n      status = status.storedName,",
     '      id = "LIDARR:mbid-1",\n      status = status.storedName,',
     "setStatus changes only the row it names, and stamps updatedAt", 1),
    ("integrations/requests-order", "integrations/core/src/main/kotlin/app/muplay/integrations/db/MediaRequestDao.kt",
     "ORDER BY requestedAtEpochMs DESC, id ASC\")\n  fun observeAll",
     "ORDER BY requestedAtEpochMs ASC, id ASC\")\n  fun observeAll",
     "requests come back newest first regardless of insertion order", 1),
```

- [ ] **Step 10: Commit**

```bash
git add integrations/core build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(integrations): a request store in a database of its own

A second RoomDatabase rather than a table in MuPlayDatabase, for two reasons: deleting this
plan should delete its storage, and any version number claimed inside MuPlayDatabase would
be a guess about what Plans 3-6 did to it -- a wrong guess is a migration conflict found by
whoever lands second. Version 1, whatever order the plans land in.

The repository's filter-by-service and set-status-by-id methods are the shape this project
has shipped a defect in twice: a delegating method that accepts an argument and hardcodes a
value. Both are proved by two disjoint observations and both carry a mutation probe."
```

---

## Task 4: `:integrations:lidarr` — the module, a key that never touches a URL, and the handshake

**Files:**
- Create: `integrations/lidarr/build.gradle.kts`
- Create: `integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrApi.kt`
- Create: `integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrDto.kt`
- Create: `integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrException.kt`
- Create: `integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrAuthInterceptor.kt`
- Create: `integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrSource.kt`
- Create: `integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrClient.kt`
- Create: `integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrSourceProvider.kt`
- Create: `integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrAuthTest.kt`
- Create: `integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrHandshakeTest.kt`
- Create: `integrations/lidarr/src/test/resources/fixtures/lidarr/system-status.json`
- Create: `integrations/lidarr/src/test/resources/fixtures/lidarr/ping-ok.json`
- Create: `integrations/lidarr/src/test/resources/fixtures/lidarr/starting-up.json`
- Modify: `settings.gradle.kts`, `build.gradle.kts`
- Modify: `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `IntegrationBaseUrl`, `IntegrationCredentials.Lidarr`, `IntegrationCredentialStore`
  (Tasks 1–2)
- Produces:
  - `data class LidarrServer(appName: String, instanceName: String, version: String, urlBase: String, authentication: String)`
  - `sealed interface LidarrException`; `LidarrUnauthorizedException`, `LidarrStartingUpException`,
    `LidarrValidationException(failures: List<LidarrValidationFailure>)`,
    `LidarrHttpException(status: Int)`
  - `data class LidarrValidationFailure(propertyName: String?, errorMessage: String?)`
  - `class LidarrAuthInterceptor(apiKey: String) : Interceptor`
  - `interface LidarrSource` with `suspend fun ping(): Boolean` and `suspend fun status(): LidarrServer`
    (Tasks 5–7 add methods to it)
  - `class LidarrClient(credentials: IntegrationCredentials.Lidarr, api: LidarrApi = …) : LidarrSource`
  - `fun interface LidarrSourceFactory { fun create(c: IntegrationCredentials.Lidarr): LidarrSource }`,
    `object DefaultLidarrSourceFactory`
  - `class LidarrSourceProvider @Inject constructor(store, factory)` with
    `suspend fun current(): LidarrSource?` — **nullable, and that is the point**

### The facts this task is built on, and where each came from

Every claim below is from Lidarr's own source on `github.com/Lidarr/Lidarr` (`develop`, the branch
`lidarr.audio/docs/api/` itself loads its OpenAPI from) or from its checked-in
`src/Lidarr.Api.V1/openapi.json`. See **Research provenance** for the full citation list.

| Fact | Source |
|---|---|
| Three accepted key forms: `X-Api-Key` header, `?apikey=` query, `Authorization: Bearer` | `src/Lidarr.Http/Authentication/AuthenticationBuilderExtensions.cs`, `ApiKeyAuthenticationHandler.cs` |
| **Lidarr logs the full path *and query string*** to `lidarr.trace.txt` | `src/Lidarr.Http/Middleware/LoggingMiddleware.cs` |
| Unauthenticated *and* wrong-key both return **401 with an empty body** — indistinguishable | `ApiKeyAuthenticationHandler.HandleChallengeAsync` |
| `GET /ping` is the **only** `[AllowAnonymous]` endpoint, is **not** under `/api/v1`, returns `{"status":"OK"}` | `src/Lidarr.Http/Ping/PingController.cs` |
| `/ping` is identical in Sonarr/Radarr/Prowlarr, so it does **not** prove the server is Lidarr | same file; the resource has one field |
| `GET /api/v1/system/status` carries `appName`, `instanceName`, `version`, `urlBase`, `authentication` | `openapi.json` `SystemResource` |
| During boot **any** API request returns **503** `{"errorMessage":"Lidarr is starting up, please try again later"}` | `src/Lidarr.Http/Middleware/StartingUpMiddleware.cs` |
| `ReturnHttpNotAcceptable = true` — a request without `Accept: application/json` can get **406** | `src/Lidarr.Http/Startup.cs` |
| A `urlBase` server answers `/api/v1/...` with a **307** to `{urlBase}/api/v1/...`, method and body preserved | `src/Lidarr.Http/Middleware/UrlBaseMiddleware.cs` |
| Responses are camelCase, **null fields are omitted**, enums are camelCase strings | `src/NzbDrone.Common/Serializer/System.Text.Json/STJson.cs` |
| The API key is a 32-char lowercase hex GUID with the dashes removed | `ConfigFileProvider.cs` |

### Why the key goes in a header and only in a header

Lidarr accepts `?apikey=` and the OpenAPI document lists it as a supported security scheme. **Do
not use it.** `LoggingMiddleware` writes `string.Concat(request.Path, request.QueryString)` into
Lidarr's own trace log, so a query-string key ends up in a file on the user's server that they will
cheerfully paste into a support thread. On this side of the wire the same string would appear in
every `MockWebServer` recorded request, in any fixture captured from a real instance, and in the
message of any `IOException` a crash reporter uploads.

The header form is not merely preferable, it is what makes the plan's
*"never place them in a URL that could be recorded in a fixture or a crash report"* constraint
achievable at all — and `LidarrAuthTest`'s `no request this client makes carries the key on its
url` is the assertion that keeps it true for every endpoint Tasks 5–7 add, not just the ones that
exist today.

### Why the handshake is `system/status` and not `ping`

`ping` proves *something* is listening and it is unauthenticated, which makes it useless as a
credential check and ambiguous as an identity check: Sonarr, Radarr and Prowlarr all serve a
byte-identical `{"status":"OK"}` at the same path. A user who pastes their Sonarr URL into the
Lidarr field would get a green tick and then a stream of 404s.

`GET /api/v1/system/status` with the header answers all four questions in one round trip:
reachable, authenticated, **actually Lidarr** (`appName == "Lidarr"`), and what its `urlBase` is.
`ping` is still implemented — it is the only thing that can distinguish "unreachable" from
"reachable but rejecting us" when `status` returns 401 — and `LidarrHandshakeTest` pins that
distinction.

### The trap: 401 cannot tell you whether the key is wrong

`HandleAuthenticateAsync` returns `NoResult()` for a *missing* key and for a *wrong* key alike, and
`HandleChallengeAsync` writes a bare 401 with no body. So this client **must not** produce a
message that claims to know which. `LidarrUnauthorizedException`'s message says
*"Lidarr rejected this API key"* — which is true in both cases — rather than "your key is wrong",
which would be a guess presented as a fact. This is the same discipline spec §4 applies to
capability negotiation: *"degrade to 'OpenSubsonic, no known extensions' — **not** to 'not
OpenSubsonic'. Those are different facts and collapsing them loses information."*

- [ ] **Step 1: Create the module**

`settings.gradle.kts`:

```kotlin
include(":integrations:lidarr")
```

`integrations/lidarr/build.gradle.kts`:

```kotlin
plugins {
  id("muplay.android.library")
  id("muplay.kotlin.serialization")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.integrations.lidarr"
}

dependencies {
  // `api`, not `implementation`: LidarrSourceProvider's public signature returns a LidarrSource
  // built from an IntegrationCredentials.Lidarr, both of which are declared in :integrations:core.
  api(project(":integrations:core"))

  implementation(libs.okhttp)
  implementation(libs.retrofit)
  implementation(libs.retrofit.serialization)
  implementation(libs.serialization.json)
  implementation(libs.coroutines.core)

  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.coroutines.test)
}
```

`build.gradle.kts` — a placeholder floor, measured in Step 10:

```kotlin
  // `:integrations:lidarr`. Every class in this module is plain Kotlin over Retrofit/OkHttp with
  // no Android dependency, so the whole module is Tier-1 BRANCH-enforceable -- the same shape as
  // `:core:network`'s single rule. Measured in Task 4 Step 10 and re-measured in Task 11.
  ":integrations:lidarr" to listOf(CoverageFloor(counter = "BRANCH", minimum = BigDecimal("0.90"))),
```

- [ ] **Step 2: Write the failing auth test**

`integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrAuthTest.kt`:

```kotlin
package app.muplay.integrations.lidarr

import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentials
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * How this client authenticates, over a real HTTP server.
 *
 * The subject is the **request**, not the response. Plan 1 proved by mutation that an
 * `authParams()` returning an empty map left every response assertion in the codebase green, and
 * this is the same class of value: a header that is absent produces a 401 that a test written
 * around a canned 200 never sees.
 */
class LidarrAuthTest {

  @StartStop private val server = MockWebServer()

  private fun clientWith(apiKey: String): LidarrClient {
    val url = IntegrationBaseUrl.parse(server.url("/").toString(), CleartextPolicy.Allowed)
    return LidarrClient(
      IntegrationCredentials.Lidarr(
        baseUrl = (url as BaseUrlResult.Valid).url,
        apiKey = apiKey,
      ),
    )
  }

  private fun enqueueStatus() {
    server.enqueue(
      MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(readFixture("lidarr/system-status.json"))
        .build(),
    )
  }

  @Test
  fun `every request carries the api key in the X-Api-Key header`() = runTest {
    enqueueStatus()

    clientWith("0123456789abcdef0123456789abcdef").status()

    assertThat(nextRequest().headers["X-Api-Key"]).isEqualTo("0123456789abcdef0123456789abcdef")
  }

  @Test
  fun `the header carries whichever key the client was given`() = runTest {
    // The second observation. A hardcoded header value passes the test above.
    enqueueStatus()
    clientWith("ffffffffffffffffffffffffffffffff").status()

    assertThat(nextRequest().headers["X-Api-Key"]).isEqualTo("ffffffffffffffffffffffffffffffff")
  }

  /**
   * `Startup.cs` sets `ReturnHttpNotAcceptable = true`, so a request that does not declare an
   * acceptable media type can be answered with **406** rather than JSON. Retrofit does not set
   * `Accept` on its own.
   */
  @Test
  fun `every request declares that it accepts json`() = runTest {
    enqueueStatus()
    clientWith("k").status()

    assertThat(nextRequest().headers["Accept"]).contains("application/json")
  }

  /**
   * The constraint this whole plan is written under: **the key never appears in a URL.**
   *
   * Lidarr *does* accept `?apikey=`, and its own `LoggingMiddleware` writes
   * `request.Path + request.QueryString` into `lidarr.trace.txt` — so a query-string key ends up
   * in a log file on the user's server. On this side it would appear in every recorded request,
   * every captured fixture and every `IOException` message a crash reporter uploads.
   *
   * Asserted over **every** recorded request rather than the first, so an endpoint added by a
   * later task cannot quietly reintroduce it.
   */
  @Test
  fun `no request this client makes carries the key on its url`() = runTest {
    val key = "0123456789abcdef0123456789abcdef"
    enqueueStatus()
    server.enqueue(MockResponse.Builder().code(200).body("""{"status":"OK"}""").build())

    val client = clientWith(key)
    client.status()
    client.ping()

    val urls = listOf(nextRequest(), nextRequest()).map { it.url.toString() }

    // Two requests were made and both are inspected. `hasSize(2)` first, because `allSatisfy` over
    // an empty list is vacuously true -- which is exactly the shape this project has shipped
    // before, and `nextRequest()` failing loudly on an empty queue is the other half of the guard.
    assertThat(urls).hasSize(2)
    assertThat(urls).allSatisfy { url -> assertThat(url).doesNotContain(key) }
    assertThat(urls).allSatisfy { url -> assertThat(url).doesNotContain("apikey") }
  }

  /**
   * A `urlBase` server answers `/api/v1/...` with a **307** to `{urlBase}/api/v1/...`
   * (`UrlBaseMiddleware.cs`). OkHttp follows it, and because it is same-host it keeps the
   * `X-Api-Key` header — but "OkHttp keeps auth headers on same-host redirects" is a claim about
   * OkHttp, and this project does not ship claims about libraries it has not observed.
   */
  @Test
  fun `a urlBase redirect is followed with the key and the path intact`() = runTest {
    server.enqueue(
      MockResponse.Builder()
        .code(307)
        .setHeader("Location", server.url("/lidarr/api/v1/system/status").toString())
        .build(),
    )
    enqueueStatus()

    clientWith("k-307").status()

    val first = nextRequest()
    val second = nextRequest()
    assertThat(first.url.encodedPath).isEqualTo("/api/v1/system/status")
    assertThat(second.url.encodedPath).isEqualTo("/lidarr/api/v1/system/status")
    assertThat(second.headers["X-Api-Key"]).isEqualTo("k-307")
  }
}
```

Add a tiny fixture reader shared by this module's tests —
`integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/Fixtures.kt`:

```kotlin
package app.muplay.integrations.lidarr

/**
 * Reads a committed Lidarr fixture from this module's own test resources.
 *
 * Deliberately **not** in `:core:testing`. Fixtures for an optional integration belong to the
 * module that would be deleted with it — the severability rule in this plan's header is a rule
 * about test data too.
 */
internal fun readFixture(path: String): String =
  checkNotNull(object {}.javaClass.classLoader.getResourceAsStream("fixtures/$path")) {
    "missing fixture: fixtures/$path"
  }.use { it.readBytes().decodeToString() }
```

- [ ] **Step 3: Capture the fixtures from a real Lidarr — do this before writing any mapping code**

**This step is not optional and it is not a formality.** The plan's rule is that no endpoint shape
is invented. Stand up a real Lidarr and capture what it actually sends.

```bash
# 1. Start one. There is no official Lidarr image; linuxserver and hotio both publish. List real
#    tags first and PIN one -- do not use `latest`, and do not take the tag from this plan, which
#    has not run this command:
#      curl -s 'https://registry.hub.docker.com/v2/repositories/linuxserver/lidarr/tags?page_size=50' \
#        | python3 -c 'import json,sys; [print(t["name"]) for t in json.load(sys.stdin)["results"]]'
#    Record the tag you pinned in the task report; Task 11 puts it in ci/lidarr.compose.yml.
docker run -d --name lidarr-capture -p 8686:8686 \
  -e PUID=1000 -e PGID=1000 -e TZ=Etc/UTC \
  -v "$PWD/.lidarr-capture:/config" \
  lscr.io/linuxserver/lidarr:<THE TAG YOU PINNED>

# 2. Wait for it to write its config, then read the generated API key. It is a 32-char lowercase
#    hex GUID with the dashes removed (ConfigFileProvider.cs).
until docker exec lidarr-capture test -f /config/config.xml; do sleep 2; done
KEY=$(docker exec lidarr-capture sed -n 's:.*<ApiKey>\(.*\)</ApiKey>.*:\1:p' /config/config.xml)
echo "key length: ${#KEY}"   # expect 32

# 3. Capture. NOTE: the key is a header, so none of these bodies contains it -- but check anyway
#    before committing, with the grep at the end.
mkdir -p integrations/lidarr/src/test/resources/fixtures/lidarr
cap() { curl -sS -H "X-Api-Key: $KEY" -H 'Accept: application/json' \
          "http://localhost:8686$1" | python3 -m json.tool \
          > "integrations/lidarr/src/test/resources/fixtures/lidarr/$2"; }

curl -sS 'http://localhost:8686/ping' | python3 -m json.tool \
  > integrations/lidarr/src/test/resources/fixtures/lidarr/ping-ok.json
cap /api/v1/system/status      system-status.json
cap /api/v1/rootfolder         rootfolder.json
cap /api/v1/qualityprofile     qualityprofile.json
cap /api/v1/metadataprofile    metadataprofile.json
cap /api/v1/queue              queue-empty.json

# 4. The two error shapes, which are the ones nobody thinks to capture until they need them.
curl -sS -o integrations/lidarr/src/test/resources/fixtures/lidarr/validation-error-empty-album.json \
  -w '%{http_code}\n' -X POST 'http://localhost:8686/api/v1/album' \
  -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' -d '{}'      # expect 400
curl -sS -i 'http://localhost:8686/api/v1/system/status' -H 'X-Api-Key: wrong' | head -20  # expect 401, empty body

# 5. Prove no fixture carries the key before committing any of them.
! grep -rl "$KEY" integrations/lidarr/src/test/resources/ && echo "clean"
```

**Record in the task report**: the pinned image tag, the Lidarr `version` string from
`system-status.json`, the exact HTTP status and body of the empty-`{}` POST, and whether a
wrong-key request really returned 401 with an empty body. Those four are the plan's own
assumptions being checked, and any of them differing is a finding worth writing down.

`starting-up.json` cannot be captured reliably by hand (the window is a few seconds). Write it from
the source, and say so in a comment inside the file's test:

```json
{"errorMessage":"Lidarr is starting up, please try again later"}
```

**If no Lidarr can be stood up at all**, stop and say so in the task report rather than proceeding
on the provisional fixtures below. This plan's whole claim to correctness on the add payload
(Task 6) is that it was checked against a real instance.

**Provisional `system-status.json`, to be overwritten by the capture.** Its field names come from
`openapi.json`'s `SystemResource` and are guaranteed by the source; a real capture will have
*more* fields, never fewer, and the client parses with `ignoreUnknownKeys = true`:

```json
{
  "appName": "Lidarr",
  "instanceName": "Lidarr",
  "version": "3.1.0.4875",
  "buildTime": "2025-11-16T00:00:00Z",
  "isDebug": false,
  "isProduction": true,
  "isAdmin": false,
  "isUserInteractive": false,
  "startupPath": "/app/lidarr/bin",
  "appData": "/config",
  "osName": "ubuntu",
  "osVersion": "24.04",
  "isNetCore": true,
  "isLinux": true,
  "isOsx": false,
  "isWindows": false,
  "isDocker": true,
  "mode": "console",
  "branch": "master",
  "databaseType": "sqLite",
  "databaseVersion": "3.53.4",
  "authentication": "forms",
  "migrationVersion": 79,
  "urlBase": "",
  "runtimeVersion": "8.0.421",
  "runtimeName": "netCore",
  "startTime": "2026-08-24T10:00:00Z",
  "packageVersion": "3.1.0.4875",
  "packageAuthor": "linuxserver.io",
  "packageUpdateMechanism": "docker"
}
```

`ping-ok.json`:

```json
{"status":"OK"}
```

- [ ] **Step 4: Write the failing handshake test**

`integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrHandshakeTest.kt`:

```kotlin
package app.muplay.integrations.lidarr

import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentials
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * What "is this a working Lidarr" means, and what each way it can fail is called.
 *
 * The distinctions here are the point. A 401 that reads as "unreachable", or a Sonarr that reads
 * as a working Lidarr, are both silent-wrong-answers — the failure class this project ranks worst.
 */
class LidarrHandshakeTest {

  @StartStop private val server = MockWebServer()

  private fun client(): LidarrClient {
    val url = IntegrationBaseUrl.parse(server.url("/").toString(), CleartextPolicy.Allowed)
    return LidarrClient(
      IntegrationCredentials.Lidarr((url as BaseUrlResult.Valid).url, apiKey = "k"),
    )
  }

  private fun json(code: Int, body: String) =
    MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

  @Test
  fun `status reports every field the configuration screen needs`() = runTest {
    server.enqueue(json(200, readFixture("lidarr/system-status.json")))

    val status = client().status()

    // Field by field, from a fixture captured off a real instance. `appName` is the one the
    // configuration screen branches on and `urlBase` is the one a proxied install needs.
    assertThat(status.appName).isEqualTo("Lidarr")
    assertThat(status.version).isNotBlank()
    assertThat(status.urlBase).isEqualTo("")
    assertThat(status.instanceName).isNotBlank()
    assertThat(status.authentication).isNotBlank()
  }

  @Test
  fun `status reads the values from the body, not from constants`() = runTest {
    // The second observation of every mapped field. Without this, a `status()` that returned a
    // fixed LidarrServer passes the test above -- which is round four of this project's defect
    // history, applied to a new client.
    server.enqueue(
      json(
        200,
        """
        {"appName":"Sonarr","instanceName":"Media","version":"9.9.9.9","urlBase":"/lidarr",
         "authentication":"none"}
        """.trimIndent(),
      ),
    )

    val status = client().status()

    assertThat(status.appName).isEqualTo("Sonarr")
    assertThat(status.instanceName).isEqualTo("Media")
    assertThat(status.version).isEqualTo("9.9.9.9")
    assertThat(status.urlBase).isEqualTo("/lidarr")
    assertThat(status.authentication).isEqualTo("none")
  }

  @Test
  fun `the request goes to api v1 system status`() = runTest {
    server.enqueue(json(200, readFixture("lidarr/system-status.json")))

    client().status()

    assertThat(nextRequest().url.encodedPath).isEqualTo("/api/v1/system/status")
  }

  /**
   * 401 is the same answer for a missing key and a wrong one — `HandleAuthenticateAsync` returns
   * `NoResult()` for both and `HandleChallengeAsync` writes a bare status with no body. The
   * message must therefore not claim to know which.
   */
  @Test
  fun `a 401 is an unauthorized failure whose message does not overclaim`() = runTest {
    server.enqueue(MockResponse.Builder().code(401).build())

    assertThatThrownBy { client().status() }
      .isInstanceOf(LidarrUnauthorizedException::class.java)
      .hasMessageContaining("rejected")
      // Lidarr cannot tell us the key is *wrong* rather than missing, so this client must not say so.
      .hasMessageNotContaining("incorrect")
  }

  /**
   * A container that has just restarted answers every API call with 503 and this exact body
   * (`StartingUpMiddleware.cs`). It is a normal transient state, not a configuration error, and a
   * client that reported it as "cannot reach Lidarr" would send the user to check their firewall.
   */
  @Test
  fun `a 503 starting-up body is its own failure, distinct from any other 503`() = runTest {
    server.enqueue(json(503, readFixture("lidarr/starting-up.json")))

    assertThatThrownBy { client().status() }
      .isInstanceOf(LidarrStartingUpException::class.java)

    // ...and a 503 that is *not* Lidarr starting up -- a reverse proxy with no upstream, say --
    // must not be mistaken for one. Two observations of the same status code, discriminated by
    // body: without the second, a client that mapped every 503 to StartingUp passes the first.
    server.enqueue(MockResponse.Builder().code(503).body("<html>502 Bad Gateway</html>").build())
    assertThatThrownBy { client().status() }
      .isInstanceOf(LidarrHttpException::class.java)
  }

  @Test
  fun `any other unsuccessful status is a plain http failure carrying the code`() = runTest {
    // Two codes, so `status` is not a constant.
    server.enqueue(MockResponse.Builder().code(404).build())
    assertThatThrownBy { client().status() }
      .isInstanceOf(LidarrHttpException::class.java)
      .extracting { (it as LidarrHttpException).status }.isEqualTo(404)

    server.enqueue(MockResponse.Builder().code(500).build())
    assertThatThrownBy { client().status() }
      .isInstanceOf(LidarrHttpException::class.java)
      .extracting { (it as LidarrHttpException).status }.isEqualTo(500)
  }

  /**
   * `ping` exists for exactly one job: distinguishing "nothing is listening" from "something is
   * listening and rejecting our key". It is unauthenticated, so it answers the first question
   * without the second interfering.
   */
  @Test
  fun `ping is unauthenticated, unversioned, and true only for an OK body`() = runTest {
    server.enqueue(json(200, readFixture("lidarr/ping-ok.json")))
    assertThat(client().ping()).isTrue()
    // Not `/api/v1/ping`: PingController maps `/ping` at the root.
    assertThat(nextRequest().url.encodedPath).isEqualTo("/ping")

    // A 200 that is not an OK ping -- a captive portal, a proxy error page served with 200 -- is
    // not a Lidarr. Without this, `ping` returning `true` for any 200 passes the assertion above.
    server.enqueue(json(200, """{"status":"Error"}"""))
    assertThat(client().ping()).isFalse()
  }

  @Test
  fun `ping is false rather than throwing when nothing is listening`() = runTest {
    server.enqueue(MockResponse.Builder().code(500).build())

    // `ping`'s whole value is being a question that always has an answer. A throw here would make
    // the configuration screen's "is anything there at all?" branch need a try/catch of its own.
    assertThat(client().ping()).isFalse()
  }
}
```

- [ ] **Step 5: Run it to verify it fails**

Run: `./gradlew :integrations:lidarr:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: LidarrClient`.

- [ ] **Step 6: Implement the DTOs and the Retrofit surface**

`integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrDto.kt`:

```kotlin
package app.muplay.integrations.lidarr

import kotlinx.serialization.Serializable

/**
 * The wire shapes this client reads. **Every non-primitive field is nullable**, and that is not
 * defensive habit: Lidarr's serializer is configured with
 * `DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull`
 * (`src/NzbDrone.Common/Serializer/System.Text.Json/STJson.cs`), so a null-valued field is
 * **omitted from the response entirely** rather than serialised as `null`. A non-nullable Kotlin
 * field with no default would fail to parse a perfectly ordinary response.
 *
 * Names are camelCase because `PropertyNamingPolicy = JsonNamingPolicy.CamelCase`. Enums arrive as
 * camelCase strings (`JsonStringEnumConverter(JsonNamingPolicy.CamelCase, true)`), and are read as
 * `String` here rather than as Kotlin enums: the trailing `true` is `allowIntegerValues`, so the
 * same field can legally arrive as a number, and a Lidarr upgrade may add a member. An unknown
 * member must not fail a whole response — that is exactly the "unsupported features are silent
 * no-ops, not errors" rule spec section 4 states for the Subsonic client.
 */
@Serializable
internal data class PingBody(val status: String? = null)

@Serializable
internal data class SystemStatusBody(
  val appName: String? = null,
  val instanceName: String? = null,
  val version: String? = null,
  val urlBase: String? = null,
  val authentication: String? = null,
)

/**
 * One element of the JSON **array** a 400 carries.
 * `LidarrErrorPipeline.cs` writes `STJson.ToJson(validationException.Errors)` — a bare array of
 * FluentValidation failures, not an object. `propertyName` is PascalCase and dotted for nested
 * paths (`Artist.QualityProfileId`).
 */
@Serializable
internal data class ValidationFailureBody(
  val propertyName: String? = null,
  val errorMessage: String? = null,
)

/** The body a 503 carries while Lidarr boots (`StartingUpMiddleware.cs`). */
@Serializable
internal data class StartingUpBody(val errorMessage: String? = null)
```

`integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrApi.kt`:

```kotlin
package app.muplay.integrations.lidarr

import retrofit2.Response
import retrofit2.http.GET

/**
 * The raw Retrofit surface for Lidarr's v1 API.
 *
 * Every method returns `Response<T>` rather than `T`, because this client has to read the **status
 * code and the raw error body** on failure — a 400 carries a JSON array of validation failures, a
 * 503 carries a distinguishing message, and a 401 carries nothing at all. Retrofit's default
 * `HttpException` gives none of those without re-reading the body, and re-reading it is not
 * possible after the exception is constructed.
 *
 * `ping` is **not** under `api/v1`: `PingController` maps `/ping` at the application root, outside
 * the versioned controllers. Tasks 5-7 add the rest.
 */
internal interface LidarrApi {

  @GET("ping")
  suspend fun ping(): Response<PingBody>

  @GET("api/v1/system/status")
  suspend fun systemStatus(): Response<SystemStatusBody>
}
```

- [ ] **Step 7: Implement the interceptor and the exceptions**

`integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrAuthInterceptor.kt`:

```kotlin
package app.muplay.integrations.lidarr

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Puts the API key on every request **as a header**, and declares that this client accepts JSON.
 *
 * Lidarr accepts three forms of the key: the `X-Api-Key` header, an `?apikey=` query parameter,
 * and `Authorization: Bearer` (`ApiKeyAuthenticationHandler.ParseApiKey`). Only the header is
 * used, and the reason is Lidarr's own `LoggingMiddleware`, which writes
 * `string.Concat(request.Path, request.QueryString)` into `lidarr.trace.txt` — a query-string key
 * ends up in a log file on the user's server. On this side of the wire it would appear in every
 * recorded request, every captured fixture and every `IOException` message.
 *
 * The `Accept` header is here rather than on each `@GET`, because `Startup.cs` sets
 * `ReturnHttpNotAcceptable = true` and a request without one can be answered with 406. One place,
 * so an endpoint added later cannot forget it.
 */
internal class LidarrAuthInterceptor(private val apiKey: String) : Interceptor {

  override fun intercept(chain: Interceptor.Chain): Response =
    chain.proceed(
      chain.request().newBuilder()
        .header("X-Api-Key", apiKey)
        .header("Accept", "application/json")
        .build(),
    )
}
```

`integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrException.kt`:

```kotlin
package app.muplay.integrations.lidarr

/**
 * Everything that can go wrong once Lidarr produced a response on purpose.
 *
 * A sealed *interface* whose members each also extend `Exception`, for the same reason
 * `SubsonicException` is built this way: Kotlin cannot make an interface extend `Throwable`, so
 * this buys an exhaustive `when` at the cost of not being directly catchable. A genuine transport
 * failure — no route, a timeout — is deliberately **not** a member and propagates as whatever the
 * transport threw. "We could not ask" is not "Lidarr said no".
 */
sealed interface LidarrException

/**
 * Lidarr answered 401.
 *
 * **It is not knowable from this response whether the key is wrong or missing**:
 * `HandleAuthenticateAsync` returns `NoResult()` in both cases and `HandleChallengeAsync` writes a
 * bare 401 with an empty body. The message says only that the key was rejected — claiming more
 * would be a guess presented to the user as a fact.
 */
class LidarrUnauthorizedException :
  Exception("Lidarr rejected this API key"), LidarrException

/**
 * Lidarr is booting: a 503 whose body is
 * `{"errorMessage":"Lidarr is starting up, please try again later"}` (`StartingUpMiddleware.cs`).
 *
 * A normal transient state after a container restart, and separate from [LidarrHttpException] so a
 * caller can retry rather than telling the user to check their network.
 */
class LidarrStartingUpException :
  Exception("Lidarr is starting up"), LidarrException

/** One FluentValidation failure from a 400 body. `propertyName` is PascalCase, dotted when nested. */
data class LidarrValidationFailure(val propertyName: String?, val errorMessage: String?)

/**
 * Lidarr answered 400 with a JSON array of FluentValidation failures.
 *
 * This is also how a **duplicate add** arrives — not a 409. `ArtistExistsValidator` and
 * `AlbumExistsValidator` produce the messages `"This artist has already been added."` and
 * `"This album has already been added."`, with no machine-readable code beside them, which is why
 * [isAlreadyAdded] matches on the message and says so out loud rather than pretending to be
 * structural. If a Lidarr upgrade rewords those strings, this returns `false` and the user sees the
 * raw validation message — degraded, not wrong.
 */
class LidarrValidationException(val failures: List<LidarrValidationFailure>) :
  Exception(failures.joinToString("; ") { "${it.propertyName}: ${it.errorMessage}" }),
  LidarrException {

  val isAlreadyAdded: Boolean
    get() = failures.any { it.errorMessage?.contains("has already been added", ignoreCase = true) == true }
}

/** Any other unsuccessful HTTP status. [status] is the HTTP code, never a Lidarr-level one. */
class LidarrHttpException(val status: Int) :
  Exception("Lidarr HTTP error $status"), LidarrException
```

- [ ] **Step 8: Implement `LidarrSource`, `LidarrClient` and the provider**

`integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrSource.kt`:

```kotlin
package app.muplay.integrations.lidarr

import app.muplay.integrations.IntegrationCredentials

/**
 * Everything this app asks of a Lidarr server, as one interface.
 *
 * A **port**, exactly like `SubsonicSource` in `:core:network`, and for the same single reason: a
 * test needs to make a *specific call* fail at a *specific point* — the status poller must not
 * advance a request's state when the third of four calls fails — and no real Lidarr can be asked
 * to do that on demand. A hand-written fake implementing this interface can, with no mock
 * framework anywhere near the build.
 */
interface LidarrSource {

  /**
   * Whether *something that answers Lidarr's unauthenticated ping* is listening.
   *
   * Never throws: its whole value is being a question that always has an answer, so the
   * configuration screen can distinguish "nothing is there" from "something is there and rejected
   * our key" without a try/catch of its own.
   *
   * **This does not prove the server is Lidarr.** Sonarr, Radarr and Prowlarr serve a
   * byte-identical `{"status":"OK"}` at the same path. [status] is what proves identity.
   */
  suspend fun ping(): Boolean

  /** Identity, version and `urlBase`, authenticated. Throws [LidarrException] on failure. */
  suspend fun status(): LidarrServer
}

/**
 * What `GET /api/v1/system/status` tells a client that matters to it.
 *
 * [appName] is the identity check — the field that separates a Lidarr from the Sonarr whose URL
 * the user pasted by mistake. [urlBase] matters because a proxied install answers unprefixed API
 * paths with a 307; OkHttp follows it, but knowing the real base lets the app store it and stop
 * paying for a redirect on every call.
 */
data class LidarrServer(
  val appName: String,
  val instanceName: String,
  val version: String,
  val urlBase: String,
  val authentication: String,
) {
  /** Whether this really is a Lidarr and not a sibling Servarr application. */
  val isLidarr: Boolean get() = appName.equals("Lidarr", ignoreCase = true)
}

fun interface LidarrSourceFactory {
  fun create(credentials: IntegrationCredentials.Lidarr): LidarrSource
}

/** The production factory: a real [LidarrClient] with its real Retrofit stack. */
object DefaultLidarrSourceFactory : LidarrSourceFactory {
  override fun create(credentials: IntegrationCredentials.Lidarr): LidarrSource =
    LidarrClient(credentials)
}
```

`integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrClient.kt`:

```kotlin
package app.muplay.integrations.lidarr

import app.muplay.integrations.IntegrationCredentials
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * A typed Kotlin client over [LidarrApi].
 *
 * The OkHttp stack carries exactly one interceptor, [LidarrAuthInterceptor]. **No logging
 * interceptor is installed and none may be added**: it would print the `X-Api-Key` header on every
 * request, which is the same secret this app seals into the Android Keystore two modules away.
 */
class LidarrClient(
  private val credentials: IntegrationCredentials.Lidarr,
  private val api: LidarrApi = buildApi(credentials),
) : LidarrSource {

  override suspend fun ping(): Boolean =
    runCatching { api.ping() }
      .map { response -> response.isSuccessful && response.body()?.status.equals("OK", true) }
      .getOrDefault(false)

  override suspend fun status(): LidarrServer {
    val body = call { api.systemStatus() }
    return LidarrServer(
      appName = body.appName.orEmpty(),
      instanceName = body.instanceName.orEmpty(),
      version = body.version.orEmpty(),
      urlBase = body.urlBase.orEmpty(),
      authentication = body.authentication.orEmpty(),
    )
  }

  /**
   * Runs [request] and returns its body only once the response is proven successful.
   *
   * The status-code cascade is ordered by specificity, and the 503 case reads the **body** before
   * deciding: a 503 from Lidarr booting and a 503 from a reverse proxy with no upstream are
   * different facts, and collapsing them would send a user to check their firewall while their
   * container finished starting.
   */
  internal suspend fun <T : Any> call(request: suspend () -> Response<T>): T {
    val response = request()
    if (response.isSuccessful) {
      return response.body() ?: throw LidarrHttpException(response.code())
    }
    val raw = response.errorBody()?.string().orEmpty()
    throw when (response.code()) {
      401 -> LidarrUnauthorizedException()
      400 -> LidarrValidationException(parseValidationFailures(raw))
      503 -> if (isStartingUp(raw)) LidarrStartingUpException() else LidarrHttpException(503)
      else -> LidarrHttpException(response.code())
    }
  }

  private fun isStartingUp(raw: String): Boolean =
    runCatching { json.decodeFromString<StartingUpBody>(raw) }
      .getOrNull()
      ?.errorMessage
      ?.contains("starting up", ignoreCase = true) == true

  /**
   * A 400 body is a bare JSON **array** of FluentValidation failures
   * (`LidarrErrorPipeline.cs`: `STJson.ToJson(validationException.Errors)`), not an object.
   * A body that is neither — a proxy's HTML error page carrying a 400 — yields an empty list
   * rather than a parse failure, so the caller still gets a `LidarrValidationException` it can
   * show rather than a `SerializationException` it cannot.
   */
  private fun parseValidationFailures(raw: String): List<LidarrValidationFailure> =
    runCatching { json.decodeFromString<List<ValidationFailureBody>>(raw) }
      .getOrDefault(emptyList())
      .map { LidarrValidationFailure(it.propertyName, it.errorMessage) }

  internal companion object {

    val json: Json = Json {
      // Lidarr adds fields between versions and omits every null-valued one. Neither may break
      // this client.
      ignoreUnknownKeys = true
      explicitNulls = false
    }

    private fun buildApi(credentials: IntegrationCredentials.Lidarr): LidarrApi {
      val http = OkHttpClient.Builder()
        .addInterceptor(LidarrAuthInterceptor(credentials.apiKey))
        .build()
      return Retrofit.Builder()
        // `IntegrationBaseUrl.value` always ends in `/`, which Retrofit requires: without it,
        // resolving `api/v1/system/status` against `https://host/lidarr` would drop the `lidarr`
        // segment. That guarantee is the type's, not this call site's.
        .baseUrl(credentials.baseUrl.value)
        .client(http)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(LidarrApi::class.java)
    }
  }
}
```

`integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrSourceProvider.kt`:

```kotlin
package app.muplay.integrations.lidarr

import app.muplay.integrations.IntegrationCredentials
import app.muplay.integrations.IntegrationCredentialStore
import app.muplay.integrations.IntegrationService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a [LidarrSource] from whatever is currently configured — or reports that nothing is.
 *
 * **[current] returns `null` rather than throwing, and that is the single most important design
 * decision in this module.** `:core:database`'s `SubsonicSourceProvider` throws
 * `NotConfiguredException` because a MuPlay with no Navidrome is a broken app. A MuPlay with no
 * Lidarr is a *normal* app — it is the state most users are in — so "not configured" is a value
 * every caller must handle, not an exception a caller may forget to catch.
 *
 * The plan's severability contract names the opposite mistake explicitly: a not-configured path
 * that every test configures around is a path no test exercises, and it is the path a real user
 * is permanently on.
 */
@Singleton
class LidarrSourceProvider @Inject constructor(
  private val credentialStore: IntegrationCredentialStore,
  private val factory: LidarrSourceFactory,
) {

  suspend fun current(): LidarrSource? =
    (credentialStore.load(IntegrationService.LIDARR) as? IntegrationCredentials.Lidarr)
      ?.let(factory::create)
}
```

Add the Hilt binding — `integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/di/LidarrModule.kt`:

```kotlin
package app.muplay.integrations.lidarr.di

import app.muplay.integrations.lidarr.DefaultLidarrSourceFactory
import app.muplay.integrations.lidarr.LidarrSourceFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LidarrModule {

  @Provides
  @Singleton
  fun provideLidarrSourceFactory(): LidarrSourceFactory = DefaultLidarrSourceFactory
}
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./gradlew :integrations:lidarr:test`
Expected: PASS.

- [ ] **Step 10: Measure the floor and prove it fires**

```bash
./gradlew :integrations:lidarr:jacocoTestReport
python3 - <<'PY'
import xml.etree.ElementTree as ET
t = ET.parse('integrations/lidarr/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml')
for c in t.getroot().findall('counter'):
    print(c.get('type'), c.get('covered'), '/', int(c.get('covered')) + int(c.get('missed')))
PY
```

Write the measured BRANCH ratio, rounded down, into `coverageFloors`. Then delete the
`a 503 starting-up body is its own failure` test, re-run `jacocoJvmCoverageVerification`, and
confirm it goes red. Restore it.

- [ ] **Step 11: Add the mutation probes**

`ci/mutation-probes.sh`:

```python
LIDARR_INT = "integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrAuthInterceptor.kt"
LIDARR_CLIENT = "integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrClient.kt"
```

```python
    # ---- Plan 7: the credential that must never reach a URL -----------------------------------
    ("integrations/lidarr-api-key-header", LIDARR_INT,
     '.header("X-Api-Key", apiKey)', '.header("X-Api-Key", "constant")',
     "the header carries whichever key the client was given", 1),
    ("integrations/lidarr-accept-json", LIDARR_INT,
     '.header("Accept", "application/json")', '.header("Accept", "*/*")',
     "every request declares that it accepts json", 1),
    # Every mapped field of the handshake, one representative -- the one the identity check reads.
    ("integrations/lidarr-appName", LIDARR_CLIENT,
     "appName = body.appName.orEmpty(),", 'appName = "Lidarr",',
     "status reads the values from the body, not from constants", 1),
    ("integrations/lidarr-urlBase", LIDARR_CLIENT,
     "urlBase = body.urlBase.orEmpty(),", 'urlBase = "",',
     "status reads the values from the body, not from constants", 1),
```

- [ ] **Step 12: Commit**

```bash
git add settings.gradle.kts build.gradle.kts integrations/lidarr ci/mutation-probes.sh
git commit -m "feat(lidarr): the client, with the key in a header and never on a url

Lidarr accepts ?apikey= and its own LoggingMiddleware writes path+querystring into
lidarr.trace.txt, so a query-string key lands in a log file on the user's server. This
client uses X-Api-Key only, and a test asserts across every recorded request that no URL
carries it -- so an endpoint added by a later task cannot quietly reintroduce it.

The handshake is /api/v1/system/status, not /ping: ping is unauthenticated and Sonarr,
Radarr and Prowlarr all serve a byte-identical body at the same path. 401 is mapped to a
message that does not claim to know whether the key was wrong or missing, because Lidarr
returns the same bare 401 for both."
```

---

## Task 5: Lidarr — finding an album, and working out where to put it

**Files:**
- Modify: `integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrApi.kt`
- Modify: `integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrDto.kt`
- Modify: `integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrSource.kt`
- Modify: `integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrClient.kt`
- Create: `integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrAddTargets.kt`
- Create: `integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrLookupTest.kt`
- Create: `integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrAddTargetsTest.kt`
- Create: `integrations/lidarr/src/test/resources/fixtures/lidarr/album-lookup.json`
- Create: `integrations/lidarr/src/test/resources/fixtures/lidarr/rootfolder.json`
- Create: `integrations/lidarr/src/test/resources/fixtures/lidarr/qualityprofile.json`
- Create: `integrations/lidarr/src/test/resources/fixtures/lidarr/metadataprofile.json`
- Modify: `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `LidarrSource`, `LidarrClient.call`, `LidarrClient.json` (Task 4)
- Produces:
  - `data class LidarrAlbumCandidate(foreignAlbumId, title, disambiguation, albumType, releaseDate, remoteCoverUrl, artistName, foreignArtistId, alreadyAdded, raw: JsonObject)`
  - `data class LidarrRootFolder(id, name, path, accessible, freeSpaceBytes, defaultQualityProfileId, defaultMetadataProfileId, defaultMonitorOption, defaultNewItemMonitorOption)`
  - `data class LidarrProfile(id: Int, name: String)`
  - `data class LidarrAddTargets(rootFolderPath, qualityProfileId, metadataProfileId, monitorOption, newItemMonitorOption)`
  - `LidarrAddTargets.Companion.resolve(rootFolder, qualityProfiles, metadataProfiles): LidarrAddTargets?`
  - `LidarrSource` gains `suspend fun lookupAlbums(term: String): List<LidarrAlbumCandidate>`,
    `suspend fun rootFolders(): List<LidarrRootFolder>`,
    `suspend fun qualityProfiles(): List<LidarrProfile>`,
    `suspend fun metadataProfiles(): List<LidarrProfile>`

### The design decision that makes Task 6 possible: keep the raw lookup element

Lidarr's own UI does not build an add payload from scratch. `frontend/src/Utilities/Album/getNewAlbum.js`
takes the **whole object the lookup returned** and decorates it:

```js
function getNewAlbum(album, payload) {
  const { searchForNewAlbum = false } = payload;
  if (!('id' in album.artist) || album.artist.id === 0) { getNewArtist(album.artist, payload); }
  album.addOptions = { searchForNewAlbum };
  album.monitored = true;
  return album;
}
```

Lidarr's own integration test does the same (`src/NzbDrone.Integration.Test/ApiTests/ArtistFixture.cs`:
`var artist = Artist.Lookup("lidarr:…").Single(); artist.QualityProfileId = 1; … Artist.Post(artist)`).

So `LidarrAlbumCandidate` carries `raw: JsonObject` — the verbatim element as it came off the wire —
and Task 6 posts that back with five fields set. **This is not laziness about modelling; it is the
difference between an add that works and one that fails validation on a field nobody knew was
required.** `AlbumResource` and the `ArtistResource` nested inside it have dozens of fields whose
necessity is not documented anywhere, `openapi.json` declares **zero** of them required (it is
Swashbuckle-generated and does not encode FluentValidation), and the only complete statement of
what Lidarr wants is what Lidarr itself sends.

`raw` participates in `equals` because `LidarrAlbumCandidate` is a `data class`. Tests therefore
assert **fields**, never whole objects — which is what this project's field rule asks for anyway.

### Why the root folder is the only thing the user has to choose

`RootFolderResource` (`src/Lidarr.Api.V1/RootFolders/RootFolderResource.cs`) carries
`defaultQualityProfileId`, `defaultMetadataProfileId`, `defaultMonitorOption` and
`defaultNewItemMonitorOption`. A user who picks a root folder has therefore chosen every remaining
required field, and MuPlay needs one picker rather than four.

`accessible`, `freeSpace` and `totalSpace` are also on that resource: an inaccessible root folder is
offered to nobody, because an add against it fails with a validation error whose text is about
paths.

**The fallback branch is real, not defensive padding.** `ValidId` requires a profile id **greater
than zero**, and a root folder created through the API rather than the UI can have zeros in those
default fields. `LidarrAddTargets.resolve` falls back to the first quality/metadata profile the
server reports, and returns `null` only when there is genuinely nothing to fall back to. All three
paths are covered by `LidarrAddTargetsTest`, which is a pure JVM test with no HTTP in it at all.

### Two traps in lookup

**Trap 1 — the album cover field is `remoteCover`, not `remotePoster`.** `ArtistLookupController`
sets `resource.RemotePoster`; `AlbumLookupController` sets `resource.RemoteCover`. A client that
reads `remotePoster` off an album lookup result gets `null` on every row and shows no artwork, with
nothing reported anywhere. `LidarrLookupTest` pins the right one and asserts the wrong one is not
what is read.

**Trap 2 — an MBID term that is not a valid GUID returns an empty array, not an error.**
`SkyHookProxy.IsMbidQuery` accepts `lidarr:`, `lidarrid:` and `mbid:` prefixes, then:
`if (slug.IsNullOrWhiteSpace() || slug.Any(char.IsWhiteSpace) || isValid == false) { return new List<Artist>(); }`.
So "no results" and "you typed a malformed id" are the same response. This client does not send
prefixed terms at all — it sends the user's plain text — and that is a deliberate scope decision
recorded here so nobody adds prefix support without also solving the ambiguity.

**And a cost worth naming:** `/album/lookup` is **not** served from the user's own database. It
proxies to `https://api.lidarr.audio/api/v0.4/…` (`src/NzbDrone.Common/Cloud/LidarrCloudRequestBuilder.cs`),
so it is slow, it can fail while the user's server is perfectly healthy, and it is rate-limited
upstream (the Servarr wiki documents *"enough individual lookups to trigger a 429 rate-limit
response from the metadata server"*). The search field in Task 10 debounces, and this client does
not retry a failed lookup automatically.

- [ ] **Step 1: Capture the lookup and target fixtures from the real instance**

Continue from Task 4 Step 3's container. **A lookup needs outbound internet from the container** —
it proxies to `api.lidarr.audio`.

```bash
KEY=$(docker exec lidarr-capture sed -n 's:.*<ApiKey>\(.*\)</ApiKey>.*:\1:p' /config/config.xml)
F=integrations/lidarr/src/test/resources/fixtures/lidarr
curl -sS -H "X-Api-Key: $KEY" -H 'Accept: application/json' \
  'http://localhost:8686/api/v1/album/lookup?term=kind%20of%20blue' \
  | python3 -m json.tool > "$F/album-lookup.json"

# A root folder must exist before /rootfolder returns anything. Create one, then capture.
docker exec lidarr-capture mkdir -p /music
curl -sS -X POST 'http://localhost:8686/api/v1/rootfolder' -H "X-Api-Key: $KEY" \
  -H 'Content-Type: application/json' \
  -d '{"path":"/music","name":"Music","defaultQualityProfileId":1,"defaultMetadataProfileId":1}'
curl -sS -H "X-Api-Key: $KEY" -H 'Accept: application/json' \
  'http://localhost:8686/api/v1/rootfolder' | python3 -m json.tool > "$F/rootfolder.json"
```

**Record in the task report**, because each is one of this plan's assumptions:
1. Whether the lookup element really carries `remoteCover` (and whether it *also* carries
   `remotePoster` — if it does, Trap 1 is worse than described, not better).
2. Whether `releaseDate` is a full ISO-8601 timestamp or a bare date, and whether it can be absent.
3. Whether the nested `artist` object on an album-lookup element carries `foreignArtistId` and
   `artistName` (this plan depends on both) and what its `id` is for an artist not yet added.
4. What `POST /api/v1/rootfolder` actually requires — the body above is a guess about a *setup*
   call this app never makes, and if it fails, create the root folder through the Lidarr UI instead
   and say so. **Nothing in MuPlay ever creates a root folder**; this is fixture capture only.
5. The exact status and body of a lookup while the container has no internet (this is the
   `SkyHookException` path, and this plan does **not** know what HTTP status it produces — see
   **Research provenance → could not establish**).

**Then scrub before committing:** `! grep -rl "$KEY" integrations/lidarr/src/test/resources/`.

- [ ] **Step 2: Write the failing lookup test**

`integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrLookupTest.kt`:

```kotlin
package app.muplay.integrations.lidarr

import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentials
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LidarrLookupTest {

  @StartStop private val server = MockWebServer()

  private fun client(): LidarrClient {
    val url = IntegrationBaseUrl.parse(server.url("/").toString(), CleartextPolicy.Allowed)
    return LidarrClient(IntegrationCredentials.Lidarr((url as BaseUrlResult.Valid).url, "k"))
  }

  private fun json(body: String, code: Int = 200) =
    MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

  /** Two terms, so a `term` parameter that was dropped or hardcoded fails. */
  @Test
  fun `the lookup sends whichever term it is given, url-encoded, to album slash lookup`() = runTest {
    server.enqueue(json("[]"))
    client().lookupAlbums("kind of blue")
    val first = nextRequest().url
    assertThat(first.encodedPath).isEqualTo("/api/v1/album/lookup")
    assertThat(first.queryParameter("term")).isEqualTo("kind of blue")

    server.enqueue(json("[]"))
    client().lookupAlbums("bitches brew")
    assertThat(nextRequest().url.queryParameter("term")).isEqualTo("bitches brew")
  }

  @Test
  fun `every candidate field is read from its own element`() = runTest {
    // A two-element body, so every field is observed at two values in one assertion each. A
    // mapper that hardcoded any field would produce a list with a repeated value and fail.
    server.enqueue(
      json(
        """
        [
          {"foreignAlbumId":"mbid-a","title":"Kind of Blue","disambiguation":"1997 remaster",
           "albumType":"Album","releaseDate":"1959-08-17T00:00:00Z","remoteCover":"https://img/a.jpg",
           "id":0,"artist":{"foreignArtistId":"art-a","artistName":"Miles Davis","id":0}},
          {"foreignAlbumId":"mbid-b","title":"Blue Train","albumType":"EP",
           "remoteCover":"https://img/b.jpg","id":12,
           "artist":{"foreignArtistId":"art-b","artistName":"John Coltrane","id":5}}
        ]
        """.trimIndent(),
      ),
    )

    val candidates = client().lookupAlbums("blue")

    // Exact mapped lists, in order. `containsExactly` proves order as well as content, and order
    // here is Lidarr's relevance ranking -- reordering it silently would put the wrong album first.
    assertThat(candidates.map { it.foreignAlbumId }).containsExactly("mbid-a", "mbid-b")
    assertThat(candidates.map { it.title }).containsExactly("Kind of Blue", "Blue Train")
    assertThat(candidates.map { it.disambiguation }).containsExactly("1997 remaster", null)
    assertThat(candidates.map { it.albumType }).containsExactly("Album", "EP")
    assertThat(candidates.map { it.releaseDate }).containsExactly("1959-08-17T00:00:00Z", null)
    assertThat(candidates.map { it.remoteCoverUrl })
      .containsExactly("https://img/a.jpg", "https://img/b.jpg")
    assertThat(candidates.map { it.artistName }).containsExactly("Miles Davis", "John Coltrane")
    assertThat(candidates.map { it.foreignArtistId }).containsExactly("art-a", "art-b")
    // `id == 0` means "not in this Lidarr's database yet". Both values observed.
    assertThat(candidates.map { it.alreadyAdded }).containsExactly(false, true)
  }

  /**
   * `AlbumLookupController` sets `resource.RemoteCover`; it is only `ArtistLookupController` that
   * sets `RemotePoster`. A client reading `remotePoster` off an album gets null on every row and
   * shows no artwork, silently.
   */
  @Test
  fun `the cover comes from remoteCover and not from remotePoster`() = runTest {
    server.enqueue(
      json(
        """
        [{"foreignAlbumId":"m","title":"t","remoteCover":"https://right.jpg",
          "remotePoster":"https://wrong.jpg","artist":{"foreignArtistId":"a","artistName":"n"}}]
        """.trimIndent(),
      ),
    )

    assertThat(client().lookupAlbums("t").single().remoteCoverUrl).isEqualTo("https://right.jpg")
  }

  @Test
  fun `the raw element is kept verbatim for the add`() = runTest {
    server.enqueue(
      json(
        """[{"foreignAlbumId":"m","title":"t","someFieldThisClientDoesNotModel":"keep me",
             "artist":{"foreignArtistId":"a","artistName":"n"}}]""",
      ),
    )

    val raw = client().lookupAlbums("t").single().raw

    // Task 6 posts this object back with five fields set, exactly as Lidarr's own UI does. A
    // field this client does not model must survive the round trip -- that is the whole reason
    // `raw` exists, and dropping it is how an add starts failing validation on a field nobody
    // knew was required.
    assertThat(raw["someFieldThisClientDoesNotModel"]?.toString()).isEqualTo("\"keep me\"")
    assertThat(raw["artist"]).isNotNull()
  }

  @Test
  fun `an element with no usable identity is skipped rather than crashing the list`() = runTest {
    // `foreignAlbumId` or the nested artist's `foreignArtistId` missing makes the row unusable
    // for an add. Dropping it keeps the other results usable; failing the parse loses all of them.
    server.enqueue(
      json(
        """
        [{"title":"no id"},
         {"foreignAlbumId":"m","title":"ok","artist":{"foreignArtistId":"a","artistName":"n"}},
         {"foreignAlbumId":"n","title":"no artist id","artist":{"artistName":"n"}}]
        """.trimIndent(),
      ),
    )

    assertThat(client().lookupAlbums("x").map { it.foreignAlbumId }).containsExactly("m")
  }

  @Test
  fun `an empty result is an empty list, not a failure`() = runTest {
    server.enqueue(json("[]"))
    assertThat(client().lookupAlbums("nothing at all")).isEmpty()
  }

  @Test
  fun `root folders carry the defaults an add needs`() = runTest {
    server.enqueue(
      json(
        """
        [{"id":1,"name":"Music","path":"/music","accessible":true,"freeSpace":123,
          "defaultQualityProfileId":2,"defaultMetadataProfileId":3,
          "defaultMonitorOption":"all","defaultNewItemMonitorOption":"none"},
         {"id":4,"name":"Archive","path":"/archive","accessible":false,
          "defaultQualityProfileId":5,"defaultMetadataProfileId":6,
          "defaultMonitorOption":"future","defaultNewItemMonitorOption":"all"}]
        """.trimIndent(),
      ),
    )

    val folders = client().rootFolders()

    assertThat(nextRequest().url.encodedPath).isEqualTo("/api/v1/rootfolder")
    // Every field at two values.
    assertThat(folders.map { it.id }).containsExactly(1, 4)
    assertThat(folders.map { it.name }).containsExactly("Music", "Archive")
    assertThat(folders.map { it.path }).containsExactly("/music", "/archive")
    assertThat(folders.map { it.accessible }).containsExactly(true, false)
    assertThat(folders.map { it.freeSpaceBytes }).containsExactly(123L, null)
    assertThat(folders.map { it.defaultQualityProfileId }).containsExactly(2, 5)
    assertThat(folders.map { it.defaultMetadataProfileId }).containsExactly(3, 6)
    assertThat(folders.map { it.defaultMonitorOption }).containsExactly("all", "future")
    assertThat(folders.map { it.defaultNewItemMonitorOption }).containsExactly("none", "all")
  }

  @Test
  fun `quality and metadata profiles are two different endpoints and both are read`() = runTest {
    server.enqueue(json("""[{"id":1,"name":"Any"},{"id":2,"name":"Lossless"}]"""))
    val quality = client().qualityProfiles()
    assertThat(nextRequest().url.encodedPath).isEqualTo("/api/v1/qualityprofile")
    assertThat(quality.map { it.id }).containsExactly(1, 2)
    assertThat(quality.map { it.name }).containsExactly("Any", "Lossless")

    server.enqueue(json("""[{"id":7,"name":"Standard"}]"""))
    val metadata = client().metadataProfiles()
    // Two observations of the *path*: a client that called one endpoint for both would fail here.
    assertThat(nextRequest().url.encodedPath).isEqualTo("/api/v1/metadataprofile")
    assertThat(metadata.map { it.id }).containsExactly(7)
  }
}
```

- [ ] **Step 3: Write the failing add-targets test (pure JVM, no HTTP)**

`integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrAddTargetsTest.kt`:

```kotlin
package app.muplay.integrations.lidarr

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Where an added album goes, decided as a pure function.
 *
 * Separate from the HTTP client on purpose: the whole cascade — root-folder defaults, the
 * greater-than-zero requirement, the fallback to the first profile, the give-up case — is real
 * logic with real branches, and it is Tier-1 enforceable at 100% branch coverage with no server
 * in sight. Exactly the argument `StreamRetryPolicy` and `StreamFormat.forSuffix` make in Plan 3.
 */
class LidarrAddTargetsTest {

  private fun folder(
    path: String = "/music",
    quality: Int = 2,
    metadata: Int = 3,
    monitor: String = "all",
    newItems: String = "none",
    accessible: Boolean = true,
  ) = LidarrRootFolder(
    id = 1, name = "Music", path = path, accessible = accessible, freeSpaceBytes = null,
    defaultQualityProfileId = quality, defaultMetadataProfileId = metadata,
    defaultMonitorOption = monitor, defaultNewItemMonitorOption = newItems,
  )

  private val profiles = listOf(LidarrProfile(9, "Any"), LidarrProfile(10, "Lossless"))

  @Test
  fun `the root folder's own defaults win when they are usable`() {
    val targets = LidarrAddTargets.resolve(folder(), profiles, profiles)

    assertThat(targets).isNotNull
    assertThat(targets!!.rootFolderPath).isEqualTo("/music")
    assertThat(targets.qualityProfileId).isEqualTo(2)
    assertThat(targets.metadataProfileId).isEqualTo(3)
    assertThat(targets.monitorOption).isEqualTo("all")
    assertThat(targets.newItemMonitorOption).isEqualTo("none")
  }

  @Test
  fun `every field comes from the folder it was given, not from a constant`() {
    // The second observation of the whole cascade.
    val targets = LidarrAddTargets.resolve(
      folder(path = "/archive", quality = 20, metadata = 30, monitor = "future", newItems = "all"),
      profiles, profiles,
    )!!

    assertThat(targets.rootFolderPath).isEqualTo("/archive")
    assertThat(targets.qualityProfileId).isEqualTo(20)
    assertThat(targets.metadataProfileId).isEqualTo(30)
    assertThat(targets.monitorOption).isEqualTo("future")
    assertThat(targets.newItemMonitorOption).isEqualTo("all")
  }

  /**
   * `ValidId` on the controller requires a profile id **greater than zero**, and a root folder
   * created through the API rather than the UI can carry zeros. Falling back to the first profile
   * the server reports is what turns a 400 nobody can act on into a working add.
   */
  @Test
  fun `a zero default falls back to the first profile the server reports`() {
    val targets = LidarrAddTargets.resolve(folder(quality = 0, metadata = 0), profiles, profiles)!!

    assertThat(targets.qualityProfileId).isEqualTo(9)
    assertThat(targets.metadataProfileId).isEqualTo(9)
  }

  @Test
  fun `each profile falls back independently of the other`() {
    // Without this, a `resolve` that fell back for both whenever either was zero would pass the
    // test above.
    assertThat(LidarrAddTargets.resolve(folder(quality = 0, metadata = 3), profiles, profiles)!!)
      .satisfies({ assertThat(it.qualityProfileId).isEqualTo(9) })
      .satisfies({ assertThat(it.metadataProfileId).isEqualTo(3) })
  }

  @Test
  fun `there is no answer when a needed profile is zero and no profile exists to fall back to`() {
    // Null, not a fabricated id 1. An add against a profile id that does not exist fails with a
    // validation message about profiles, which is a worse experience than being told up front.
    assertThat(LidarrAddTargets.resolve(folder(quality = 0), emptyList(), profiles)).isNull()
    assertThat(LidarrAddTargets.resolve(folder(metadata = 0), profiles, emptyList())).isNull()
  }

  @Test
  fun `an inaccessible root folder has no answer`() {
    // Offering it would produce an add that fails on a path validation the user cannot interpret.
    assertThat(LidarrAddTargets.resolve(folder(accessible = false), profiles, profiles)).isNull()
  }

  @Test
  fun `a root folder with a blank path has no answer`() {
    assertThat(LidarrAddTargets.resolve(folder(path = "  "), profiles, profiles)).isNull()
  }

  @Test
  fun `a blank monitor default becomes all rather than an empty string on the wire`() {
    // `MonitorTypes` has no empty member; sending "" would be a 400. "all" is Lidarr's own UI
    // default (frontend/src/Utilities/Artist/monitorOptions.js lists it first).
    val targets = LidarrAddTargets.resolve(folder(monitor = "", newItems = ""), profiles, profiles)!!

    assertThat(targets.monitorOption).isEqualTo("all")
    assertThat(targets.newItemMonitorOption).isEqualTo("all")
  }
}
```

- [ ] **Step 4: Run both to verify they fail**

Run: `./gradlew :integrations:lidarr:test`
Expected: FAIL — `Unresolved reference: lookupAlbums`, `LidarrAddTargets`.

- [ ] **Step 5: Extend the DTOs and the Retrofit surface**

Append to `LidarrDto.kt`:

```kotlin
/**
 * A root folder, with the four defaults that let one picker satisfy every required add field.
 *
 * `freeSpace` and `totalSpace` are `long?` in the resource and are genuinely absent for an
 * inaccessible folder, which is why they are nullable here rather than defaulted to zero — "zero
 * bytes free" and "we do not know" are different things to show a user.
 */
@Serializable
internal data class RootFolderBody(
  val id: Int = 0,
  val name: String? = null,
  val path: String? = null,
  val accessible: Boolean = false,
  val freeSpace: Long? = null,
  val defaultQualityProfileId: Int = 0,
  val defaultMetadataProfileId: Int = 0,
  val defaultMonitorOption: String? = null,
  val defaultNewItemMonitorOption: String? = null,
)

/** Quality and metadata profiles share the only two fields an add needs. */
@Serializable
internal data class ProfileBody(val id: Int = 0, val name: String? = null)
```

`LidarrApi.kt` gains:

```kotlin
  /**
   * Returns raw `JsonElement`s, not a typed resource.
   *
   * Deliberate: Task 6's add posts the lookup element **back** with five fields set, which is what
   * Lidarr's own UI does, and a typed round trip would silently drop every field this client does
   * not model. The typed view is built beside it by `LidarrClient.lookupAlbums`.
   */
  @GET("api/v1/album/lookup")
  suspend fun albumLookup(@Query("term") term: String): Response<List<JsonElement>>

  @GET("api/v1/rootfolder")
  suspend fun rootFolders(): Response<List<RootFolderBody>>

  @GET("api/v1/qualityprofile")
  suspend fun qualityProfiles(): Response<List<ProfileBody>>

  @GET("api/v1/metadataprofile")
  suspend fun metadataProfiles(): Response<List<ProfileBody>>
```

with `import kotlinx.serialization.json.JsonElement` and `import retrofit2.http.Query`.

- [ ] **Step 6: Implement the models, `LidarrAddTargets`, and the client methods**

`integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrAddTargets.kt`:

```kotlin
package app.muplay.integrations.lidarr

/**
 * Where an album Lidarr is asked to add will be filed, and under which profiles.
 *
 * Every field here is one Lidarr's `AlbumController` validators require on the nested artist:
 * `qualityProfileId` and `metadataProfileId` must both be **greater than zero** and must exist,
 * and exactly one of `rootFolderPath`/`path` must be a valid path.
 */
data class LidarrAddTargets(
  val rootFolderPath: String,
  val qualityProfileId: Int,
  val metadataProfileId: Int,
  val monitorOption: String,
  val newItemMonitorOption: String,
) {
  companion object {

    /** `MonitorTypes.All`, and Lidarr's own UI's first option. Used when a folder names none. */
    private const val DEFAULT_MONITOR = "all"

    /**
     * Resolves [rootFolder]'s defaults into a complete set of add targets, or `null` when there is
     * no honest answer.
     *
     * `null` rather than a fabricated id: an add against a profile that does not exist fails with
     * a validation message about profiles, which is strictly worse for a user than being told
     * before they press the button.
     */
    fun resolve(
      rootFolder: LidarrRootFolder,
      qualityProfiles: List<LidarrProfile>,
      metadataProfiles: List<LidarrProfile>,
    ): LidarrAddTargets? {
      if (!rootFolder.accessible || rootFolder.path.isBlank()) return null
      val quality = usableId(rootFolder.defaultQualityProfileId, qualityProfiles) ?: return null
      val metadata = usableId(rootFolder.defaultMetadataProfileId, metadataProfiles) ?: return null
      return LidarrAddTargets(
        rootFolderPath = rootFolder.path,
        qualityProfileId = quality,
        metadataProfileId = metadata,
        monitorOption = rootFolder.defaultMonitorOption.ifBlank { DEFAULT_MONITOR },
        newItemMonitorOption = rootFolder.defaultNewItemMonitorOption.ifBlank { DEFAULT_MONITOR },
      )
    }

    /** [preferred] if it is a real id, else the first profile there is, else `null`. */
    private fun usableId(preferred: Int, profiles: List<LidarrProfile>): Int? =
      if (preferred > 0) preferred else profiles.firstOrNull()?.id
  }
}
```

Append to `LidarrSource.kt`:

```kotlin
/**
 * One album Lidarr's metadata lookup found.
 *
 * [raw] is the element **exactly as it came off the wire**, and Task 6 posts it back with five
 * fields set — the same thing Lidarr's own UI does (`frontend/src/Utilities/Album/getNewAlbum.js`).
 * Rebuilding a payload from the typed fields below would drop every field this client does not
 * model, and `openapi.json` declares none of them required because it is Swashbuckle-generated and
 * does not encode Lidarr's FluentValidation rules. The only complete statement of what Lidarr
 * wants is what Lidarr sends.
 *
 * [alreadyAdded] is `id != 0`: a lookup element for an album already in this Lidarr's database
 * carries its real database id, and one that is not carries `0`.
 */
data class LidarrAlbumCandidate(
  val foreignAlbumId: String,
  val title: String,
  val disambiguation: String?,
  val albumType: String?,
  /** The raw string Lidarr sent. Not parsed: this app has no datetime dependency and shows it as-is. */
  val releaseDate: String?,
  /** From `remoteCover`. **Not** `remotePoster`, which only artist lookups carry. */
  val remoteCoverUrl: String?,
  val artistName: String,
  val foreignArtistId: String,
  val alreadyAdded: Boolean,
  val raw: kotlinx.serialization.json.JsonObject,
)

data class LidarrRootFolder(
  val id: Int,
  val name: String,
  val path: String,
  val accessible: Boolean,
  val freeSpaceBytes: Long?,
  val defaultQualityProfileId: Int,
  val defaultMetadataProfileId: Int,
  val defaultMonitorOption: String,
  val defaultNewItemMonitorOption: String,
)

data class LidarrProfile(val id: Int, val name: String)
```

and add to the `LidarrSource` interface:

```kotlin
  /**
   * Albums matching [term], from Lidarr's metadata lookup.
   *
   * **Not served from the user's own database.** It proxies to `api.lidarr.audio`, so it is slow,
   * can fail while the user's own server is healthy, and is rate-limited upstream. Callers debounce
   * and do not retry automatically.
   */
  suspend fun lookupAlbums(term: String): List<LidarrAlbumCandidate>

  suspend fun rootFolders(): List<LidarrRootFolder>

  suspend fun qualityProfiles(): List<LidarrProfile>

  suspend fun metadataProfiles(): List<LidarrProfile>
```

`LidarrClient.kt` gains:

```kotlin
  override suspend fun lookupAlbums(term: String): List<LidarrAlbumCandidate> =
    call { api.albumLookup(term) }.mapNotNull(::toCandidate)

  override suspend fun rootFolders(): List<LidarrRootFolder> =
    call { api.rootFolders() }.map { body ->
      LidarrRootFolder(
        id = body.id,
        // A folder with no name is shown by its path rather than by a blank row.
        name = body.name?.takeIf { it.isNotBlank() } ?: body.path.orEmpty(),
        path = body.path.orEmpty(),
        accessible = body.accessible,
        freeSpaceBytes = body.freeSpace,
        defaultQualityProfileId = body.defaultQualityProfileId,
        defaultMetadataProfileId = body.defaultMetadataProfileId,
        defaultMonitorOption = body.defaultMonitorOption.orEmpty(),
        defaultNewItemMonitorOption = body.defaultNewItemMonitorOption.orEmpty(),
      )
    }

  override suspend fun qualityProfiles(): List<LidarrProfile> =
    call { api.qualityProfiles() }.map { LidarrProfile(it.id, it.name.orEmpty()) }

  override suspend fun metadataProfiles(): List<LidarrProfile> =
    call { api.metadataProfiles() }.map { LidarrProfile(it.id, it.name.orEmpty()) }

  /**
   * A typed view over one lookup element, or `null` when the element cannot be used for an add.
   *
   * An element with no `foreignAlbumId`, or whose nested artist has no `foreignArtistId`, is
   * unusable: both are required by `AlbumController`'s validators. Dropping such a row keeps every
   * other result usable, where failing the whole parse would lose all of them.
   */
  private fun toCandidate(element: JsonElement): LidarrAlbumCandidate? {
    val obj = element as? JsonObject ?: return null
    val artist = obj["artist"] as? JsonObject
    val foreignAlbumId = obj.string("foreignAlbumId") ?: return null
    val foreignArtistId = artist?.string("foreignArtistId") ?: return null
    return LidarrAlbumCandidate(
      foreignAlbumId = foreignAlbumId,
      title = obj.string("title").orEmpty(),
      disambiguation = obj.string("disambiguation"),
      albumType = obj.string("albumType"),
      releaseDate = obj.string("releaseDate"),
      // `remoteCover`, never `remotePoster`: AlbumLookupController sets the former and only
      // ArtistLookupController sets the latter. Reading the wrong one yields null on every row.
      remoteCoverUrl = obj.string("remoteCover"),
      artistName = artist.string("artistName").orEmpty(),
      foreignArtistId = foreignArtistId,
      alreadyAdded = (obj["id"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { it != 0 } ?: false,
      raw = obj,
    )
  }

  private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
```

with imports `kotlinx.serialization.json.JsonElement`, `JsonObject`, `JsonPrimitive`.

- [ ] **Step 7: Run to verify they pass, then measure and probe**

Run: `./gradlew :integrations:lidarr:test`
Expected: PASS.

Re-measure the module's BRANCH floor (Task 4 Step 10's command) and update `coverageFloors`.

`ci/mutation-probes.sh`:

```python
LIDARR_TARGETS = "integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrAddTargets.kt"
```

```python
    ("integrations/lidarr-lookup-term", LIDARR_CLIENT,
     "call { api.albumLookup(term) }", 'call { api.albumLookup("kind of blue") }',
     "the lookup sends whichever term it is given, url-encoded, to album slash lookup", 1),
    # The trap that produces no artwork and no error.
    ("integrations/lidarr-remoteCover", LIDARR_CLIENT,
     'remoteCoverUrl = obj.string("remoteCover"),', 'remoteCoverUrl = obj.string("remotePoster"),',
     "the cover comes from remoteCover and not from remotePoster", 1),
    ("integrations/lidarr-targets-quality-passthrough", LIDARR_TARGETS,
     "qualityProfileId = quality,", "qualityProfileId = 1,",
     "every field comes from the folder it was given, not from a constant", 1),
    ("integrations/lidarr-targets-rootpath-passthrough", LIDARR_TARGETS,
     "rootFolderPath = rootFolder.path,", 'rootFolderPath = "/music",',
     "every field comes from the folder it was given, not from a constant", 1),
```

- [ ] **Step 8: Commit**

```bash
git add integrations/lidarr build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(lidarr): album lookup, root folders, and where an add goes

The lookup keeps each element's raw JSON as well as a typed view, because Lidarr's own UI
posts the lookup object back decorated rather than building a payload -- and openapi.json
declares zero required fields, so what Lidarr sends is the only complete statement of what
it wants.

Two traps pinned: the album cover is remoteCover, not remotePoster (only artist lookups
carry that one, so reading it yields null on every row with nothing reported), and a root
folder carries the quality/metadata/monitor defaults, so one picker satisfies every required
add field. The zero-default fallback is a real branch -- ValidId requires an id above zero."
```

---

## Task 6: Lidarr — submitting the add, and proving the body carried the right identifier

**Files:**
- Modify: `LidarrApi.kt`, `LidarrSource.kt`, `LidarrClient.kt`
- Create: `integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrAddPayload.kt`
- Create: `integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrAddPayloadTest.kt`
- Create: `integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrSubmitTest.kt`
- Modify: `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `LidarrAlbumCandidate.raw`, `LidarrAddTargets` (Task 5)
- Produces:
  - `object LidarrAddPayload` with
    `build(candidate: LidarrAlbumCandidate, targets: LidarrAddTargets, searchNow: Boolean): JsonObject`
  - `sealed interface LidarrAddOutcome` with `data class Added(val albumId: Int)`,
    `data object AlreadyAdded`, `data class Rejected(val failures: List<LidarrValidationFailure>)`
  - `LidarrSource` gains
    `suspend fun submitAlbum(candidate, targets, searchNow: Boolean): LidarrAddOutcome` and
    `suspend fun findAddedAlbumId(foreignAlbumId: String): Int?`

### This is the task the whole plan exists to get right

Spec §8 says, of Lidarr: *"`POST /api/v1/album` payload is unverified against a live instance."*
That sentence is the reason this task is written the way it is. Here is what the payload actually
is, and every claim carries its source.

**Required by `AlbumController`'s `PostValidator` (`src/Lidarr.Api.V1/Albums/AlbumController.cs`):**

```csharp
PostValidator.RuleFor(s => s.ForeignAlbumId).NotEmpty().SetValidator(albumExistsValidator);
PostValidator.RuleFor(s => s.Artist).NotNull();
PostValidator.RuleFor(s => s.Artist.ForeignArtistId).NotEmpty().When(s => s.Artist != null);
PostValidator.RuleFor(s => s.Artist.QualityProfileId).Cascade(CascadeMode.Stop)
    .ValidId().SetValidator(qualityProfileExistsValidator).When(s => s.Artist != null);
PostValidator.RuleFor(s => s.Artist.MetadataProfileId).Cascade(CascadeMode.Stop)
    .ValidId().SetValidator(metadataProfileExistsValidator).When(s => s.Artist != null);
PostValidator.RuleFor(s => s.Artist.RootFolderPath)
    .IsValidPath().SetValidator(rootFolderExistsValidator)
    .When(s => s.Artist != null && s.Artist.Path.IsNullOrWhiteSpace());
```

So: `foreignAlbumId`, plus a **nested `artist`** carrying `foreignArtistId`, `qualityProfileId`
(> 0, exists), `metadataProfileId` (> 0, exists) and — when the artist has no `path` — a valid
`rootFolderPath`. **The nested artist's requirements apply whether or not the artist already
exists**: the validators are unconditional on the object's presence, not on the artist being new.

**`openapi.json` declares none of this.** It is Swashbuckle-generated, does not encode
FluentValidation, lists **zero** required fields anywhere, and documents the POST response as
`200` when the code returns `201` (`RestController.Created` → `CreatedAtAction`). A client written
from the published spec gets this task wrong in two places. That is why this plan does not vendor
it as an oracle the way `:core:testing` vendors the OpenSubsonic one — an oracle that is wrong
about the crux is worse than no oracle, and this project's own spec §10 already records that
principle for the two Navidrome divergences.

### Three traps, in descending order of how quietly they fail

**Trap 1 — `searchForMissingAlbums` silently cancels `searchForNewAlbum`.**
`src/NzbDrone.Core/Music/Services/AddAlbumService.cs`:

```csharp
// if adding and searching for artist, don't trigger album specific search
if (artist.AddOptions?.SearchForMissingAlbums ?? false)
{ album.AddOptions.SearchForNewAlbum = false; }
```

A payload that sets both gets a 201, a monitored album, and **no search** — so nothing is ever
downloaded and nothing anywhere says why. There is an open upstream issue on exactly this
(Lidarr #5012). This client always sends `artist.addOptions.searchForMissingAlbums = false`, and
`LidarrAddPayloadTest` asserts it — the single most important assertion in this task.

**Trap 2 — `AddAlbumOptions` has no `monitor` and no `monitored` field.**
`src/NzbDrone.Core/Music/Model/AddAlbumOptions.cs` is `{ AddType, SearchForNewAlbum }`. The
`monitor` enum belongs to the **artist's** `addOptions`
(`AddArtistOptions : MonitoringOptions`). Putting `monitor` on the album's `addOptions` is silently
ignored — `PropertyNameCaseInsensitive` binding drops unknown members without complaint. And
`addType` is overwritten server-side to `Manual` on every album POST, so sending it is noise.

**Trap 3 — a duplicate add is a `400`, not a `409`, and is only identifiable by its message.**
`AlbumExistsValidator`'s template is the literal string `"This album has already been added."`
There is no code, no field, nothing structural. `LidarrAddOutcome.AlreadyAdded` is derived by
matching that message, which is fragile and is documented as fragile — if a Lidarr release rewords
it, this degrades to `Rejected` showing the user the raw validation text, which is the safe
direction.

### The assertion that this plan's brief singles out

> *"a test asserting a request was **submitted** rather than that its body carried the right
> identifier"*

`assertThat(server.requestCount).isEqualTo(1)` is satisfied by a client that POSTs `{}`.
**Every submit test below reads the recorded request's body, parses it as JSON, and asserts the
specific field — at two different values.** `LidarrAddPayloadTest` does the same on the payload
builder without a server at all, which is where the exhaustive per-field coverage lives.

- [ ] **Step 1: Write the failing payload test (pure JVM, no HTTP)**

`integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrAddPayloadTest.kt`:

```kotlin
package app.muplay.integrations.lidarr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The exact JSON this client asks Lidarr to add.
 *
 * A pure test over a pure function, with no server in it, because this is where every field
 * belongs under a microscope. The plan's brief names the failure this guards against by name: *a
 * test asserting a request was submitted rather than that its body carried the right identifier.*
 */
class LidarrAddPayloadTest {

  private val json = Json

  private fun candidate(
    albumId: String = "album-mbid",
    artistId: String = "artist-mbid",
    extra: String = "",
  ): LidarrAlbumCandidate {
    val raw = json.parseToJsonElement(
      """
      {"foreignAlbumId":"$albumId","title":"An album","monitored":false,
       "unmodelledAlbumField":"survive me"$extra,
       "artist":{"foreignArtistId":"$artistId","artistName":"An artist","id":0,
                 "unmodelledArtistField":"survive me too"}}
      """.trimIndent(),
    ).jsonObject
    return LidarrAlbumCandidate(
      foreignAlbumId = albumId, title = "An album", disambiguation = null, albumType = null,
      releaseDate = null, remoteCoverUrl = null, artistName = "An artist",
      foreignArtistId = artistId, alreadyAdded = false, raw = raw,
    )
  }

  private fun targets(
    path: String = "/music", quality: Int = 2, metadata: Int = 3,
    monitor: String = "all", newItems: String = "none",
  ) = LidarrAddTargets(path, quality, metadata, monitor, newItems)

  private fun build(
    candidate: LidarrAlbumCandidate = candidate(),
    targets: LidarrAddTargets = targets(),
    searchNow: Boolean = true,
  ): JsonObject = LidarrAddPayload.build(candidate, targets, searchNow)

  @Test
  fun `the album identifier on the body is the one that was asked for`() {
    // Two observations. A payload builder that hardcoded the id passes neither this nor any
    // downstream test, and this is the exact assertion the plan's brief singles out.
    assertThat(build(candidate(albumId = "mbid-a"))["foreignAlbumId"]!!.jsonPrimitive.content)
      .isEqualTo("mbid-a")
    assertThat(build(candidate(albumId = "mbid-b"))["foreignAlbumId"]!!.jsonPrimitive.content)
      .isEqualTo("mbid-b")
  }

  @Test
  fun `the nested artist identifier is the one that was asked for`() {
    assertThat(build(candidate(artistId = "art-a"))["artist"]!!.jsonObject["foreignArtistId"]!!
      .jsonPrimitive.content).isEqualTo("art-a")
    assertThat(build(candidate(artistId = "art-b"))["artist"]!!.jsonObject["foreignArtistId"]!!
      .jsonPrimitive.content).isEqualTo("art-b")
  }

  @Test
  fun `every field the lookup sent that this client does not model survives`() {
    // The reason `raw` exists. `openapi.json` declares no field required, so the only complete
    // statement of what Lidarr wants is what Lidarr sent -- and a payload rebuilt from typed
    // fields silently drops the rest.
    val body = build()

    assertThat(body["unmodelledAlbumField"]!!.jsonPrimitive.content).isEqualTo("survive me")
    assertThat(body["artist"]!!.jsonObject["unmodelledArtistField"]!!.jsonPrimitive.content)
      .isEqualTo("survive me too")
  }

  @Test
  fun `the three add targets are written onto the nested artist`() {
    // Two values each, so none of the three can be a constant.
    val a = build(targets = targets(path = "/music", quality = 2, metadata = 3))["artist"]!!.jsonObject
    val b = build(targets = targets(path = "/archive", quality = 20, metadata = 30))["artist"]!!.jsonObject

    assertThat(a["rootFolderPath"]!!.jsonPrimitive.content).isEqualTo("/music")
    assertThat(b["rootFolderPath"]!!.jsonPrimitive.content).isEqualTo("/archive")
    assertThat(a["qualityProfileId"]!!.jsonPrimitive.int).isEqualTo(2)
    assertThat(b["qualityProfileId"]!!.jsonPrimitive.int).isEqualTo(20)
    assertThat(a["metadataProfileId"]!!.jsonPrimitive.int).isEqualTo(3)
    assertThat(b["metadataProfileId"]!!.jsonPrimitive.int).isEqualTo(30)
  }

  @Test
  fun `both monitored flags are set, because an unmonitored album is never fetched`() {
    val body = build()

    assertThat(body["monitored"]!!.jsonPrimitive.boolean).isTrue()
    assertThat(body["artist"]!!.jsonObject["monitored"]!!.jsonPrimitive.boolean).isTrue()
    // ...and the lookup element's own `monitored: false` was overwritten, not merged around.
    assertThat(build(candidate())["monitored"]!!.jsonPrimitive.boolean).isTrue()
  }

  @Test
  fun `the monitor options come from the targets, on the artist and not on the album`() {
    val a = build(targets = targets(monitor = "all", newItems = "none"))["artist"]!!.jsonObject
    val b = build(targets = targets(monitor = "first", newItems = "all"))["artist"]!!.jsonObject

    assertThat(a["addOptions"]!!.jsonObject["monitor"]!!.jsonPrimitive.content).isEqualTo("all")
    assertThat(b["addOptions"]!!.jsonObject["monitor"]!!.jsonPrimitive.content).isEqualTo("first")
    assertThat(a["monitorNewItems"]!!.jsonPrimitive.content).isEqualTo("none")
    assertThat(b["monitorNewItems"]!!.jsonPrimitive.content).isEqualTo("all")
  }

  /**
   * **Trap 2.** `AddAlbumOptions` is `{ AddType, SearchForNewAlbum }` — there is no `monitor` and
   * no `monitored` on it. Lidarr binds JSON case-insensitively and drops unknown members without
   * complaint, so a `monitor` placed here would be accepted and ignored, which is the quietest
   * possible way to be wrong.
   */
  @Test
  fun `the album's addOptions carries only searchForNewAlbum`() {
    val addOptions = build()["addOptions"]!!.jsonObject

    assertThat(addOptions.keys).containsExactly("searchForNewAlbum")
  }

  @Test
  fun `searchForNewAlbum is whatever the caller asked for`() {
    assertThat(build(searchNow = true)["addOptions"]!!.jsonObject["searchForNewAlbum"]!!
      .jsonPrimitive.boolean).isTrue()
    assertThat(build(searchNow = false)["addOptions"]!!.jsonObject["searchForNewAlbum"]!!
      .jsonPrimitive.boolean).isFalse()
  }

  /**
   * **Trap 1, and the most important assertion in this task.**
   *
   * `AddAlbumService`: `if (artist.AddOptions?.SearchForMissingAlbums ?? false)
   * { album.AddOptions.SearchForNewAlbum = false; }`. A payload that sets both gets a 201, a
   * monitored album, and no search at all — nothing is downloaded and nothing says why. Upstream
   * issue Lidarr #5012.
   *
   * Asserted at **both** values of `searchNow`, because the interaction is only visible when the
   * caller asked for a search.
   */
  @Test
  fun `the artist never asks for a missing-albums search, which would cancel the album search`() {
    for (searchNow in listOf(true, false)) {
      val artistAddOptions = build(searchNow = searchNow)["artist"]!!.jsonObject["addOptions"]!!.jsonObject
      assertThat(artistAddOptions["searchForMissingAlbums"]!!.jsonPrimitive.boolean)
        .describedAs("searchForMissingAlbums with searchNow=%s", searchNow)
        .isFalse()
    }
  }

  @Test
  fun `addType is not sent, because the server overwrites it on every album post`() {
    // `AddAlbumService` sets `album.AddOptions.AddType = AlbumAddType.Manual` unconditionally.
    // Sending it is noise that reads like a decision.
    assertThat(build()["addOptions"]!!.jsonObject).doesNotContainKey("addType")
  }

  /**
   * An artist already in this Lidarr comes back from lookup with a real `path`. `AlbumController`
   * applies the `rootFolderPath` rule only `When(s.Artist.Path.IsNullOrWhiteSpace())`, so leaving
   * an existing path alone is correct — the artist keeps the folder it already lives in.
   */
  @Test
  fun `an existing artist's own path is left alone`() {
    val existing = candidate().let { c ->
      c.copy(
        raw = Json.parseToJsonElement(
          """{"foreignAlbumId":"m","artist":{"foreignArtistId":"a","id":7,"path":"/music/An artist"}}""",
        ).jsonObject,
      )
    }

    val artist = LidarrAddPayload.build(existing, targets(), searchNow = true)["artist"]!!.jsonObject

    assertThat(artist["path"]!!.jsonPrimitive.content).isEqualTo("/music/An artist")
    // `rootFolderPath` is still written: it is harmless when `path` is set (the validator skips
    // its rule) and required when it is not, and branching here would add an untestable-in-
    // isolation decision to a builder that has no business making it.
    assertThat(artist["rootFolderPath"]!!.jsonPrimitive.content).isEqualTo("/music")
  }

  @Test
  fun `a candidate whose raw element has no artist object still produces a valid nested artist`() {
    // Defensive but reachable: `toCandidate` requires `artist.foreignArtistId` to exist, so this
    // shape cannot come from `lookupAlbums` -- but `LidarrAddPayload` is a public object and a
    // caller could construct a candidate by hand. It must not produce a body with no `artist`,
    // which Lidarr rejects with a validation error about a null artist.
    val hand = LidarrAlbumCandidate(
      foreignAlbumId = "m", title = "t", disambiguation = null, albumType = null,
      releaseDate = null, remoteCoverUrl = null, artistName = "n", foreignArtistId = "a",
      alreadyAdded = false, raw = Json.parseToJsonElement("""{"foreignAlbumId":"m"}""").jsonObject,
    )

    val artist = LidarrAddPayload.build(hand, targets(), searchNow = true)["artist"]!!.jsonObject

    assertThat(artist["foreignArtistId"]!!.jsonPrimitive.content).isEqualTo("a")
    assertThat(artist["artistName"]!!.jsonPrimitive.content).isEqualTo("n")
  }
}
```

- [ ] **Step 2: Write the failing submit test**

`integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrSubmitTest.kt`:

```kotlin
package app.muplay.integrations.lidarr

import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentials
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The add, over a real socket.
 *
 * The subject is the **body that went out** and the **outcome that came back**. A test that
 * asserted only that a request was made would be satisfied by a client POSTing `{}` — the exact
 * failure this plan's brief names.
 */
class LidarrSubmitTest {

  @StartStop private val server = MockWebServer()

  private fun client(): LidarrClient {
    val url = IntegrationBaseUrl.parse(server.url("/").toString(), CleartextPolicy.Allowed)
    return LidarrClient(IntegrationCredentials.Lidarr((url as BaseUrlResult.Valid).url, "k"))
  }

  private fun nextRequest(): RecordedRequest =
    checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "the client sent no request" }

  /**
   * The recorded request's body as JSON.
   *
   * `mockwebserver3` 5.5.0 exposes the body as an Okio buffer; use whatever accessor that version
   * actually has (`request.body.utf8()` at the time of writing) rather than assuming this one —
   * Step 3 is where you find out, and a compile error is the cheapest possible way to.
   */
  private fun bodyOf(request: RecordedRequest): JsonObject =
    Json.parseToJsonElement(request.body.utf8()).jsonObject

  private fun candidate(albumId: String, artistId: String) = LidarrAlbumCandidate(
    foreignAlbumId = albumId, title = "An album", disambiguation = null, albumType = null,
    releaseDate = null, remoteCoverUrl = null, artistName = "An artist",
    foreignArtistId = artistId, alreadyAdded = false,
    raw = Json.parseToJsonElement(
      """{"foreignAlbumId":"$albumId","artist":{"foreignArtistId":"$artistId","artistName":"An artist"}}""",
    ).jsonObject,
  )

  private val targets = LidarrAddTargets("/music", 2, 3, "all", "none")

  @Test
  fun `the add is a POST to api v1 album with a json content type`() = runTest {
    server.enqueue(MockResponse.Builder().code(201).body("""{"id":42}""").build())

    client().submitAlbum(candidate("m", "a"), targets, searchNow = true)

    val request = nextRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.url.encodedPath).isEqualTo("/api/v1/album")
    assertThat(request.headers["Content-Type"]).contains("application/json")
    // The key is still a header on a mutation, and still not on the URL.
    assertThat(request.headers["X-Api-Key"]).isEqualTo("k")
    assertThat(request.url.toString()).doesNotContain("apikey")
  }

  /**
   * **The assertion the plan's brief singles out.** Not "a request was submitted": the body, read
   * back, parsed, and its identifier asserted — at two values, so a hardcoded id fails.
   */
  @Test
  fun `the body carries the identifier that was asked for, not a constant`() = runTest {
    server.enqueue(MockResponse.Builder().code(201).body("""{"id":1}""").build())
    client().submitAlbum(candidate("mbid-a", "art-a"), targets, searchNow = true)
    val first = bodyOf(nextRequest())
    assertThat(first["foreignAlbumId"]!!.jsonPrimitive.content).isEqualTo("mbid-a")
    assertThat(first["artist"]!!.jsonObject["foreignArtistId"]!!.jsonPrimitive.content)
      .isEqualTo("art-a")

    server.enqueue(MockResponse.Builder().code(201).body("""{"id":2}""").build())
    client().submitAlbum(candidate("mbid-b", "art-b"), targets, searchNow = true)
    val second = bodyOf(nextRequest())
    assertThat(second["foreignAlbumId"]!!.jsonPrimitive.content).isEqualTo("mbid-b")
    assertThat(second["artist"]!!.jsonObject["foreignArtistId"]!!.jsonPrimitive.content)
      .isEqualTo("art-b")
  }

  @Test
  fun `a 201 yields the album id from the response body`() = runTest {
    // Two ids, so `Added(albumId)` cannot be a constant. This id is what every status poll in
    // Task 7 correlates on -- getting it from the wrong place, or fixing it, breaks every later
    // status update silently.
    server.enqueue(MockResponse.Builder().code(201).body("""{"id":42,"title":"An album"}""").build())
    assertThat(client().submitAlbum(candidate("m", "a"), targets, true))
      .isEqualTo(LidarrAddOutcome.Added(albumId = 42))

    server.enqueue(MockResponse.Builder().code(201).body("""{"id":99,"title":"An album"}""").build())
    assertThat(client().submitAlbum(candidate("m", "a"), targets, true))
      .isEqualTo(LidarrAddOutcome.Added(albumId = 99))
  }

  /**
   * A duplicate add is a **400**, not a 409, and the only thing distinguishing it from any other
   * validation failure is the message string `"This album has already been added."`
   * (`AlbumExistsValidator.GetDefaultMessageTemplate`). Fragile, and treated as fragile: a
   * reworded message degrades to `Rejected`, which shows the user the raw text, rather than to a
   * wrong claim.
   */
  @Test
  fun `an already-added album is its own outcome, not a rejection`() = runTest {
    server.enqueue(
      MockResponse.Builder().code(400)
        .body("""[{"propertyName":"ForeignAlbumId","errorMessage":"This album has already been added."}]""")
        .build(),
    )

    assertThat(client().submitAlbum(candidate("m", "a"), targets, true))
      .isEqualTo(LidarrAddOutcome.AlreadyAdded)
  }

  @Test
  fun `any other validation failure is a rejection carrying every failure`() = runTest {
    server.enqueue(
      MockResponse.Builder().code(400)
        .body(
          """
          [{"propertyName":"Artist.QualityProfileId","errorMessage":"Quality profile does not exist"},
           {"propertyName":"Artist.RootFolderPath","errorMessage":"Root folder does not exist"}]
          """.trimIndent(),
        )
        .build(),
    )

    val outcome = client().submitAlbum(candidate("m", "a"), targets, true)

    assertThat(outcome).isInstanceOf(LidarrAddOutcome.Rejected::class.java)
    // The exact mapped lists, in order: `hasSize(2)` alone would be satisfied by two copies of
    // one failure, and the dotted PascalCase property name is what tells a user *which* setting
    // is wrong.
    val rejected = outcome as LidarrAddOutcome.Rejected
    assertThat(rejected.failures.map { it.propertyName })
      .containsExactly("Artist.QualityProfileId", "Artist.RootFolderPath")
    assertThat(rejected.failures.map { it.errorMessage })
      .containsExactly("Quality profile does not exist", "Root folder does not exist")
  }

  @Test
  fun `a 401 on the add is still an unauthorized failure and not an outcome`() = runTest {
    // Losing authentication is not a thing the user can act on from the request screen the way a
    // validation failure is, so it keeps propagating as an exception rather than becoming a
    // fourth outcome nobody handles specifically.
    server.enqueue(MockResponse.Builder().code(401).build())

    val thrown = runCatching { client().submitAlbum(candidate("m", "a"), targets, true) }
      .exceptionOrNull()
    assertThat(thrown).isInstanceOf(LidarrUnauthorizedException::class.java)
  }

  @Test
  fun `an already-added album can be found again by its foreign id`() = runTest {
    // `AlreadyAdded` carries no id, so status polling needs a way back to one. `GET /api/v1/album`
    // takes `foreignAlbumId`, and two observations prove the parameter is passed through.
    server.enqueue(MockResponse.Builder().code(200).body("""[{"id":7,"foreignAlbumId":"m"}]""").build())
    assertThat(client().findAddedAlbumId("m")).isEqualTo(7)
    val first = nextRequest().url
    assertThat(first.encodedPath).isEqualTo("/api/v1/album")
    assertThat(first.queryParameter("foreignAlbumId")).isEqualTo("m")

    server.enqueue(MockResponse.Builder().code(200).body("""[{"id":8,"foreignAlbumId":"n"}]""").build())
    assertThat(client().findAddedAlbumId("n")).isEqualTo(8)
    assertThat(nextRequest().url.queryParameter("foreignAlbumId")).isEqualTo("n")
  }

  @Test
  fun `an album that is not there yields null rather than an invented id`() = runTest {
    server.enqueue(MockResponse.Builder().code(200).body("[]").build())
    assertThat(client().findAddedAlbumId("missing")).isNull()
  }
}
```

- [ ] **Step 3: Run both to verify they fail**

Run: `./gradlew :integrations:lidarr:test`
Expected: FAIL — `Unresolved reference: LidarrAddPayload`, `submitAlbum`. **If `request.body.utf8()`
does not compile**, fix `bodyOf` to whatever `mockwebserver3` 5.5.0 exposes and record the real
accessor in the task report.

- [ ] **Step 4: Implement the payload builder**

`integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrAddPayload.kt`:

```kotlin
package app.muplay.integrations.lidarr

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Builds the body for `POST /api/v1/album`.
 *
 * **Decorates the lookup element rather than rebuilding it**, which is exactly what Lidarr's own
 * UI does (`frontend/src/Utilities/Album/getNewAlbum.js`) and what its own integration tests do.
 * `openapi.json` declares zero required fields — it is Swashbuckle-generated and does not encode
 * the FluentValidation rules the controller actually enforces — so the only complete statement of
 * what Lidarr wants is what Lidarr sent.
 */
object LidarrAddPayload {

  fun build(
    candidate: LidarrAlbumCandidate,
    targets: LidarrAddTargets,
    searchNow: Boolean,
  ): JsonObject {
    val artist = buildJsonObject {
      // Every field the lookup gave us for this artist, first. Anything this client does not
      // model rides along untouched.
      (candidate.raw["artist"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
      // Then identity, restated from the typed view so a hand-built candidate still produces a
      // usable body, and so `PostValidator.RuleFor(s => s.Artist.ForeignArtistId).NotEmpty()`
      // cannot fail on an element whose nested object was missing.
      put("foreignArtistId", candidate.foreignArtistId)
      put("artistName", candidate.artistName)
      // Then the three the controller validates. `rootFolderPath` is written unconditionally:
      // its rule applies only `When(s.Artist.Path.IsNullOrWhiteSpace())`, so it is harmless on an
      // artist that already has a path and required on one that does not.
      put("qualityProfileId", targets.qualityProfileId)
      put("metadataProfileId", targets.metadataProfileId)
      put("rootFolderPath", targets.rootFolderPath)
      put("monitored", true)
      put("monitorNewItems", targets.newItemMonitorOption)
      put(
        "addOptions",
        buildJsonObject {
          put("monitor", targets.monitorOption)
          put("monitored", true)
          // **Never true.** `AddAlbumService`: if the artist asks for a missing-albums search,
          // the server silently sets `album.addOptions.searchForNewAlbum = false` -- so the album
          // is added, monitored, and never searched for, with nothing reported. Upstream issue
          // Lidarr #5012.
          put("searchForMissingAlbums", false)
        },
      )
    }

    return buildJsonObject {
      candidate.raw.forEach { (key, value) -> put(key, value) }
      put("foreignAlbumId", candidate.foreignAlbumId)
      // An unmonitored album is never fetched, whatever the search flag says.
      put("monitored", true)
      put("artist", artist)
      put(
        "addOptions",
        buildJsonObject {
          // `AddAlbumOptions` is `{ AddType, SearchForNewAlbum }` and nothing else. `addType` is
          // overwritten server-side to Manual on every album POST, so it is not sent; a `monitor`
          // here would be bound case-insensitively, ignored, and look like a decision.
          put("searchForNewAlbum", searchNow)
        },
      )
    }
  }
}
```

- [ ] **Step 5: Implement the outcome, the API methods and the client methods**

Append to `LidarrSource.kt`:

```kotlin
/**
 * What happened when Lidarr was asked to add an album.
 *
 * A sealed result rather than "success or exception", because [AlreadyAdded] is a **normal**
 * outcome — a user asking twice, or asking for something a housemate already added — and Lidarr
 * reports it as a 400 indistinguishable in status from a real configuration error. Forcing the
 * caller to handle all three is the point.
 */
sealed interface LidarrAddOutcome {

  /** Lidarr created the album. [albumId] is what every later status poll correlates on. */
  data class Added(val albumId: Int) : LidarrAddOutcome

  /**
   * Lidarr already has it.
   *
   * Derived by **matching the message string** `"This album has already been added."`
   * (`AlbumExistsValidator`), because a duplicate add is a 400 with no code and nothing structural
   * to key on. If a Lidarr release rewords it this becomes [Rejected] and the user sees the raw
   * validation text — degraded, never wrong.
   */
  data object AlreadyAdded : LidarrAddOutcome

  /** Lidarr refused. [failures] carry dotted PascalCase property names such as `Artist.QualityProfileId`. */
  data class Rejected(val failures: List<LidarrValidationFailure>) : LidarrAddOutcome
}
```

and to the `LidarrSource` interface:

```kotlin
  /**
   * Asks Lidarr to add [candidate], filed according to [targets].
   *
   * [searchNow] becomes `addOptions.searchForNewAlbum`. Note that the artist's own
   * `searchForMissingAlbums` is always sent as `false`, because a `true` there makes the server
   * silently cancel the album search — see [LidarrAddPayload].
   */
  suspend fun submitAlbum(
    candidate: LidarrAlbumCandidate,
    targets: LidarrAddTargets,
    searchNow: Boolean,
  ): LidarrAddOutcome

  /** The database id of an already-added album, by its MusicBrainz id, or `null` if it is not there. */
  suspend fun findAddedAlbumId(foreignAlbumId: String): Int?
```

`LidarrApi.kt`:

```kotlin
  @POST("api/v1/album")
  suspend fun addAlbum(@Body body: JsonObject): Response<JsonObject>

  @GET("api/v1/album")
  suspend fun albumsByForeignId(@Query("foreignAlbumId") foreignAlbumId: String): Response<List<JsonObject>>
```

with `import retrofit2.http.POST`, `retrofit2.http.Body`, `kotlinx.serialization.json.JsonObject`.

`LidarrClient.kt`:

```kotlin
  override suspend fun submitAlbum(
    candidate: LidarrAlbumCandidate,
    targets: LidarrAddTargets,
    searchNow: Boolean,
  ): LidarrAddOutcome =
    try {
      val created = call { api.addAlbum(LidarrAddPayload.build(candidate, targets, searchNow)) }
      // `RestController.Created` re-fetches the persisted resource, so the id here is the real
      // database id -- not the synthetic counter `/api/v1/search` assigns.
      LidarrAddOutcome.Added(
        albumId = (created["id"] as? JsonPrimitive)?.content?.toIntOrNull()
          ?: throw LidarrHttpException(201),
      )
    } catch (e: LidarrValidationException) {
      if (e.isAlreadyAdded) LidarrAddOutcome.AlreadyAdded else LidarrAddOutcome.Rejected(e.failures)
    }

  override suspend fun findAddedAlbumId(foreignAlbumId: String): Int? =
    call { api.albumsByForeignId(foreignAlbumId) }
      .firstNotNullOfOrNull { (it["id"] as? JsonPrimitive)?.content?.toIntOrNull() }
```

> **A 201 with no `id` is a `LidarrHttpException(201)`, deliberately.** Returning
> `Added(albumId = 0)` would put a row in the request store that every later status poll looks up
> under an id no album has — the silent-wrong-answer class. A loud failure on a response that
> should never happen is the right trade.

- [ ] **Step 6: Run, measure, probe**

Run: `./gradlew :integrations:lidarr:test`
Expected: PASS. Re-measure the module floor and update `coverageFloors`.

`ci/mutation-probes.sh`:

```python
LIDARR_PAYLOAD = "integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrAddPayload.kt"
```

```python
    # ---- Plan 7: the add payload -- the crux, and the one the spec called unverified ----------
    ("integrations/lidarr-add-foreignAlbumId", LIDARR_PAYLOAD,
     'put("foreignAlbumId", candidate.foreignAlbumId)', 'put("foreignAlbumId", "mbid-a")',
     "the body carries the identifier that was asked for, not a constant", 2),
    ("integrations/lidarr-add-foreignArtistId", LIDARR_PAYLOAD,
     'put("foreignArtistId", candidate.foreignArtistId)', 'put("foreignArtistId", "art-a")',
     "the nested artist identifier is the one that was asked for", 2),
    # Trap 1: the mutation that produces a 201 and no download, with nothing reported anywhere.
    ("integrations/lidarr-searchForMissingAlbums", LIDARR_PAYLOAD,
     'put("searchForMissingAlbums", false)', 'put("searchForMissingAlbums", true)',
     "the artist never asks for a missing-albums search, which would cancel the album search", 1),
    ("integrations/lidarr-searchForNewAlbum", LIDARR_PAYLOAD,
     'put("searchForNewAlbum", searchNow)', 'put("searchForNewAlbum", true)',
     "searchForNewAlbum is whatever the caller asked for", 1),
    ("integrations/lidarr-add-qualityProfileId", LIDARR_PAYLOAD,
     'put("qualityProfileId", targets.qualityProfileId)', 'put("qualityProfileId", 2)',
     "the three add targets are written onto the nested artist", 1),
```

**The `2` on the first two probes is a measurement, not a guess** — those mutations redden both
`LidarrAddPayloadTest`'s field test and `LidarrSubmitTest`'s body test. Run the probe and use the
number it reports; if it disagrees with `2`, the count is out of date, not the code (read the
script's header, which says exactly this).

- [ ] **Step 7: Commit**

```bash
git add integrations/lidarr ci/mutation-probes.sh build.gradle.kts
git commit -m "feat(lidarr): the add payload, established from Lidarr's own validators

Spec section 8 said this payload was unverified. It now has a source for every field:
AlbumController's PostValidator for what is required, AddAlbumOptions for what the album's
addOptions may contain, and Lidarr's own getNewAlbum.js for the decorate-the-lookup-element
strategy. openapi.json is not used as an oracle -- it is Swashbuckle-generated, declares zero
required fields and documents a 200 where the code returns 201.

The assertion that matters: every submit test reads the recorded body back, parses it, and
asserts the identifier at two values. artist.addOptions.searchForMissingAlbums is pinned
false, because a true there makes the server silently cancel the album search (Lidarr #5012)
-- a 201, a monitored album, and no download, with nothing reported anywhere."
```

---

## Task 7: Lidarr — what happened to the request, mapped from a state nobody may invent

**Files:**
- Modify: `LidarrApi.kt`, `LidarrDto.kt`, `LidarrSource.kt`, `LidarrClient.kt`
- Create: `integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrStatusMapper.kt`
- Create: `integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrStatusMapperTest.kt`
- Create: `integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrQueueTest.kt`
- Create: `integrations/lidarr/src/test/resources/fixtures/lidarr/queue-downloading.json`
- Modify: `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `RequestStatus` (Task 3), `LidarrSource` (Tasks 4–6)
- Produces:
  - `data class LidarrQueueItem(albumId: Int?, artistId: Int?, sizeBytes: Double, sizeLeftBytes: Double, trackedDownloadState: String, trackedDownloadStatus: String, errorMessage: String?)`
  - `data class LidarrAlbumProgress(trackFileCount: Int, totalTrackCount: Int)` with
    `val isComplete: Boolean`
  - `object LidarrStatusMapper` with
    `map(queueItem: LidarrQueueItem?, progress: LidarrAlbumProgress?): RequestStatus` and
    `percentComplete(item: LidarrQueueItem): Int?`
  - `LidarrSource` gains `suspend fun queue(): List<LidarrQueueItem>` and
    `suspend fun albumProgress(albumId: Int): LidarrAlbumProgress?`

### The state values, quoted rather than guessed

Lidarr's queue record carries **two** state-ish fields and they are not equally usable.

- **`status`** is `model.Status.FirstCharToLower()` — a lower-cased download-client status whose
  full value set is not enumerated anywhere this plan could find. **This client does not branch on
  it.** Branching on a set you cannot enumerate means an `else` arm that is a guess.
- **`trackedDownloadState`** *is* enumerated, in the OpenAPI document's own enum:
  `downloading | downloadFailed | downloadFailedPending | importBlocked | importPending |
  importing | importFailed | imported | ignored`. Nine values, and this client maps all nine.
- **`trackedDownloadStatus`** is `ok | warning | error` — carried through into the failure message
  but never the sole basis for a decision, because a `warning` on an item that is still downloading
  is not a failure.

`LidarrStatusMapperTest` asserts **all nine, as one exact mapped list**. A mapper observed on one
status is a mapper that has not been tested — this project's rule 2, applied to the field that
decides what the user is told about their request.

### Why the album's own statistics outrank the queue

`AlbumStatisticsResource` carries `trackFileCount` and `totalTrackCount`. **Files on disk is a
stronger fact than a download client's opinion**, and it is the only one that survives the queue
item disappearing — which it does, immediately after import. A poller that read only the queue
would watch an item vanish and have no idea whether it succeeded.

So the order is: complete statistics → `Imported`; else a queue item → its state; else
`Requested`. `percentOfTracks` is deliberately **not** used: it is a `double` on a 0–100 scale, not
0–1, and a client that assumed the other one shows 0.73% forever. `trackFileCount ==
totalTrackCount` is arithmetic on two integers and cannot be got backwards.

**`Imported` is not `Arrived`.** Lidarr saying the files are on disk is not Navidrome having
scanned them. Task 9 is the code that closes that gap, and collapsing the two here would put a
"play it" button on a row that navigates nowhere.

- [ ] **Step 1: Write the failing mapper test (pure JVM)**

`integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrStatusMapperTest.kt`:

```kotlin
package app.muplay.integrations.lidarr

import app.muplay.integrations.RequestStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Lidarr's nine tracked-download states, mapped onto the five this app shows.
 *
 * All nine, as one exact list. A status mapper observed on one status is not tested — and the
 * consequence of getting one wrong is a user told their download failed when it is running, or
 * told it is running forever when it failed.
 */
class LidarrStatusMapperTest {

  private fun item(
    state: String,
    size: Double = 100.0,
    sizeLeft: Double = 25.0,
    status: String = "ok",
    error: String? = null,
  ) = LidarrQueueItem(
    albumId = 1, artistId = 2, sizeBytes = size, sizeLeftBytes = sizeLeft,
    trackedDownloadState = state, trackedDownloadStatus = status, errorMessage = error,
  )

  /** The full `trackedDownloadState` enum, in the order the OpenAPI document declares it. */
  private val allStates = listOf(
    "downloading", "downloadFailed", "downloadFailedPending", "importBlocked",
    "importPending", "importing", "importFailed", "imported", "ignored",
  )

  @Test
  fun `every tracked download state maps to a status, and no two failures are conflated`() {
    val mapped = allStates.map { LidarrStatusMapper.map(item(it), progress = null) }

    // The exact list. `allMatch { it is RequestStatus }` would be vacuously satisfied by nine
    // copies of `Requested`, which is precisely the shape this project has shipped.
    assertThat(mapped).containsExactly(
      RequestStatus.Downloading(percentComplete = 75),
      RequestStatus.Failed("the download failed"),
      RequestStatus.Failed("the download failed"),
      RequestStatus.Failed("Lidarr could not import the files"),
      RequestStatus.Downloading(percentComplete = 75),
      RequestStatus.Downloading(percentComplete = 75),
      RequestStatus.Failed("Lidarr could not import the files"),
      RequestStatus.Imported,
      RequestStatus.Failed("Lidarr was told to ignore this download"),
    )
  }

  @Test
  fun `a failure message from lidarr replaces the generic one`() {
    // Two observations, so the message is not a constant -- and the whole value of surfacing it
    // is that "no files found in the release" tells the user something the generic text cannot.
    assertThat(LidarrStatusMapper.map(item("importFailed", error = "no audio files found"), null))
      .isEqualTo(RequestStatus.Failed("no audio files found"))
    assertThat(LidarrStatusMapper.map(item("downloadFailed", error = "tracker rejected"), null))
      .isEqualTo(RequestStatus.Failed("tracker rejected"))
    // A blank message is not a message.
    assertThat(LidarrStatusMapper.map(item("downloadFailed", error = "  "), null))
      .isEqualTo(RequestStatus.Failed("the download failed"))
  }

  /**
   * An unrecognised state means a Lidarr newer than this client. The item is in the queue, so
   * *something* is happening; `Downloading` is the only claim its mere presence supports.
   * Reporting `Failed` would be a guess that reads as a fact.
   */
  @Test
  fun `a state this client does not know still reports progress rather than a verdict`() {
    assertThat(LidarrStatusMapper.map(item("somethingNewInLidarr4"), null))
      .isEqualTo(RequestStatus.Downloading(percentComplete = 75))
  }

  @Test
  fun `the percentage is computed from size and sizeleft, at more than one value`() {
    assertThat(LidarrStatusMapper.percentComplete(item("downloading", size = 100.0, sizeLeft = 25.0)))
      .isEqualTo(75)
    assertThat(LidarrStatusMapper.percentComplete(item("downloading", size = 200.0, sizeLeft = 50.0)))
      .isEqualTo(75)
    assertThat(LidarrStatusMapper.percentComplete(item("downloading", size = 100.0, sizeLeft = 90.0)))
      .isEqualTo(10)
  }

  @Test
  fun `a zero-size item has an unknown percentage rather than a divide by zero`() {
    // Lidarr's own queue sort guards this identically: `q.Size == 0 ? 0 : ...`.
    assertThat(LidarrStatusMapper.percentComplete(item("downloading", size = 0.0, sizeLeft = 0.0)))
      .isNull()
  }

  @Test
  fun `a percentage outside zero to one hundred is clamped rather than shown`() {
    // `sizeleft` can exceed `size` briefly while a download client re-reports. A progress bar at
    // -14% is a bug the user sees.
    assertThat(LidarrStatusMapper.percentComplete(item("downloading", size = 100.0, sizeLeft = 114.0)))
      .isEqualTo(0)
    assertThat(LidarrStatusMapper.percentComplete(item("downloading", size = 100.0, sizeLeft = -5.0)))
      .isEqualTo(100)
  }

  /**
   * Files on disk beat the queue. This is what makes a poll correct after the queue item has
   * vanished, which it does the moment an import finishes.
   */
  @Test
  fun `complete statistics report Imported even while a queue item still exists`() {
    val progress = LidarrAlbumProgress(trackFileCount = 10, totalTrackCount = 10)

    assertThat(LidarrStatusMapper.map(item("downloading"), progress)).isEqualTo(RequestStatus.Imported)
    assertThat(LidarrStatusMapper.map(queueItem = null, progress = progress))
      .isEqualTo(RequestStatus.Imported)
  }

  @Test
  fun `incomplete statistics do not report Imported`() {
    // Two observations of the same comparison, either side of the boundary.
    val partial = LidarrAlbumProgress(trackFileCount = 9, totalTrackCount = 10)

    assertThat(LidarrStatusMapper.map(null, partial)).isEqualTo(RequestStatus.Requested)
    assertThat(LidarrStatusMapper.map(item("downloading"), partial))
      .isEqualTo(RequestStatus.Downloading(percentComplete = 75))
  }

  @Test
  fun `an album with no tracks yet is not complete, however many files it has`() {
    // `totalTrackCount == 0` means Lidarr has not fetched the track list. `0 >= 0` would read as
    // "fully downloaded" and put a play button on an empty album.
    assertThat(LidarrStatusMapper.map(null, LidarrAlbumProgress(0, 0)))
      .isEqualTo(RequestStatus.Requested)
  }

  @Test
  fun `nothing in the queue and nothing on disk is still Requested`() {
    // The state a monitored album sits in between being added and a release being found. It is
    // not a failure and it is not progress.
    assertThat(LidarrStatusMapper.map(queueItem = null, progress = null))
      .isEqualTo(RequestStatus.Requested)
  }
}
```

- [ ] **Step 2: Write the failing queue/progress test**

`integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LidarrQueueTest.kt`:

```kotlin
package app.muplay.integrations.lidarr

import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentials
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LidarrQueueTest {

  @StartStop private val server = MockWebServer()

  private fun client(): LidarrClient {
    val url = IntegrationBaseUrl.parse(server.url("/").toString(), CleartextPolicy.Allowed)
    return LidarrClient(IntegrationCredentials.Lidarr((url as BaseUrlResult.Valid).url, "k"))
  }

  private fun nextRequest(): RecordedRequest =
    checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "the client sent no request" }

  private fun json(body: String) =
    MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()

  /**
   * The queue is paged with a **default `pageSize` of 10** (`PagingResource.cs`). A client that
   * accepted the default would stop seeing its own request the moment the user had eleven things
   * downloading, and would report `Requested` forever with nothing wrong anywhere.
   *
   * `includeUnknownArtistItems=true` matters for the same reason: items whose artist Lidarr cannot
   * resolve are **hidden by default**, and an album added seconds ago is exactly the case where
   * the artist may not be resolved yet.
   */
  @Test
  fun `the queue is asked for a page big enough to contain the answer`() = runTest {
    server.enqueue(json("""{"page":1,"pageSize":100,"totalRecords":0,"records":[]}"""))

    client().queue()

    val url = nextRequest().url
    assertThat(url.encodedPath).isEqualTo("/api/v1/queue")
    assertThat(url.queryParameter("pageSize")).isEqualTo("100")
    assertThat(url.queryParameter("includeUnknownArtistItems")).isEqualTo("true")
  }

  @Test
  fun `every queue record field is read from its own record`() = runTest {
    server.enqueue(
      json(
        """
        {"page":1,"pageSize":100,"totalRecords":2,"records":[
          {"id":1,"albumId":11,"artistId":21,"size":100.0,"sizeleft":25.0,
           "trackedDownloadState":"downloading","trackedDownloadStatus":"ok"},
          {"id":2,"albumId":12,"artistId":22,"size":400.0,"sizeleft":0.0,
           "trackedDownloadState":"importFailed","trackedDownloadStatus":"error",
           "errorMessage":"no audio files found"}
        ]}
        """.trimIndent(),
      ),
    )

    val items = client().queue()

    // Exact mapped lists, in order. Order is the server's, and a poller correlating by index
    // rather than by albumId would be broken by a reorder -- so the order is pinned too.
    assertThat(items.map { it.albumId }).containsExactly(11, 12)
    assertThat(items.map { it.artistId }).containsExactly(21, 22)
    assertThat(items.map { it.sizeBytes }).containsExactly(100.0, 400.0)
    assertThat(items.map { it.sizeLeftBytes }).containsExactly(25.0, 0.0)
    assertThat(items.map { it.trackedDownloadState }).containsExactly("downloading", "importFailed")
    assertThat(items.map { it.trackedDownloadStatus }).containsExactly("ok", "error")
    assertThat(items.map { it.errorMessage }).containsExactly(null, "no audio files found")
  }

  /**
   * `sizeleft` is lower-case `l`. Lidarr's `QueueResource` declares `Sizeleft`, which the
   * camelCase policy renders `sizeleft` and **not** `sizeLeft`. A client reading `sizeLeft` gets
   * the kotlinx default of 0.0 on every record and shows every download at 100% forever.
   */
  @Test
  fun `sizeleft is read from the lower-case field lidarr actually sends`() = runTest {
    server.enqueue(
      json(
        """{"records":[{"albumId":1,"size":100.0,"sizeleft":40.0,"sizeLeft":999.0,
             "trackedDownloadState":"downloading","trackedDownloadStatus":"ok"}]}""",
      ),
    )

    assertThat(client().queue().single().sizeLeftBytes).isEqualTo(40.0)
  }

  @Test
  fun `an absent records array is an empty queue, not a failure`() = runTest {
    // A queue with nothing in it is the normal case, and `WhenWritingNull` means the array can be
    // omitted entirely.
    server.enqueue(json("""{"page":1,"pageSize":100,"totalRecords":0}"""))
    assertThat(client().queue()).isEmpty()
  }

  @Test
  fun `album progress is fetched by id and read from the statistics object`() = runTest {
    server.enqueue(json("""{"id":42,"statistics":{"trackFileCount":7,"totalTrackCount":10}}"""))
    val first = client().albumProgress(42)
    assertThat(nextRequest().url.encodedPath).isEqualTo("/api/v1/album/42")
    assertThat(first).isEqualTo(LidarrAlbumProgress(trackFileCount = 7, totalTrackCount = 10))

    // Two observations of the path *and* of both fields.
    server.enqueue(json("""{"id":43,"statistics":{"trackFileCount":3,"totalTrackCount":3}}"""))
    val second = client().albumProgress(43)
    assertThat(nextRequest().url.encodedPath).isEqualTo("/api/v1/album/43")
    assertThat(second).isEqualTo(LidarrAlbumProgress(trackFileCount = 3, totalTrackCount = 3))
  }

  @Test
  fun `an album with no statistics object yields null rather than a zeroed progress`() = runTest {
    // `LidarrAlbumProgress(0, 0)` and "we do not know" are different facts, and the mapper treats
    // the first as "not complete", which is only correct if it really was reported.
    server.enqueue(json("""{"id":42}"""))
    assertThat(client().albumProgress(42)).isNull()
  }

  @Test
  fun `an album that is gone yields null rather than throwing`() = runTest {
    // A user can delete an album in Lidarr while MuPlay still has a request row for it. A 404
    // here is a normal answer to "how is this going", not an error to surface.
    server.enqueue(MockResponse.Builder().code(404).build())
    assertThat(client().albumProgress(42)).isNull()
  }
}
```

- [ ] **Step 3: Run both to verify they fail**

Run: `./gradlew :integrations:lidarr:test`
Expected: FAIL — `Unresolved reference: LidarrStatusMapper`, `queue`.

- [ ] **Step 4: Implement the DTOs, API and models**

`LidarrDto.kt` gains:

```kotlin
/**
 * One page of Lidarr's queue. `records` is nullable because `WhenWritingNull` omits an empty one.
 */
@Serializable
internal data class QueuePageBody(
  val page: Int = 0,
  val pageSize: Int = 0,
  val totalRecords: Int = 0,
  val records: List<QueueRecordBody>? = null,
)

/**
 * **`sizeleft`, lower-case `l`.** `QueueResource` declares `Sizeleft`, and the camelCase policy
 * renders that `sizeleft` — not `sizeLeft`. Reading the wrong one silently yields `0.0` on every
 * record, which shows every download at 100%.
 */
@Serializable
internal data class QueueRecordBody(
  val id: Int = 0,
  val albumId: Int? = null,
  val artistId: Int? = null,
  val size: Double = 0.0,
  val sizeleft: Double = 0.0,
  val trackedDownloadState: String? = null,
  val trackedDownloadStatus: String? = null,
  val errorMessage: String? = null,
)

@Serializable
internal data class AlbumWithStatisticsBody(
  val id: Int = 0,
  val statistics: AlbumStatisticsBody? = null,
)

@Serializable
internal data class AlbumStatisticsBody(
  val trackFileCount: Int = 0,
  val totalTrackCount: Int = 0,
)
```

`LidarrApi.kt`:

```kotlin
  @GET("api/v1/queue")
  suspend fun queue(
    @Query("pageSize") pageSize: Int,
    @Query("includeUnknownArtistItems") includeUnknownArtistItems: Boolean,
  ): Response<QueuePageBody>

  @GET("api/v1/album/{id}")
  suspend fun album(@Path("id") id: Int): Response<AlbumWithStatisticsBody>
```

with `import retrofit2.http.Path`.

Append to `LidarrSource.kt`:

```kotlin
/**
 * One item in Lidarr's download queue.
 *
 * The queue is a **live merge** of the download client's queue and pending releases, so `id` is
 * not durable across polls — every correlation in this app is on [albumId].
 */
data class LidarrQueueItem(
  val albumId: Int?,
  val artistId: Int?,
  val sizeBytes: Double,
  val sizeLeftBytes: Double,
  /** One of nine enumerated values; see [LidarrStatusMapper] for all of them. */
  val trackedDownloadState: String,
  /** `ok`, `warning` or `error`. Never the sole basis for a verdict. */
  val trackedDownloadStatus: String,
  val errorMessage: String?,
)

/**
 * How much of an album Lidarr actually has on disk.
 *
 * Deliberately **not** `percentOfTracks`, which is a `double` on a **0-100** scale rather than
 * 0-1: a client that assumed the other convention shows 0.73% forever. Two integers compared is
 * arithmetic nobody can get backwards.
 */
data class LidarrAlbumProgress(val trackFileCount: Int, val totalTrackCount: Int) {
  /** `false` when Lidarr has not fetched the track list yet — `0 >= 0` is not "complete". */
  val isComplete: Boolean get() = totalTrackCount > 0 && trackFileCount >= totalTrackCount
}
```

and to the interface:

```kotlin
  /** Everything currently downloading or importing, in Lidarr's own order. */
  suspend fun queue(): List<LidarrQueueItem>

  /** How much of [albumId] is on disk, or `null` if Lidarr does not know or no longer has it. */
  suspend fun albumProgress(albumId: Int): LidarrAlbumProgress?
```

- [ ] **Step 5: Implement the mapper and the client methods**

`integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrStatusMapper.kt`:

```kotlin
package app.muplay.integrations.lidarr

import app.muplay.integrations.RequestStatus
import kotlin.math.roundToInt

/**
 * Turns what Lidarr says into what this app shows.
 *
 * A pure object with no HTTP in it, so every branch is Tier-1 enforceable — the same argument
 * `LidarrAddTargets` and Plan 3's `StreamRetryPolicy` make.
 *
 * **Branches on `trackedDownloadState` and never on `status`.** `status` is
 * `model.Status.FirstCharToLower()` — a download-client status whose complete value set is not
 * enumerated anywhere this plan could establish, and branching on a set you cannot enumerate means
 * an `else` arm that is a guess presented as a fact. `trackedDownloadState` is declared as a
 * nine-member enum in Lidarr's own OpenAPI document, and all nine are handled below.
 */
object LidarrStatusMapper {

  private val IN_PROGRESS = setOf("downloading", "importPending", "importing")
  private val DOWNLOAD_FAILED = setOf("downloadFailed", "downloadFailedPending")
  private val IMPORT_FAILED = setOf("importFailed", "importBlocked")

  /**
   * [progress] outranks [queueItem]: files on disk is a stronger fact than a download client's
   * opinion, and it is the only one that survives the queue item vanishing — which happens the
   * moment an import completes.
   *
   * Returns [RequestStatus.Imported], never [RequestStatus.Arrived]: Lidarr having the files is
   * not Navidrome having scanned them, and collapsing the two would put a "play it" button on a
   * row that navigates nowhere.
   */
  fun map(queueItem: LidarrQueueItem?, progress: LidarrAlbumProgress?): RequestStatus {
    if (progress?.isComplete == true) return RequestStatus.Imported
    if (queueItem == null) return RequestStatus.Requested
    val detail = queueItem.errorMessage?.takeIf { it.isNotBlank() }
    return when (queueItem.trackedDownloadState) {
      "imported" -> RequestStatus.Imported
      "ignored" -> RequestStatus.Failed(detail ?: "Lidarr was told to ignore this download")
      in DOWNLOAD_FAILED -> RequestStatus.Failed(detail ?: "the download failed")
      in IMPORT_FAILED -> RequestStatus.Failed(detail ?: "Lidarr could not import the files")
      in IN_PROGRESS -> RequestStatus.Downloading(percentComplete(queueItem))
      // A state this client does not know means a Lidarr newer than this build. The item is in
      // the queue, so something is happening -- which is the only claim its presence supports.
      // Reporting a failure here would be a guess that reads as a verdict.
      else -> RequestStatus.Downloading(percentComplete(queueItem))
    }
  }

  /**
   * `1 - sizeleft/size`, as a whole percentage, or `null` when the size is unknown.
   *
   * Lidarr does not send a percentage; its own queue sort computes this the same way, with the
   * same zero guard (`q.Size == 0 ? 0 : 100 - (q.Sizeleft / q.Size * 100)`). Clamped because a
   * download client can briefly report `sizeleft` above `size` or below zero, and a progress bar
   * at -14% is a bug the user sees.
   */
  fun percentComplete(item: LidarrQueueItem): Int? {
    if (item.sizeBytes <= 0.0) return null
    val done = (item.sizeBytes - item.sizeLeftBytes) / item.sizeBytes
    return (done * 100).roundToInt().coerceIn(0, 100)
  }
}
```

`LidarrClient.kt`:

```kotlin
  override suspend fun queue(): List<LidarrQueueItem> =
    call { api.queue(pageSize = QUEUE_PAGE_SIZE, includeUnknownArtistItems = true) }
      .records
      .orEmpty()
      .map { record ->
        LidarrQueueItem(
          albumId = record.albumId,
          artistId = record.artistId,
          sizeBytes = record.size,
          sizeLeftBytes = record.sizeleft,
          trackedDownloadState = record.trackedDownloadState.orEmpty(),
          trackedDownloadStatus = record.trackedDownloadStatus.orEmpty(),
          errorMessage = record.errorMessage,
        )
      }

  override suspend fun albumProgress(albumId: Int): LidarrAlbumProgress? =
    // A 404 is a normal answer to "how is this going" for an album the user deleted in Lidarr
    // while MuPlay still holds a request row for it. Anything else still propagates.
    try {
      call { api.album(albumId) }.statistics
        ?.let { LidarrAlbumProgress(it.trackFileCount, it.totalTrackCount) }
    } catch (e: LidarrHttpException) {
      if (e.status == 404) null else throw e
    }
```

and in the companion:

```kotlin
    /**
     * `PagingResource` defaults `pageSize` to **10**. A client that accepted that would stop
     * seeing its own request as soon as the user had eleven things downloading, and would report
     * `Requested` forever with nothing wrong anywhere.
     */
    private const val QUEUE_PAGE_SIZE = 100
```

- [ ] **Step 6: Run, measure, probe**

Run: `./gradlew :integrations:lidarr:test`
Expected: PASS. Re-measure the module floor.

`ci/mutation-probes.sh`:

```python
LIDARR_STATUS = "integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrStatusMapper.kt"
```

```python
    ("integrations/lidarr-status-imported", LIDARR_STATUS,
     '"imported" -> RequestStatus.Imported', '"imported" -> RequestStatus.Requested',
     "every tracked download state maps to a status, and no two failures are conflated", 1),
    ("integrations/lidarr-status-progress-beats-queue", LIDARR_STATUS,
     "if (progress?.isComplete == true) return RequestStatus.Imported",
     "if (false) return RequestStatus.Imported",
     "complete statistics report Imported even while a queue item still exists", 1),
    ("integrations/lidarr-progress-zero-tracks", LIDARR_STATUS.replace(
        "LidarrStatusMapper.kt", "LidarrSource.kt"),
     "get() = totalTrackCount > 0 && trackFileCount >= totalTrackCount",
     "get() = trackFileCount >= totalTrackCount",
     "an album with no tracks yet is not complete, however many files it has", 1),
    ("integrations/lidarr-queue-sizeleft", LIDARR_CLIENT,
     "sizeLeftBytes = record.sizeleft,", "sizeLeftBytes = 0.0,",
     "sizeleft is read from the lower-case field lidarr actually sends", 1),
    ("integrations/lidarr-queue-pagesize", LIDARR_CLIENT,
     "private const val QUEUE_PAGE_SIZE = 100", "private const val QUEUE_PAGE_SIZE = 10",
     "the queue is asked for a page big enough to contain the answer", 1),
```

- [ ] **Step 7: Commit**

```bash
git add integrations/lidarr ci/mutation-probes.sh build.gradle.kts
git commit -m "feat(lidarr): request status from a state set that is actually enumerated

Branches on trackedDownloadState (nine values, declared as an enum in Lidarr's own OpenAPI
document) and never on `status`, whose value set this plan could not establish -- branching
on a set you cannot enumerate means an else arm that is a guess. All nine are asserted as one
exact mapped list.

The album's own trackFileCount/totalTrackCount outranks the queue, because files on disk is
the only fact that survives the queue item vanishing at import. percentOfTracks is
deliberately unused: it is 0-100, not 0-1, and the wrong assumption shows 0.73% forever.
Imported is not Arrived -- Navidrome has not scanned anything yet."
```

---

## Task 8: `:integrations:bindery` — asking for a book, where asking *is* acquiring

**Files:**
- Create: `integrations/bindery/build.gradle.kts`
- Create: `integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/BinderyApi.kt`
- Create: `integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/BinderyDto.kt`
- Create: `integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/BinderyException.kt`
- Create: `integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/BinderyAuthInterceptor.kt`
- Create: `integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/BinderySource.kt`
- Create: `integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/BinderyClient.kt`
- Create: `integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/BinderyStatusMapper.kt`
- Create: `integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/BinderySourceProvider.kt`
- Create: `integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/di/BinderyModule.kt`
- Create: `integrations/bindery/src/test/kotlin/app/muplay/integrations/bindery/BinderyAuthTest.kt`
- Create: `integrations/bindery/src/test/kotlin/app/muplay/integrations/bindery/BinderySearchTest.kt`
- Create: `integrations/bindery/src/test/kotlin/app/muplay/integrations/bindery/BinderySubmitTest.kt`
- Create: `integrations/bindery/src/test/kotlin/app/muplay/integrations/bindery/BinderyStatusMapperTest.kt`
- Create: `integrations/bindery/src/test/resources/fixtures/bindery/*.json`
- Modify: `integrations/core/.../IntegrationCredentials.kt` — the `Bindery` member
- Modify: `integrations/core/.../IntegrationCredentialStore.kt` — its two `when` arms
- Modify: `settings.gradle.kts`, `build.gradle.kts`, `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `IntegrationBaseUrl`, `IntegrationCredentialStore`, `RequestStatus`
- Produces:
  - `IntegrationCredentials.Bindery(baseUrl: IntegrationBaseUrl, apiKey: String)`
  - `data class BinderyServer(version: String)`
  - `data class BinderyBookCandidate(foreignBookId: String, title: String, authorName: String, foreignAuthorId: String?, asin: String?, coverUrl: String?, raw: JsonObject)`
  - `data class BinderyBook(id: String, foreignBookId: String, title: String, status: String)`
  - `enum class BinderyMediaType(val wireValue: String) { EBOOK("ebook"), AUDIOBOOK("audiobook"), BOTH("both") }`
  - `sealed interface BinderyException`; `BinderyUnauthorizedException`,
    `BinderyHttpException(status: Int)`, `BinderyMessageException(status: Int, message: String)`
  - `object BinderyStatusMapper` with `map(status: String): RequestStatus`
  - `interface BinderySource` with `health()`, `searchBooks(term)`, `submitBook(candidate, mediaType, searchOnAdd)`, `books(status: String?)`
  - `class BinderySourceProvider` with `suspend fun current(): BinderySource?`

### Read this before writing a line: the spec is wrong about what Bindery is

Spec §8 says *"**Bindery** — request audiobooks from inside the app"*, listed beside Lidarr under
"Optional integrations", with the framing that both are **request** services. That framing is
wrong, and it was established by reading Bindery's own source rather than its README:

**Bindery is a Readarr replacement — an acquisition automation tool.** There is no
request/approval concept in it: there are no request or approve routes in its router (the only
`approve` is an *import review* queue), and none on its roadmap. **Adding a book *is* acquiring
it.** There is nobody to approve anything, because the person adding the book owns the server.

This plan therefore models **acquisition, not approval**, and that is a deliberate decision rather
than a simplification:

- There is no `Pending approval` state, because Bindery has none. Inventing one would be a state
  machine the server does not have, displayed to a user as if it did.
- The verb in the UI is *"Ask Bindery for this"*, and what happens next is that Bindery goes and
  gets it. For a single self-hosted user those are the same act, and the thing spec §8 wanted —
  "request audiobooks from inside the app" — is satisfied.
- Task 11 corrects spec §8's wording, because a design decision that lives only in a plan is one
  the next spec reader will make differently.

**Three unrelated projects are called Bindery.** The one this plan means is
**`github.com/vavallee/bindery`** — MIT, ~408 stars, actively developed, **v1.32.1 (2026-08-20)**,
Docker image `ghcr.io/vavallee/bindery`, default port **8787**. Not `evanbrooks/bindery` (browser
book layout, archived 2023) and not `jarynclouatre/bindery` (an e-book format converter, and
confusingly the only "Bindery" in awesome-selfhosted). Write this into the module's own KDoc: the
next person to look it up will find the wrong one first.

### The API, and exactly how confident this plan is in each part

| Fact | Confidence |
|---|---|
| Base path `/api/v1`; auth header `X-Api-Key` | **Established** from source |
| `?apikey=` works on GET/HEAD/OPTIONS only and is **rejected on mutations** | **Established** from source |
| The API key is **instance-wide and always treated as admin** (`middleware.go`); the users table has **no `api_key` column** across all 75 migrations, so the README's "per-account API key" claim is false | **Established** from source |
| `GET /api/v1/health`, unauthenticated → `{"status":"ok","version":"…"}` | **Established** from source |
| `GET /api/v1/search/book?term=…` → a **bare array**. The docs say `?q=` and are **wrong** — the handler reads `term` | **Established** from source; *the 400 on `?q=` was read, not run* |
| `POST /api/v1/author/book` (undocumented) → **201**. Body: `foreignBookId` (required), `foreignAuthorId`, `authorName`, `searchOnAdd`, `mediaType` ∈ `ebook\|audiobook\|both` | **Established** from source; *the 201 was read, not run* |
| **`mediaType` defaults to `ebook`** | **Established** — and this is the trap below |
| `GET /api/v1/book?status=…` → `{items,total,limit,offset}`; statuses `wanted\|downloading\|downloaded\|imported` | **Established** from source |
| `asin` is top-level on a book; `foreignBookId` is namespaced `gb:`/`hc:`/`dnb:`/unprefixed-means-OpenLibrary | **Established** from source |
| ISBN is **not** on `Book` (`ProviderISBNs` is `json:"-"`); it lives on editions via `GET /api/v1/book/{id}` | **Established** from source |
| **There is no OpenAPI or Swagger document at all** | **Established** |
| **The per-item field names of a search result and of a book** | **NOT established.** See Step 1. |

**Two facts were read from source at commit `300e38a` and never executed**, and they are the
implementer's first job: the `400` on `?q=`, and the `201` on `POST /api/v1/author/book`. Step 1
runs both. If either is wrong, the code changes and the finding goes in the task report — that is
what this step is for.

**And there is no oracle here.** Bindery publishes no machine-readable schema, so the
`OpenApiFixtureValidator` pattern `:core:testing` uses for Navidrome has nothing to validate
against. The substitute is **fixtures captured from a real instance**, and this task does not
proceed on hand-written JSON.

### The trap that would silently deliver the wrong thing

**`mediaType` defaults to `ebook`.** MuPlay is an audiobook player. A submit that omits the field
gets a `201`, a happy-looking request row, and an EPUB — which Navidrome will never scan, so the
request sits at `Imported` forever and never becomes `Arrived`, with nothing anywhere saying why.

`BinderySubmitTest`'s `the media type is always sent, and is audiobook by default` is the
assertion that closes it, and `LidarrAddPayload`'s equivalent (Trap 1) is the same shape of bug in
the other service. Two services, two silent-wrong-answers, both from a field whose *absence* is
legal.

### The key is admin-equivalent, and the plan says so out loud

Bindery's API key is not scoped to a user and is always treated as admin. For a single self-hosted
owner that is acceptable — it is exactly the position the Navidrome password is already in — but
it is a hard constraint on anything built later: **nobody may build multi-user sharing on this
key.** It is sealed with the same AndroidKeystore AES-GCM mechanism as `CredentialStore`, never
logged, and never placed in a URL. Task 2's store already does all three; this task only adds the
member.

- [ ] **Step 1: Stand up a real Bindery, settle the two unexecuted facts, and capture the fixtures**

```bash
# Pin a tag; do not use `latest`. v1.32.1 is the version this plan was written against -- confirm
# it exists and record whatever you actually pinned.
docker run -d --name bindery-capture -p 8787:8787 \
  -v "$PWD/.bindery-capture:/config" ghcr.io/vavallee/bindery:<PINNED TAG>

# The API key: find it the way Bindery's own docs say to (its settings UI, or its config file in
# /config). Record where it actually came from -- this plan does not know, and guessing a path
# would be exactly the invention it forbids.
KEY=<the key>
B=http://localhost:8787
F=integrations/bindery/src/test/resources/fixtures/bindery
mkdir -p "$F"

# --- FACT 1: health is unauthenticated and carries a version -------------------------------
curl -sS -i "$B/api/v1/health" | tee /dev/stderr | tail -1 > "$F/health.json"

# --- FACT 2: `term`, not `q`. Read from source, never run. -----------------------------------
echo "--- term (expected: 200 and a bare JSON array) ---"
curl -sS -o "$F/search-book.json" -w '%{http_code}\n' \
  -H "X-Api-Key: $KEY" "$B/api/v1/search/book?term=project%20hail%20mary"
echo "--- q (expected: 400) ---"
curl -sS -o /dev/null -w '%{http_code}\n' -H "X-Api-Key: $KEY" \
  "$B/api/v1/search/book?q=project%20hail%20mary"

# --- FACT 3: the submit returns 201. Read from source, never run. ----------------------------
# Take foreignBookId / foreignAuthorId / authorName from a real element of search-book.json.
echo "--- submit (expected: 201) ---"
curl -sS -i -X POST "$B/api/v1/author/book" -H "X-Api-Key: $KEY" \
  -H 'Content-Type: application/json' \
  -d '{"foreignBookId":"<FROM THE SEARCH>","foreignAuthorId":"<FROM THE SEARCH>",
       "authorName":"<FROM THE SEARCH>","mediaType":"audiobook","searchOnAdd":false}'

# --- FACT 4: the query-string key really is refused on a mutation ----------------------------
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$B/api/v1/author/book?apikey=$KEY" \
  -H 'Content-Type: application/json' -d '{}'

# --- The book list, in each of the four states it can be in ----------------------------------
for s in wanted downloading downloaded imported; do
  curl -sS -H "X-Api-Key: $KEY" "$B/api/v1/book?status=$s" | python3 -m json.tool > "$F/books-$s.json"
done
curl -sS -H "X-Api-Key: $KEY" "$B/api/v1/book" | python3 -m json.tool > "$F/books-all.json"

# --- Unauthenticated, for the 401 assertion --------------------------------------------------
curl -sS -i "$B/api/v1/book" | head -5

! grep -rl "$KEY" integrations/bindery/src/test/resources/ && echo "clean"
```

**Write all of this into the task report**, whatever it says:

1. Where the API key actually came from.
2. `?q=` — the real status code and body. If it is not 400, this plan's table is wrong; fix the
   table and say so.
3. `POST /api/v1/author/book` — the real status code, and whether the response body carries the
   created book's id (this plan does not know, and Step 6 depends on it — see the note there).
4. The status code for the query-string key on a mutation.
5. **The exact per-item field names** in `search-book.json` and `books-*.json`. This plan asserts
   `foreignBookId`, `title`, `authorName`, `foreignAuthorId`, `asin`, `id` and `status`; every one
   of those is a *name this plan did not observe*, and the DTOs in Step 5 must be corrected to
   match what is really there. **Changing a field name here is a normal outcome of this step, not
   a failure of the plan.**
6. Whether a book element carries anything usable as a cover URL.

**If no Bindery instance can be stood up**, stop. Do not write this module against invented field
names — say so in the task report and let the controller decide whether to defer Task 8 and ship
the Lidarr half alone. **That is a supported outcome**: the two integrations are independently
optional by construction, so a Plan 7 with only Lidarr is a complete, coherent deliverable.

- [ ] **Step 2: Add the `Bindery` credential member and close the store's two `when` arms**

`integrations/core/.../IntegrationCredentials.kt` — add inside the sealed interface:

```kotlin
  /**
   * Bindery authenticates with a single API key sent as an `X-Api-Key` **header**.
   *
   * Bindery also accepts `?apikey=` — but **only on GET, HEAD and OPTIONS; it is rejected outright
   * on mutations**, so a query-string client cannot even submit a book. The header is therefore
   * not merely the safer choice here, it is the only one that works end to end.
   *
   * **This key is instance-wide and is always treated as admin** (`middleware.go`; the users table
   * has no `api_key` column in any of Bindery's 75 migrations, so the README's "per-account API
   * key" claim is false). Acceptable for a single self-hosted owner — the Navidrome password is in
   * exactly the same position — but nothing may be built on top of it that assumes user scoping.
   */
  data class Bindery(
    override val baseUrl: IntegrationBaseUrl,
    val apiKey: String,
  ) : IntegrationCredentials {

    override val service: IntegrationService get() = IntegrationService.BINDERY

    override fun toString(): String = "Bindery(baseUrl=$baseUrl, apiKey=<redacted>)"
  }
```

`IntegrationCredentialStore.kt` — the two `when`s become exhaustive over both members:

```kotlin
    return when (service) {
      IntegrationService.LIDARR -> IntegrationCredentials.Lidarr(url, secret)
      IntegrationService.BINDERY -> IntegrationCredentials.Bindery(url, secret)
    }
```

```kotlin
  private fun secretOf(credentials: IntegrationCredentials): String = when (credentials) {
    is IntegrationCredentials.Lidarr -> credentials.apiKey
    is IntegrationCredentials.Bindery -> credentials.apiKey
  }
```

Then extend `IntegrationCredentialStoreTest` with the fourth configuration combination, which Task
2 could not write because the member did not exist:

```kotlin
  private val bindery = IntegrationCredentials.Bindery(
    baseUrl = url("https://bindery.example.com"),
    apiKey = "bindery-secret-key",
  )

  @Test
  fun `both services configured at once are independent in every direction`() = runTest {
    store.save(lidarr)
    store.save(bindery)

    // Both readable, each its own type, neither's secret leaking into the other.
    assertThat(store.configured.first().keys)
      .containsExactly(IntegrationService.LIDARR, IntegrationService.BINDERY)
    assertThat((store.load(IntegrationService.LIDARR) as IntegrationCredentials.Lidarr).apiKey)
      .isEqualTo("0123456789abcdef0123456789abcdef")
    assertThat((store.load(IntegrationService.BINDERY) as IntegrationCredentials.Bindery).apiKey)
      .isEqualTo("bindery-secret-key")

    store.clear(IntegrationService.LIDARR)

    // Forgetting one leaves the other completely intact -- entries and Keystore key alike. This
    // is the assertion the per-service alias exists for.
    assertThat(store.configured.first().keys).containsExactly(IntegrationService.BINDERY)
    assertThat((store.load(IntegrationService.BINDERY) as IntegrationCredentials.Bindery).apiKey)
      .isEqualTo("bindery-secret-key")
    assertThat(IntegrationCredentialStore.keyExists(IntegrationService.BINDERY)).isTrue()
    assertThat(IntegrationCredentialStore.keyExists(IntegrationService.LIDARR)).isFalse()
  }
```

- [ ] **Step 3: Create the module**

`settings.gradle.kts`: `include(":integrations:bindery")`

`integrations/bindery/build.gradle.kts` — identical in shape to `:integrations:lidarr`'s, with
`namespace = "app.muplay.integrations.bindery"`.

`build.gradle.kts`:

```kotlin
  // `:integrations:bindery`. Plain Kotlin over Retrofit/OkHttp, no Android dependency, so the
  // whole module is Tier-1 BRANCH-enforceable. Measured in Task 8 Step 8.
  ":integrations:bindery" to listOf(CoverageFloor(counter = "BRANCH", minimum = BigDecimal("0.90"))),
```

- [ ] **Step 4: Write the failing tests**

`integrations/bindery/src/test/kotlin/app/muplay/integrations/bindery/BinderyStatusMapperTest.kt`
— pure JVM, and the one test in this module whose subject is fully established:

```kotlin
package app.muplay.integrations.bindery

import app.muplay.integrations.RequestStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Bindery's four book statuses, mapped onto the five this app shows.
 *
 * All four as one exact list. The set is `wanted | downloading | downloaded | imported`, from
 * Bindery's own source, and **it has no failure member** — so this client never synthesises one.
 * A book Bindery cannot find simply stays `wanted`, and telling the user it failed would be a
 * claim the server never made.
 */
class BinderyStatusMapperTest {

  @Test
  fun `every bindery status maps to exactly one request status`() {
    val statuses = listOf("wanted", "downloading", "downloaded", "imported")

    assertThat(statuses.map(BinderyStatusMapper::map)).containsExactly(
      RequestStatus.Requested,
      RequestStatus.Downloading(percentComplete = null),
      RequestStatus.Downloading(percentComplete = null),
      RequestStatus.Imported,
    )
  }

  /**
   * `downloaded` is deliberately **not** [RequestStatus.Imported]. The file has been fetched but
   * has not been moved into the library folder, so Navidrome cannot possibly have scanned it —
   * and `Imported` is what Task 9 treats as "start looking for it in the mirror". Collapsing the
   * two would start a search that can never succeed and would look, to a user, like the arrival
   * detection was broken.
   */
  @Test
  fun `downloaded is progress, not arrival`() {
    assertThat(BinderyStatusMapper.map("downloaded"))
      .isNotEqualTo(RequestStatus.Imported)
      .isEqualTo(RequestStatus.Downloading(percentComplete = null))
  }

  @Test
  fun `the percentage is null, because bindery does not report one`() {
    // Not zero. "We do not know how far along this is" and "it has not started" are different
    // things to show a user, and inventing 0 would show a progress bar that never moves.
    assertThat((BinderyStatusMapper.map("downloading") as RequestStatus.Downloading).percentComplete)
      .isNull()
  }

  @Test
  fun `a status this client does not know makes the least possible claim`() {
    // A newer Bindery with a fifth status. `Requested` says only "we have asked and it is not
    // here yet", which is true of every state short of success. `Failed` would be a verdict.
    assertThat(BinderyStatusMapper.map("somethingNew")).isEqualTo(RequestStatus.Requested)
    assertThat(BinderyStatusMapper.map("")).isEqualTo(RequestStatus.Requested)
  }

  @Test
  fun `the status match is case-insensitive`() {
    assertThat(BinderyStatusMapper.map("IMPORTED")).isEqualTo(RequestStatus.Imported)
  }
}
```

`BinderyAuthTest.kt`, `BinderySearchTest.kt` and `BinderySubmitTest.kt` follow the same shape as
`:integrations:lidarr`'s equivalents — **write them by reading those three files, not by pattern-
matching this plan**, and change only what differs. The four assertions that are specific to
Bindery and must exist:

```kotlin
  // BinderyAuthTest
  @Test
  fun `every request carries the key in the X-Api-Key header, at two values`() = runTest { /* … */ }

  @Test
  fun `no request this client makes carries the key on its url`() = runTest {
    // Bindery accepts `?apikey=` on GET/HEAD/OPTIONS and **rejects it on mutations**, so a
    // query-string client cannot submit a book at all. Header-only is the only thing that works
    // end to end here, as well as the only thing that keeps the key out of a recorded request.
  }

  // BinderySearchTest
  @Test
  fun `the search parameter is term, and never q`() = runTest {
    server.enqueue(json("[]"))
    client().searchBooks("project hail mary")
    val url = nextRequest().url
    assertThat(url.encodedPath).isEqualTo("/api/v1/search/book")
    // Bindery's own documentation says `q`. The handler reads `term`, and `q` returns 400. This
    // assertion is the reason this client works and a client written from the docs does not.
    assertThat(url.queryParameter("term")).isEqualTo("project hail mary")
    assertThat(url.queryParameter("q")).isNull()

    server.enqueue(json("[]"))
    client().searchBooks("dune")
    assertThat(nextRequest().url.queryParameter("term")).isEqualTo("dune")
  }

  @Test
  fun `the search response is a bare array, not an envelope`() = runTest {
    // `GET /api/v1/search/book` returns an array; `GET /api/v1/book` returns
    // `{items,total,limit,offset}`. Two shapes on one service, and using the wrong reader for
    // either yields an empty list rather than an error.
  }

  // BinderySubmitTest
  @Test
  fun `the media type is always sent, and is audiobook by default`() = runTest {
    server.enqueue(MockResponse.Builder().code(201).body("{}").build())
    client().submitBook(candidate("book-1"), BinderyMediaType.AUDIOBOOK, searchOnAdd = true)

    val body = bodyOf(nextRequest())
    // **The trap.** `mediaType` defaults to `ebook` server-side. Omitting it yields a 201, a
    // happy-looking request row, and an EPUB Navidrome will never scan -- so the request sits at
    // Imported forever and never becomes Arrived, with nothing anywhere saying why.
    assertThat(body["mediaType"]!!.jsonPrimitive.content).isEqualTo("audiobook")

    // The second observation, so the field is not a constant.
    server.enqueue(MockResponse.Builder().code(201).body("{}").build())
    client().submitBook(candidate("book-1"), BinderyMediaType.BOTH, searchOnAdd = true)
    assertThat(bodyOf(nextRequest())["mediaType"]!!.jsonPrimitive.content).isEqualTo("both")
  }

  @Test
  fun `the body carries the book identifier that was asked for, not a constant`() = runTest {
    // Two ids. Same assertion, same reason, as Lidarr's -- this is the one the plan's brief names.
  }
```

- [ ] **Step 5: Implement the DTOs — from the capture, not from this plan**

Write `BinderyDto.kt` **against `search-book.json` and `books-*.json` as captured in Step 1.** The
shapes below are the plan's best statement of what to expect and every non-primitive field is
nullable with a default, so an absent field is not fatal — but **the field names are the part this
plan did not observe**, and correcting them from the capture is the expected outcome of this step.

```kotlin
package app.muplay.integrations.bindery

import kotlinx.serialization.Serializable

/** `GET /api/v1/health`, unauthenticated. Established from source. */
@Serializable
internal data class HealthBody(val status: String? = null, val version: String? = null)

/**
 * The envelope `GET /api/v1/book` returns. **Established from source**, unlike the element shape
 * below: `{items, total, limit, offset}`.
 *
 * Note that `GET /api/v1/search/book` returns a **bare array** instead. Two shapes on one service,
 * and reading either with the other's reader yields an empty list rather than an error.
 */
@Serializable
internal data class BookPageBody(
  val items: List<BookBody>? = null,
  val total: Int = 0,
  val limit: Int = 0,
  val offset: Int = 0,
)

/**
 * One book.
 *
 * **`foreignBookId` is namespaced**: `gb:` for Google Books, `hc:` for Hardcover, `dnb:` for the
 * Deutsche Nationalbibliothek, and an unprefixed value means Open Library. This client treats it
 * as an opaque string and never parses the prefix — but it is stored as the request's
 * `externalId`, so two books from different providers cannot collide.
 *
 * **There is no ISBN here.** `Book.ProviderISBNs` is `json:"-"` in Bindery's own model; ISBNs live
 * on editions, reachable through `GET /api/v1/book/{id}`. This client does not need one and does
 * not fetch editions.
 */
@Serializable
internal data class BookBody(
  val id: String? = null,
  val foreignBookId: String? = null,
  val foreignAuthorId: String? = null,
  val title: String? = null,
  val authorName: String? = null,
  /** Top-level on a book, per Bindery's model. Not used for identity — `foreignBookId` is. */
  val asin: String? = null,
  val status: String? = null,
)

/**
 * The body of `POST /api/v1/author/book`. **Undocumented**, established by reading the handler.
 *
 * `mediaType` is not optional as far as this client is concerned: it defaults to `ebook`
 * server-side, and this application plays audiobooks.
 */
@Serializable
internal data class AddBookBody(
  val foreignBookId: String,
  val foreignAuthorId: String? = null,
  val authorName: String? = null,
  val mediaType: String,
  val searchOnAdd: Boolean,
)
```

- [ ] **Step 6: Implement the client, the mapper and the provider**

`BinderyStatusMapper.kt`:

```kotlin
package app.muplay.integrations.bindery

import app.muplay.integrations.RequestStatus

/**
 * Bindery's four book statuses, mapped onto this app's five.
 *
 * The set is `wanted | downloading | downloaded | imported`, from Bindery's own source. **It has
 * no failure member**, so this client never synthesises one: a book Bindery cannot find stays
 * `wanted`, and reporting that as a failure would be a claim the server never made.
 */
object BinderyStatusMapper {

  fun map(status: String): RequestStatus = when (status.lowercase()) {
    "wanted" -> RequestStatus.Requested
    // Bindery reports no byte counts, so there is no percentage to compute. `null`, not `0`:
    // "we do not know how far along this is" and "it has not started" are different things, and
    // a progress bar pinned at 0 looks broken.
    "downloading" -> RequestStatus.Downloading(percentComplete = null)
    // **Not `Imported`.** The file is fetched but has not been moved into the library folder, so
    // Navidrome cannot have scanned it -- and `Imported` is what Task 9 treats as "start looking
    // for it in the mirror". Collapsing the two starts a search that can never succeed.
    "downloaded" -> RequestStatus.Downloading(percentComplete = null)
    "imported" -> RequestStatus.Imported
    // A newer Bindery with a fifth status. `Requested` claims only "we have asked and it is not
    // here yet", which is true of everything short of success.
    else -> RequestStatus.Requested
  }
}
```

`BinderySource.kt` / `BinderyClient.kt` follow `:integrations:lidarr`'s structure exactly:
`BinderyAuthInterceptor` sets `X-Api-Key` and `Accept: application/json`; `call` maps `401` to
`BinderyUnauthorizedException` and anything else unsuccessful to `BinderyHttpException(status)`;
`BinderySourceProvider.current()` returns **`BinderySource?`**, nullable for the same reason
Lidarr's is — a MuPlay with no Bindery is a normal MuPlay.

`submitBook` returns `BinderyBook?`:

> **A decision Step 1 settles.** This plan does **not** know whether the 201 body carries the
> created book's id. If it does, `submitBook` returns the parsed `BinderyBook` and Task 9 stores
> its `id` as the request's `remoteId`. If it does not, `submitBook` returns `null`, and Task 9
> correlates on `foreignBookId` against `GET /api/v1/book` instead — which works regardless, since
> `foreignBookId` is what the user asked for and what the request row already stores. **Implement
> whichever the capture shows, and say which in the task report.** Do not implement both.

- [ ] **Step 7: Run everything**

Run: `./gradlew :integrations:bindery:test :integrations:core:connectedDebugAndroidTest`
Expected: PASS, including Task 2's now-fourth configuration combination.

- [ ] **Step 8: Measure the floor, probe, commit**

Measure `:integrations:bindery`'s BRANCH floor as in Task 4 Step 10 and write the real number.

`ci/mutation-probes.sh`:

```python
BINDERY_STATUS = "integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/BinderyStatusMapper.kt"
BINDERY_CLIENT = "integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/BinderyClient.kt"
```

```python
    # ---- Plan 7: Bindery ----------------------------------------------------------------------
    # The trap: `mediaType` defaults to `ebook`, so a dropped field silently acquires an EPUB.
    ("integrations/bindery-mediaType", BINDERY_CLIENT,
     "mediaType = mediaType.wireValue,", 'mediaType = "ebook",',
     "the media type is always sent, and is audiobook by default", 1),
    ("integrations/bindery-search-term", BINDERY_CLIENT,
     "api.searchBook(term)", 'api.searchBook("dune")',
     "the search parameter is term, and never q", 1),
    ("integrations/bindery-status-downloaded", BINDERY_STATUS,
     '"downloaded" -> RequestStatus.Downloading(percentComplete = null)',
     '"downloaded" -> RequestStatus.Imported',
     "downloaded is progress, not arrival", 2),
    ("integrations/bindery-foreignBookId", BINDERY_CLIENT,
     "foreignBookId = candidate.foreignBookId,", 'foreignBookId = "book-1",',
     "the body carries the book identifier that was asked for, not a constant", 1),
```

```bash
git add settings.gradle.kts build.gradle.kts integrations ci/mutation-probes.sh
git commit -m "feat(bindery): asking for a book, where asking is acquiring

Bindery (vavallee/bindery -- not the archived browser-layout library, not the ebook
converter) is a Readarr replacement, not a request service: there is no request or approval
concept in its router or its roadmap, and adding a book *is* acquiring it. Spec section 8
frames it as a request service and is wrong; Task 11 corrects it. This module models
acquisition rather than inventing a state machine the server does not have.

mediaType is always sent explicitly, because it defaults to ebook server-side -- an omitted
field yields a 201, a happy request row, and an EPUB Navidrome will never scan. The search
parameter is `term`; Bindery's own docs say `q`, and `q` returns 400.

The API key is instance-wide and admin-equivalent (middleware.go; no api_key column in any
of the 75 migrations, so the README's per-account claim is false). Sealed the same way as
the Navidrome password, never logged, never on a URL -- and Bindery rejects a query-string
key on mutations anyway."
```

---

## Task 9: Arrival — the bridge to Navidrome's scan, and the composition that never guesses

**Files:**
- Create: `integrations/requests/build.gradle.kts`
- Create: `integrations/requests/src/main/kotlin/app/muplay/integrations/requests/MirrorPorts.kt`
- Create: `integrations/requests/src/main/kotlin/app/muplay/integrations/requests/TitleMatching.kt`
- Create: `integrations/requests/src/main/kotlin/app/muplay/integrations/requests/RequestArrivalDetector.kt`
- Create: `integrations/requests/src/main/kotlin/app/muplay/integrations/requests/RequestsRepository.kt`
- Create: `integrations/requests/src/main/kotlin/app/muplay/integrations/requests/di/RequestsModule.kt`
- Create: `integrations/requests/src/test/kotlin/app/muplay/integrations/requests/Fakes.kt`
- Create: `integrations/requests/src/test/kotlin/app/muplay/integrations/requests/TitleMatchingTest.kt`
- Create: `integrations/requests/src/test/kotlin/app/muplay/integrations/requests/RequestArrivalDetectorTest.kt`
- Create: `integrations/requests/src/test/kotlin/app/muplay/integrations/requests/RequestsRepositoryTest.kt`
- Modify: `settings.gradle.kts`, `build.gradle.kts`, `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `MediaRequestRepository`, `RequestStatus`, `MediaRequest` (Task 3);
  `LidarrSourceProvider`, `LidarrStatusMapper` (Tasks 4–7); `BinderySourceProvider`,
  `BinderyStatusMapper` (Task 8); `SyncState`, `SearchResults`, `Album`, `LibraryRole` (Plan 2)
- Produces:
  - `fun interface MirrorSync { suspend fun syncIfStale(): SyncState }`
  - `fun interface AlbumSearch { suspend fun search(libraryId: Int, query: String, limit: Int): SearchResults }`
  - `fun interface LibraryRoles { suspend fun idsWithRole(role: LibraryRole): List<Int> }`
  - `object TitleMatching` with `normalise(value: String): String`
  - `class RequestArrivalDetector @Inject constructor(sync, search, roles)` with
    `suspend fun locate(request: MediaRequest): String?`
  - `class RequestsRepository @Inject constructor(requests, credentials, lidarr, bindery, arrival)`
    with `val configuredServices: Flow<Set<IntegrationService>>`,
    `val all: Flow<List<MediaRequest>>`, `suspend fun refresh(): RefreshReport`,
    `suspend fun recordLidarrAdd(...)`, `suspend fun recordBinderyAdd(...)`,
    `suspend fun forget(id: String)`
  - `data class RefreshReport(polled: Int, updated: Int, skippedUnconfigured: Set<IntegrationService>)`

### Why a fifth module, and why no WorkManager

**The fifth module.** This code has to see all three of `:integrations:core`,
`:integrations:lidarr` and `:integrations:bindery`. It cannot live in `:integrations:core` (the two
clients depend on *it*), and it must not live in `:feature:requests` (a Compose feature module is
not where a polling repository belongs, and the project's rule is that repositories are the only
entry point to data). `:integrations:requests` is the composition root of this feature's data
layer, and it is still inside the one directory a `git rm -r` removes.

**No WorkManager, and that is a deliberate scope decision rather than an omission.** The spec's
stack table names WorkManager, but it is **not in `gradle/libs.versions.toml`** — adding it would
break this plan's no-new-dependency rule, and it would buy background polling for an *optional*
feature, at the cost of a battery consideration, a `FOREGROUND_SERVICE` question, and a failure
mode the user cannot see. **Requests refresh when the requests screen is opened, and when the user
pulls to refresh.** For a feature whose entire payoff is "let me check on the thing I asked for",
that is the right amount of machinery. If background polling is ever wanted, it is a plan of its
own with a notification design attached.

### The three ports, and why they are not over-abstraction

`RequestArrivalDetector` needs three things from Plan 2: a sync, a search, and the library roles.
It takes them as three single-method interfaces it declares itself, with production adapters
wired in `RequestsModule`.

This is the same argument `SubsonicSource` makes in `:core:network`, and it is the *only* reason:
**a test needs a specific call to fail at a specific point** — `syncIfStale` returning
`ScanInProgress` must stop the detector before it searches — and `SyncEngine` is a concrete class
with five constructor dependencies including a `SubsonicSourceProvider`. There is no mock framework
in this build and there will not be one. Three `fun interface`s with hand-written fakes is the
smallest thing that makes the whole detector Tier-1 testable.

It also has a severability payoff worth naming: `:integrations:requests` touches `:core:database`
through three methods, and every one of them is read-only. Nothing in this plan can change the
mirror, the watermark, or a library role.

### The matching rule, and the trade it makes explicitly

The question this task answers is: *the service says the files are on disk — is it in Navidrome
yet, and if so, which album is it?* The answer has to come from a title match, and **a title match
is exactly the kind of thing that silently gives a wrong answer.**

The rule:

1. Only a request at `RequestStatus.Imported` is looked for at all. `Downloading` and `Requested`
   have nothing to find; `Arrived` is done.
2. `syncIfStale()` first. `ScanInProgress` or `Failed` → stop, return `null`, try again next
   refresh. **The watermark is Plan 2's and this code never advances it.**
3. Search only the libraries whose **role** fits: `MUSIC` for a Lidarr request, `AUDIOBOOKS` for a
   Bindery one. Spec §4 is emphatic that library id is the only mechanism there is, and a Lidarr
   album matching a book's title in the audiobook library would be exactly the cross-library
   contamination this whole application exists to prevent.
4. A candidate matches only when its **normalised name equals the normalised title** and — when the
   request has a non-blank subtitle — its **normalised artist equals the normalised subtitle**.
5. **Exactly one candidate across all searched libraries, or no answer.** Two matches is not "pick
   the first"; it is "we do not know".

**The trade, stated rather than assumed:** a request that never flips to `Arrived` is a visible,
harmless annoyance — the user can see the album in their library anyway. A request that flips to
the *wrong* album puts a "play it" button that opens something else. So near-misses do not match:
`"Kind of Blue"` and `"Kind of Blue (Remastered)"` are different albums to this code, on purpose,
and `RequestArrivalDetectorTest` asserts that.

Normalisation is deliberately shallow and fully specified: lower-case, Unicode NFD with combining
marks stripped, every non-alphanumeric run collapsed to a single space, trimmed. That makes
`"Hörbücher"` match `"Horbucher"` and `"Sgt. Pepper's"` match `"Sgt Peppers"`, and it does **not**
try to be clever about subtitles, editions or featured artists.

- [ ] **Step 1: Create the module**

`settings.gradle.kts`: `include(":integrations:requests")`

`integrations/requests/build.gradle.kts`:

```kotlin
plugins {
  id("muplay.android.library")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.integrations.requests"
}

dependencies {
  api(project(":integrations:core"))
  api(project(":integrations:lidarr"))
  api(project(":integrations:bindery"))
  // Read-only, and through three single-method ports this module declares itself -- see
  // `MirrorPorts.kt`. Nothing here writes to the mirror, the watermark or a library role.
  implementation(project(":core:database"))

  implementation(libs.coroutines.core)

  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)
}
```

`build.gradle.kts`:

```kotlin
  // `:integrations:requests`. Pure Kotlin composition over hand-written fakes; every branch is
  // reachable from a JVM test, so the whole module is one Tier-1 BRANCH floor. Measured in Step 8.
  ":integrations:requests" to listOf(CoverageFloor(counter = "BRANCH", minimum = BigDecimal("0.90"))),
```

- [ ] **Step 2: Write the failing normalisation test**

`integrations/requests/src/test/kotlin/app/muplay/integrations/requests/TitleMatchingTest.kt`:

```kotlin
package app.muplay.integrations.requests

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TitleMatchingTest {

  @Test
  fun `normalisation is case-insensitive and trims`() {
    assertThat(TitleMatching.normalise("  Kind Of Blue  ")).isEqualTo("kind of blue")
    assertThat(TitleMatching.normalise("BITCHES BREW")).isEqualTo("bitches brew")
  }

  @Test
  fun `diacritics are stripped, which is what makes a german library searchable`() {
    // Spec section 4's own example is "Hörbücher". A user typing either spelling must find the
    // same album, and a request whose title came from Lidarr's metadata may carry either.
    assertThat(TitleMatching.normalise("Hörbücher")).isEqualTo(TitleMatching.normalise("Horbucher"))
    assertThat(TitleMatching.normalise("Café Blue")).isEqualTo("cafe blue")
  }

  @Test
  fun `punctuation collapses to a single space rather than vanishing`() {
    // Vanishing would make "Sgt Peppers" equal to "SgtPeppers", which no server ever sends, and
    // would merge "Vol.1" and "Vol 1" -- the second of which is a real difference between albums.
    assertThat(TitleMatching.normalise("Sgt. Pepper's Lonely Hearts Club Band"))
      .isEqualTo("sgt pepper s lonely hearts club band")
    assertThat(TitleMatching.normalise("A  --  B")).isEqualTo("a b")
  }

  @Test
  fun `two different titles do not normalise to the same string`() {
    // The assertion that makes every one above mean something. Without it a `normalise` returning
    // a constant passes them all.
    assertThat(TitleMatching.normalise("Kind of Blue"))
      .isNotEqualTo(TitleMatching.normalise("Kind of Blue (Remastered)"))
    assertThat(TitleMatching.normalise("Dune")).isNotEqualTo(TitleMatching.normalise("Dune Messiah"))
  }

  @Test
  fun `a blank input normalises to an empty string`() {
    assertThat(TitleMatching.normalise("   ")).isEmpty()
    assertThat(TitleMatching.normalise("!!!")).isEmpty()
  }
}
```

- [ ] **Step 3: Write the failing detector test**

`integrations/requests/src/test/kotlin/app/muplay/integrations/requests/Fakes.kt` — hand-written,
no mock framework anywhere:

```kotlin
package app.muplay.integrations.requests

import app.muplay.database.SyncState
import app.muplay.model.Album
import app.muplay.model.LibraryRole
import app.muplay.model.SearchResults

/**
 * Hand-written fakes. **No mock framework may enter this build** (`ConventionTest`'s
 * `no mock framework is declared in any build file or convention plugin`), and these exist for the
 * one reason the spec's test hierarchy allows a fake at all: making a specific call fail at a
 * specific point, which no real `SyncEngine` can be asked to do.
 *
 * Each records what it was asked, so a test can assert **argument passthrough** rather than
 * "it was called" — which is the defect class round six of this project's reviews found.
 */
class FakeMirrorSync(private var next: SyncState = SyncState.UpToDate) : MirrorSync {
  var calls: Int = 0
    private set

  fun willReturn(state: SyncState) { next = state }

  override suspend fun syncIfStale(): SyncState {
    calls++
    return next
  }
}

class FakeAlbumSearch(private val byLibrary: Map<Int, List<Album>> = emptyMap()) : AlbumSearch {
  /** Every `(libraryId, query, limit)` this was called with, in order. */
  val queries: MutableList<Triple<Int, String, Int>> = mutableListOf()

  override suspend fun search(libraryId: Int, query: String, limit: Int): SearchResults {
    queries += Triple(libraryId, query, limit)
    return SearchResults(artists = emptyList(), albums = byLibrary[libraryId].orEmpty(), songs = emptyList())
  }
}

class FakeLibraryRoles(private val byRole: Map<LibraryRole, List<Int>>) : LibraryRoles {
  val asked: MutableList<LibraryRole> = mutableListOf()

  override suspend fun idsWithRole(role: LibraryRole): List<Int> {
    asked += role
    return byRole[role].orEmpty()
  }
}

fun album(id: String, libraryId: Int, name: String, artist: String?) = Album(
  id = id, libraryId = libraryId, name = name, artistId = null, artistName = artist,
  coverArtId = null, songCount = 1, durationSeconds = 1,
)
```

`RequestArrivalDetectorTest.kt`:

```kotlin
package app.muplay.integrations.requests

import app.muplay.database.SyncState
import app.muplay.integrations.IntegrationService
import app.muplay.integrations.MediaRequest
import app.muplay.integrations.RequestStatus
import app.muplay.model.LibraryRole
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Whether a thing the service finished fetching is visible in Navidrome yet, and which album it is.
 *
 * The design constraint that shapes every test here: **a wrong answer is far worse than no
 * answer.** A request stuck at `Imported` is a harmless annoyance; a request that flips to the
 * wrong album puts a "play it" button on something else.
 */
class RequestArrivalDetectorTest {

  private fun request(
    service: IntegrationService = IntegrationService.LIDARR,
    title: String = "Kind of Blue",
    subtitle: String = "Miles Davis",
    status: RequestStatus = RequestStatus.Imported,
  ) = MediaRequest(
    id = "x", service = service, externalId = "e", title = title, subtitle = subtitle,
    remoteId = null, status = status, requestedAtEpochMs = 0, updatedAtEpochMs = 0,
  )

  private val roles = FakeLibraryRoles(
    mapOf(LibraryRole.MUSIC to listOf(1), LibraryRole.AUDIOBOOKS to listOf(2)),
  )

  @Test
  fun `a matching album in the right library is found and its id returned`() = runTest {
    val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-1", 1, "Kind of Blue", "Miles Davis"))))
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    assertThat(detector.locate(request())).isEqualTo("al-1")
  }

  @Test
  fun `the id returned is the matching album's, not a constant`() = runTest {
    // The second observation. A `locate` returning a fixed id passes the test above.
    val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-99", 1, "Bitches Brew", "Miles Davis"))))
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    assertThat(detector.locate(request(title = "Bitches Brew"))).isEqualTo("al-99")
  }

  @Test
  fun `the search is issued with the request's own title, in the right libraries`() = runTest {
    val search = FakeAlbumSearch()
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    detector.locate(request(title = "Bitches Brew"))

    // Argument passthrough, proven as an exact list rather than "the search was called".
    assertThat(search.queries.map { it.first }).containsExactly(1)
    assertThat(search.queries.map { it.second }).containsExactly("Bitches Brew")
  }

  /**
   * Spec section 4: library id is the only mechanism scoping has. A Bindery request must be looked
   * for in the **audiobook** libraries, and a Lidarr one in the **music** libraries — searching
   * both would let a book with an album's title satisfy a music request, which is the exact
   * cross-library contamination this application exists to prevent.
   */
  @Test
  fun `a bindery request is looked for in the audiobook libraries and a lidarr one in music`() = runTest {
    val search = FakeAlbumSearch()
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    detector.locate(request(service = IntegrationService.LIDARR))
    detector.locate(request(service = IntegrationService.BINDERY))

    // Two observations of the role, and of the library ids that follow from it.
    assertThat(roles.asked).containsExactly(LibraryRole.MUSIC, LibraryRole.AUDIOBOOKS)
    assertThat(search.queries.map { it.first }).containsExactly(1, 2)
  }

  @Test
  fun `a request that is not imported is not looked for at all`() = runTest {
    val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-1", 1, "Kind of Blue", "Miles Davis"))))
    val sync = FakeMirrorSync()
    val detector = RequestArrivalDetector(sync, search, roles)

    // Every status that is not `Imported`, so none of them can slip through individually.
    val notReady = listOf(
      RequestStatus.Requested,
      RequestStatus.Downloading(percentComplete = 50),
      RequestStatus.Arrived(albumId = "already"),
      RequestStatus.Failed("nope"),
    )

    assertThat(notReady.map { detector.locate(request(status = it)) })
      .containsExactly(null, null, null, null)
    // ...and no sync was triggered by any of them. A detector that synced first and filtered
    // afterwards would poll Navidrome on every refresh for every dead request.
    assertThat(sync.calls).isZero()
    assertThat(search.queries).isEmpty()
  }

  @Test
  fun `a scan in progress defers rather than answering`() = runTest {
    val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-1", 1, "Kind of Blue", "Miles Davis"))))
    val sync = FakeMirrorSync(SyncState.ScanInProgress)
    val detector = RequestArrivalDetector(sync, search, roles)

    // The album is right there in the fake mirror, and the answer is still null: mid-scan the
    // mirror is not a fact yet. Stopping before the search is what makes "try again next refresh"
    // correct rather than lucky.
    assertThat(detector.locate(request())).isNull()
    assertThat(search.queries).isEmpty()
  }

  @Test
  fun `a failed sync defers rather than answering`() = runTest {
    val sync = FakeMirrorSync(SyncState.Failed(IllegalStateException("no route")))
    val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-1", 1, "Kind of Blue", "Miles Davis"))))

    assertThat(RequestArrivalDetector(sync, search, roles).locate(request())).isNull()
    assertThat(search.queries).isEmpty()
  }

  @Test
  fun `an up-to-date mirror is searched, and so is one that just synced`() = runTest {
    // Both success states of `SyncState`, so a detector that only accepted one of them fails.
    for (state in listOf(SyncState.UpToDate, SyncState.Synced(emptyMap()))) {
      val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-1", 1, "Kind of Blue", "Miles Davis"))))
      val detector = RequestArrivalDetector(FakeMirrorSync(state), search, roles)
      assertThat(detector.locate(request())).describedAs("%s", state).isEqualTo("al-1")
    }
  }

  /**
   * **The trade, as a test.** A near-miss is not a match. "Kind of Blue (Remastered)" is a
   * different album, and guessing costs the user a button that opens the wrong thing.
   */
  @Test
  fun `a near miss does not match`() = runTest {
    val search = FakeAlbumSearch(
      mapOf(1 to listOf(album("al-1", 1, "Kind of Blue (Remastered)", "Miles Davis"))),
    )

    assertThat(RequestArrivalDetector(FakeMirrorSync(), search, roles).locate(request())).isNull()
  }

  @Test
  fun `a title match with the wrong artist does not match`() = runTest {
    val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-1", 1, "Kind of Blue", "Someone Else"))))

    assertThat(RequestArrivalDetector(FakeMirrorSync(), search, roles).locate(request())).isNull()
  }

  @Test
  fun `a request with no subtitle matches on the title alone`() = runTest {
    // Bindery may not give an author for every book. Requiring an artist match would make those
    // requests never arrive; requiring the title alone is the weakest rule that still discriminates.
    val search = FakeAlbumSearch(mapOf(2 to listOf(album("al-2", 2, "Dune", "Some Narrator"))))
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    assertThat(detector.locate(request(IntegrationService.BINDERY, "Dune", subtitle = "")))
      .isEqualTo("al-2")
  }

  @Test
  fun `two equally good matches is no answer, not the first one`() = runTest {
    val search = FakeAlbumSearch(
      mapOf(
        1 to listOf(
          album("al-1", 1, "Kind of Blue", "Miles Davis"),
          album("al-2", 1, "kind of blue", "miles davis"),
        ),
      ),
    )

    // Ambiguity is a fact about the library, not a tie to break. Picking `al-1` would be right
    // half the time and silently wrong the other half.
    assertThat(RequestArrivalDetector(FakeMirrorSync(), search, roles).locate(request())).isNull()
  }

  @Test
  fun `no library with the right role means no answer and no search`() = runTest {
    // The user tagged no library `Audiobooks`. Searching everything would be the scope leak spec
    // section 4 spends a page on.
    val search = FakeAlbumSearch()
    val detector = RequestArrivalDetector(
      FakeMirrorSync(), search, FakeLibraryRoles(mapOf(LibraryRole.MUSIC to listOf(1))),
    )

    assertThat(detector.locate(request(service = IntegrationService.BINDERY))).isNull()
    assertThat(search.queries).isEmpty()
  }

  @Test
  fun `a match in either of two libraries with the same role is found`() = runTest {
    val search = FakeAlbumSearch(mapOf(3 to listOf(album("al-3", 3, "Kind of Blue", "Miles Davis"))))
    val detector = RequestArrivalDetector(
      FakeMirrorSync(), search, FakeLibraryRoles(mapOf(LibraryRole.MUSIC to listOf(1, 3))),
    )

    assertThat(detector.locate(request())).isEqualTo("al-3")
    // Both were searched, in order -- so a detector that stopped at the first empty library fails.
    assertThat(search.queries.map { it.first }).containsExactly(1, 3)
  }
}
```

- [ ] **Step 4: Implement the ports, the matcher and the detector**

`MirrorPorts.kt`:

```kotlin
package app.muplay.integrations.requests

import app.muplay.database.SyncState
import app.muplay.model.LibraryRole
import app.muplay.model.SearchResults

/**
 * The three things this module needs from Plan 2's data layer, as three single-method interfaces.
 *
 * Declared here rather than consumed directly for one reason, the same one `SubsonicSource` gives:
 * **a test needs a specific call to fail at a specific point** — `syncIfStale` returning
 * `ScanInProgress` must stop the detector before it searches — and `SyncEngine` is a concrete
 * class with five constructor dependencies. There is no mock framework in this build.
 *
 * All three are **read-only**. Nothing in this plan writes the mirror, moves the sync watermark or
 * changes a library role, and these ports are what makes that structural rather than a promise.
 */
fun interface MirrorSync {
  /** Plan 2's `SyncEngine.syncIfStale`. Never called for a request that has nothing to find. */
  suspend fun syncIfStale(): SyncState
}

fun interface AlbumSearch {
  /** Plan 2's `BrowseRepository.search`, scoped to one library. */
  suspend fun search(libraryId: Int, query: String, limit: Int): SearchResults
}

fun interface LibraryRoles {
  /** Plan 2's `LibraryRepository.idsWithRole`. */
  suspend fun idsWithRole(role: LibraryRole): List<Int>
}
```

`TitleMatching.kt`:

```kotlin
package app.muplay.integrations.requests

import java.text.Normalizer

/**
 * The one normalisation both halves of an arrival match go through.
 *
 * Deliberately shallow and fully specified: lower-case, NFD with combining marks stripped, every
 * run of non-alphanumeric characters collapsed to one space, trimmed. It makes `"Hörbücher"` match
 * `"Horbucher"` and `"Sgt. Pepper's"` match `"Sgt Peppers"`, and it makes **no** attempt to be
 * clever about editions, subtitles or featured artists — because every additional cleverness is
 * another way to match the wrong album, and a wrong match is worse than no match here.
 *
 * Punctuation collapses to a space rather than vanishing: vanishing would equate `"Vol.1"` and
 * `"Vol 1"`, which is fine, but also `"Sgt Peppers"` and `"SgtPeppers"`, which no server sends,
 * and it would merge titles that differ only in spacing.
 */
object TitleMatching {

  private val NON_ALPHANUMERIC = Regex("[^\\p{Alnum}]+")
  private val COMBINING_MARKS = Regex("\\p{Mn}+")

  fun normalise(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
      .replace(COMBINING_MARKS, "")
      .lowercase()
      .replace(NON_ALPHANUMERIC, " ")
      .trim()
}
```

`RequestArrivalDetector.kt`:

```kotlin
package app.muplay.integrations.requests

import app.muplay.database.SyncState
import app.muplay.integrations.IntegrationService
import app.muplay.integrations.MediaRequest
import app.muplay.integrations.RequestStatus
import app.muplay.model.Album
import app.muplay.model.LibraryRole
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers one question: the service says it has the files — is it in Navidrome yet, and which
 * album is it?
 *
 * Returns `null` for every kind of "we do not know", and there are five of them: the request is
 * not ready, the mirror is mid-scan, the sync failed, no library carries the right role, and the
 * match is ambiguous. **None of them is a failure.** Each simply means "ask again next refresh",
 * which is correct because the answer genuinely changes over time.
 *
 * The single design rule, stated once: **a wrong answer is worse than no answer.** A request stuck
 * at `Imported` is a harmless annoyance the user can route around by opening their library; a
 * request that flips to the wrong album puts a "play it" button on something else.
 */
@Singleton
class RequestArrivalDetector @Inject constructor(
  private val sync: MirrorSync,
  private val search: AlbumSearch,
  private val roles: LibraryRoles,
) {

  suspend fun locate(request: MediaRequest): String? {
    // Filter *before* syncing. A detector that synced first would poll Navidrome on every refresh
    // for every request that has nothing to find.
    if (request.status != RequestStatus.Imported) return null

    // Mid-scan the mirror is not yet a fact, and a failed sync did not advance the watermark, so
    // in both cases the honest answer is "not yet".
    when (sync.syncIfStale()) {
      SyncState.ScanInProgress -> return null
      is SyncState.Failed -> return null
      SyncState.UpToDate, is SyncState.Synced -> Unit
    }

    // Spec section 4: library id is the only scoping mechanism there is. A book must not be found
    // by a music request, and vice versa.
    val libraryIds = roles.idsWithRole(roleFor(request.service))
    if (libraryIds.isEmpty()) return null

    val matches = libraryIds
      .flatMap { libraryId -> search.search(libraryId, request.title, SEARCH_LIMIT).albums }
      .filter { album -> matches(album, request) }
      .map(Album::id)
      .distinct()

    // Exactly one, or nothing. Two matches is a fact about the library, not a tie to break.
    return matches.singleOrNull()
  }

  private fun matches(album: Album, request: MediaRequest): Boolean {
    if (TitleMatching.normalise(album.name) != TitleMatching.normalise(request.title)) return false
    // A blank subtitle means the service did not tell us an author or artist -- common for Bindery
    // -- so the title alone has to do. Requiring an artist match would make those never arrive.
    if (request.subtitle.isBlank()) return true
    return TitleMatching.normalise(album.artistName.orEmpty()) ==
      TitleMatching.normalise(request.subtitle)
  }

  private fun roleFor(service: IntegrationService): LibraryRole = when (service) {
    IntegrationService.LIDARR -> LibraryRole.MUSIC
    IntegrationService.BINDERY -> LibraryRole.AUDIOBOOKS
  }

  private companion object {
    /**
     * Enough rows to contain every album whose title matches, small enough that a two-word title
     * does not drag a page of the mirror into memory. The match is exact after normalisation, so
     * a bigger page would not find anything a smaller one misses unless the library holds more
     * than this many *substring* matches for one title.
     */
    const val SEARCH_LIMIT = 50
  }
}
```

- [ ] **Step 5: Write the failing repository test — all four configuration combinations**

`RequestsRepositoryTest.kt` (abridged to the assertions that carry the design; write the rest by
the same rules):

```kotlin
  /**
   * **The severability contract, at the data layer.** A user with only Lidarr configured must
   * cause **zero** Bindery traffic — not "the Bindery UI was not shown", but that no call was made
   * at all. The fake counts calls; `isZero()` is the assertion.
   */
  @Test
  fun `refresh polls only the services that are configured`() = runTest {
    credentials.save(lidarrCredentials)          // Bindery deliberately not configured
    requests.record(IntegrationService.LIDARR, "mbid", "A", "x", remoteId = "7")
    requests.record(IntegrationService.BINDERY, "book", "B", "y", remoteId = "9")

    val report = repository.refresh()

    assertThat(lidarrSource.queueCalls).isEqualTo(1)
    assertThat(binderySource.bookCalls).isZero()
    assertThat(report.skippedUnconfigured).containsExactly(IntegrationService.BINDERY)
    // The Bindery row is left exactly as it was rather than being marked failed: "we did not
    // ask" is not "it went wrong".
    assertThat(requests.requests(IntegrationService.BINDERY).first().single().status)
      .isEqualTo(RequestStatus.Requested)
  }

  @Test
  fun `refresh with nothing configured touches nothing and reports both as skipped`() = runTest {
    // The path a real user is permanently on until they configure something. It must not throw,
    // must issue no HTTP, and must not rewrite any row.
    requests.record(IntegrationService.LIDARR, "mbid", "A", "x", remoteId = "7")

    val report = repository.refresh()

    assertThat(lidarrSource.queueCalls).isZero()
    assertThat(binderySource.bookCalls).isZero()
    assertThat(report.polled).isZero()
    assertThat(report.skippedUnconfigured)
      .containsExactlyInAnyOrder(IntegrationService.LIDARR, IntegrationService.BINDERY)
  }

  @Test
  fun `refresh updates the row whose remote id it polled, and not another`() = runTest {
    // The delegating-argument rule again, one level up: a refresh that wrote its result to the
    // first row rather than the matching one passes a single-row test.
    credentials.save(lidarrCredentials)
    requests.record(IntegrationService.LIDARR, "mbid-1", "A", "x", remoteId = "7")
    requests.record(IntegrationService.LIDARR, "mbid-2", "B", "y", remoteId = "8")
    lidarrSource.queue = listOf(queueItem(albumId = 8, state = "downloading", size = 100.0, left = 25.0))

    repository.refresh()

    val byId = requests.requests().first().associateBy { it.externalId }
    assertThat(byId.getValue("mbid-2").status).isEqualTo(RequestStatus.Downloading(75))
    assertThat(byId.getValue("mbid-1").status).isEqualTo(RequestStatus.Requested)
  }

  @Test
  fun `an imported request that the detector locates becomes Arrived with that album id`() = runTest {
    credentials.save(lidarrCredentials)
    requests.record(IntegrationService.LIDARR, "mbid-1", "Kind of Blue", "Miles Davis", remoteId = "7")
    lidarrSource.albumProgress = LidarrAlbumProgress(trackFileCount = 5, totalTrackCount = 5)
    albumSearch = FakeAlbumSearch(mapOf(1 to listOf(album("al-1", 1, "Kind of Blue", "Miles Davis"))))

    repository.refresh()

    assertThat(requests.requests().first().single().status)
      .isEqualTo(RequestStatus.Arrived(albumId = "al-1"))
  }

  @Test
  fun `an imported request the detector cannot locate stays Imported, not Failed`() = runTest {
    // The user can still find it in their library; telling them it failed would be false.
    credentials.save(lidarrCredentials)
    requests.record(IntegrationService.LIDARR, "mbid-1", "Kind of Blue", "Miles Davis", remoteId = "7")
    lidarrSource.albumProgress = LidarrAlbumProgress(5, 5)

    repository.refresh()

    assertThat(requests.requests().first().single().status).isEqualTo(RequestStatus.Imported)
  }

  @Test
  fun `a request whose service throws leaves every other request updated`() = runTest {
    // One dead service must not stop the other from refreshing. This is the "fail closed, never
    // block core playback" rule of spec section 8, applied within the feature itself.
    credentials.save(lidarrCredentials)
    credentials.save(binderyCredentials)
    lidarrSource.failWith = LidarrHttpException(500)
    requests.record(IntegrationService.LIDARR, "mbid", "A", "x", remoteId = "7")
    requests.record(IntegrationService.BINDERY, "book", "B", "y", remoteId = "9")
    binderySource.books = listOf(BinderyBook(id = "9", foreignBookId = "book", title = "B", status = "imported"))

    val report = repository.refresh()

    assertThat(requests.requests(IntegrationService.BINDERY).first().single().status)
      .isEqualTo(RequestStatus.Imported)
    assertThat(report.polled).isEqualTo(1)
  }

  @Test
  fun `configuredServices reports exactly what is configured, and changes when it changes`() = runTest {
    // All four combinations through one flow, in order. This is what Task 10's UI decides
    // whether to render anything at all from.
    assertThat(repository.configuredServices.first()).isEmpty()
    credentials.save(lidarrCredentials)
    assertThat(repository.configuredServices.first()).containsExactly(IntegrationService.LIDARR)
    credentials.save(binderyCredentials)
    assertThat(repository.configuredServices.first())
      .containsExactlyInAnyOrder(IntegrationService.LIDARR, IntegrationService.BINDERY)
    credentials.clear(IntegrationService.LIDARR)
    assertThat(repository.configuredServices.first()).containsExactly(IntegrationService.BINDERY)
  }
```

> **`IntegrationCredentialStore` is device-only** (it reaches `AndroidKeystore`). To keep
> `RequestsRepositoryTest` in Tier 1, have `RequestsRepository` depend on a
> `fun interface ConfiguredServices { val configured: Flow<Map<IntegrationService, IntegrationCredentials>> }`
> — a fourth port, in `MirrorPorts.kt`, with the production adapter binding
> `IntegrationCredentialStore::configured` in `RequestsModule`. The same argument as the other
> three, and it is what makes the four-combination test a JVM test rather than an emulator one.
> **State in the task report that you did this**, because it is a design decision this plan is
> making one level later than the others.

- [ ] **Step 6: Implement `RequestsRepository` and `RequestsModule`**

`RequestsRepository` composes, and its shape is fixed by the tests above:

- `configuredServices: Flow<Set<IntegrationService>>` = the port's map's keys.
- `all: Flow<List<MediaRequest>>` = `MediaRequestRepository.requests()`.
- `refresh(): RefreshReport`:
  1. Read the configured map once.
  2. For each service **that is configured**, fetch its state in one call —
     `LidarrSource.queue()` for Lidarr, `BinderySource.books(status = null)` for Bindery —
     inside a `runCatching`, so one dead service does not stop the other.
  3. For each stored request of that service, compute its new status
     (`LidarrStatusMapper.map(queueItemFor(remoteId), albumProgress)` /
     `BinderyStatusMapper.map(book.status)`), and `setStatus` **only when it changed** — an
     unconditional write would move `updatedAt` on every refresh and make "last updated" useless.
  4. For each request now at `Imported`, call `RequestArrivalDetector.locate` and, on a non-null
     result, `setStatus(id, RequestStatus.Arrived(albumId))`.
  5. Return `RefreshReport(polled, updated, skippedUnconfigured)`.
- `recordLidarrAdd` / `recordBinderyAdd` call `MediaRequestRepository.record` with the ids the
  submit returned.
- `forget(id)` delegates.

`RequestsModule` binds the four ports to the real Plan 2 collaborators:

```kotlin
  @Provides fun provideMirrorSync(engine: SyncEngine): MirrorSync = MirrorSync { engine.syncIfStale() }

  @Provides fun provideAlbumSearch(browse: BrowseRepository): AlbumSearch =
    AlbumSearch { libraryId, query, limit -> browse.search(libraryId, query, limit) }

  @Provides fun provideLibraryRoles(libraries: LibraryRepository): LibraryRoles =
    LibraryRoles { role -> libraries.idsWithRole(role) }

  @Provides fun provideConfiguredServices(store: IntegrationCredentialStore): ConfiguredServices =
    ConfiguredServices { store.configured }
```

> **Check these signatures against Plan 2 before writing them.** `SyncEngine.syncIfStale()`,
> `BrowseRepository.search(libraryId, query, limit)` and `LibraryRepository.idsWithRole(role)` are
> taken from `docs/superpowers/plans/2026-08-24-muplay-k02-library-browse.md`'s Interfaces blocks.
> If any differs in the tree, use the real one and say so in the task report — do **not** add a
> second search or a second sync engine.

- [ ] **Step 7: Run, measure, probe, commit**

Run: `./gradlew :integrations:requests:test`
Expected: PASS. Measure the module's BRANCH floor and write the real number.

`ci/mutation-probes.sh`:

```python
DETECTOR = "integrations/requests/src/main/kotlin/app/muplay/integrations/requests/RequestArrivalDetector.kt"
```

```python
    # ---- Plan 7: arrival -- where a wrong answer is worse than none ---------------------------
    ("integrations/arrival-role-scope", DETECTOR,
     "IntegrationService.BINDERY -> LibraryRole.AUDIOBOOKS",
     "IntegrationService.BINDERY -> LibraryRole.MUSIC",
     "a bindery request is looked for in the audiobook libraries and a lidarr one in music", 1),
    ("integrations/arrival-single-match", DETECTOR,
     "return matches.singleOrNull()", "return matches.firstOrNull()",
     "two equally good matches is no answer, not the first one", 1),
    ("integrations/arrival-title-passthrough", DETECTOR,
     "search.search(libraryId, request.title, SEARCH_LIMIT)",
     'search.search(libraryId, "Kind of Blue", SEARCH_LIMIT)',
     "the search is issued with the request's own title, in the right libraries", 1),
    ("integrations/arrival-scan-in-progress", DETECTOR,
     "SyncState.ScanInProgress -> return null", "SyncState.ScanInProgress -> Unit",
     "a scan in progress defers rather than answering", 1),
]
```

```bash
git add settings.gradle.kts build.gradle.kts integrations/requests ci/mutation-probes.sh
git commit -m "feat(integrations): arrival detection, which declines to guess

The service saying it has the files is not Navidrome having scanned them. This closes that
gap: sync, then search only the libraries whose role fits the service (spec section 4 --
library id is the only scoping mechanism there is), and match on an exactly-normalised title
plus artist. Exactly one match or no answer; a near miss does not match, on purpose.

The trade is stated as a test: a request stuck at Imported is a harmless annoyance, and a
request that flips to the wrong album puts a play button on something else.

No WorkManager -- it is not in the version catalogue and this plan adds no dependency.
Requests refresh when the screen is opened. Four hand-written ports keep the whole module
Tier-1 testable and keep every touch of Plan 2's data layer read-only."
```

---

## Task 10: `:feature:requests` — a surface that is absent, not empty

**Files:**
- Create: `feature/requests/build.gradle.kts`
- Create: `feature/requests/src/main/kotlin/app/muplay/requests/ConnectionCheck.kt`
- Create: `feature/requests/src/main/kotlin/app/muplay/requests/IntegrationSetupUiState.kt`
- Create: `feature/requests/src/main/kotlin/app/muplay/requests/IntegrationSetupViewModel.kt`
- Create: `feature/requests/src/main/kotlin/app/muplay/requests/IntegrationSetupScreen.kt`
- Create: `feature/requests/src/main/kotlin/app/muplay/requests/IntegrationsSettingsScreen.kt`
- Create: `feature/requests/src/main/kotlin/app/muplay/requests/RequestsUiState.kt`
- Create: `feature/requests/src/main/kotlin/app/muplay/requests/RequestsViewModel.kt`
- Create: `feature/requests/src/main/kotlin/app/muplay/requests/RequestsScreen.kt`
- Create: `feature/requests/src/test/kotlin/app/muplay/requests/ConnectionCheckTest.kt`
- Create: `feature/requests/src/test/kotlin/app/muplay/requests/IntegrationSetupViewModelTest.kt`
- Create: `feature/requests/src/test/kotlin/app/muplay/requests/RequestsViewModelTest.kt`
- Modify: `app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt`
- Create: `app/src/main/kotlin/app/muplay/ui/navigation/RequestsRoute.kt`
- Modify: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`

**Interfaces:**
- Consumes: `RequestsRepository`, `IntegrationService`, `IntegrationBaseUrl`, `BaseUrlResult`,
  `CleartextPolicy`, `MediaRequest`, `RequestStatus`, `LidarrSource`, `BinderySource`
- Produces:
  - `sealed interface ConnectionCheck` with `data class Ok(val description: String)`,
    `data object Unreachable`, `data object Unauthorized`,
    `data class WrongApplication(val appName: String)`, `data class Failed(val detail: String)`
  - `data class IntegrationSetupUiState(service, urlText, keyText, urlError, check, saving, saved)`
  - `class IntegrationSetupViewModel @Inject constructor(...)`
  - `sealed interface RequestsUiState` with `data object NotConfigured`,
    `data class Ready(services: Set<IntegrationService>, requests: List<MediaRequest>, searching: Boolean, results: List<RequestCandidate>, error: String?)`
  - `data class RequestCandidate(service, externalId, title, subtitle, coverUrl, alreadyAdded)`
  - `@Composable fun RequestsScreen(...)`, `@Composable fun IntegrationSetupScreen(...)`,
    `@Composable fun IntegrationsSettingsScreen(...)`
  - `app/…/navigation/RequestsRoute.kt` — the two destinations

### The one piece of always-present UI, and why it is exactly one

The plan's severability contract says a user who runs neither service must see **no dead UI**. It
also has to be possible to turn the feature on, and a feature with zero affordance is unreachable.
Those pull against each other, and the resolution is a line drawn in a specific place:

- **The feature surface is absent when nothing is configured.** No requests list, no empty state
  saying "no requests yet", no search field, no disabled button, and **no requests destination in
  the navigation graph at all**. `RequestsUiState.NotConfigured` renders nothing and the route is
  not registered.
- **One settings row turns it on.** A row reading *"Integrations"* in the app's settings surface —
  not a feature, a switch. That is the same category of thing as the server-URL field: present
  because the app has to be configurable, not because a feature is on.

**Where that row goes depends on what exists when this task runs.** Spec §9 names a
`feature/settings` module; it does not exist in the tree as this plan is written, and no plan
before this one creates it. So:

- **If a settings screen exists**, add one row to it that navigates to `IntegrationsSettingsScreen`,
  and nothing else.
- **If it does not**, add an overflow menu item on the library screen's top app bar, labelled
  *"Integrations"*, that navigates to the same screen.

Say in the task report which one you did. Either way, **exactly one always-present affordance**,
and Task 11's emulator journey asserts that a device with nothing configured shows no requests
surface anywhere.

### The connection check, which must not lie in four different ways

*"Test connection"* is the one place this feature can be genuinely helpful or genuinely
misleading, because there are four distinct things that can be wrong and they need four distinct
messages:

| Outcome | How it is detected | Why it is separate |
|---|---|---|
| `Unreachable` | the unauthenticated ping/health call fails or is falsy | Nothing is listening. Telling the user their key is wrong sends them to regenerate a perfectly good key. |
| `Unauthorized` | ping succeeds, the authenticated call throws `*UnauthorizedException` | Something *is* there. **The message must not claim the key is wrong rather than missing** — Lidarr returns a bare 401 for both, and this client does not know which. |
| `WrongApplication` | the authenticated call succeeds but `appName != "Lidarr"` | The single most likely real mistake: pasting a Sonarr or Radarr URL. `/ping` is byte-identical across all the Servarr apps, so without this check the user gets a green tick and then a stream of 404s. |
| `Ok` | everything succeeded | — |

Everything else is `Failed(detail)` carrying whatever the exception said.

`ConnectionCheckTest` asserts **all five members as one exact mapped list**, from five different
fake sources. A connection check observed at one outcome is a connection check that has not been
tested, and this one is a five-way branch.

**Bindery has no `appName` to check**, because its `/api/v1/health` reports only `status` and
`version`. So `WrongApplication` is unreachable for Bindery, and the plan says so rather than
letting an untestable branch sit there: `ConnectionCheck.of(...)` takes the expected application
name as a **nullable** parameter, `null` means "this service does not identify itself", and both
paths are tested.

- [ ] **Step 1: Write the failing connection-check test**

```kotlin
package app.muplay.requests

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * All five outcomes of "test connection", as one exact list.
 *
 * Five separate messages, because four separate things can be wrong and sending a user to
 * regenerate a working API key because nothing was listening is the kind of unhelpfulness that
 * makes people give up on a feature.
 */
class ConnectionCheckTest {

  private object Boom : Exception("no route")

  @Test
  fun `each way a connection can go maps to its own outcome`() {
    val outcomes = listOf(
      // reachable=false: nothing is listening.
      ConnectionCheck.of(reachable = false, identity = null, failure = null, expectedAppName = "Lidarr"),
      // reachable, but the authenticated call was refused.
      ConnectionCheck.of(true, null, failure = Unauthorized, expectedAppName = "Lidarr"),
      // reachable and authenticated, but it is a Sonarr.
      ConnectionCheck.of(true, identity = "Sonarr", failure = null, expectedAppName = "Lidarr"),
      // reachable and authenticated and right.
      ConnectionCheck.of(true, identity = "Lidarr", failure = null, expectedAppName = "Lidarr"),
      // reachable, and something else went wrong entirely.
      ConnectionCheck.of(true, null, failure = Boom, expectedAppName = "Lidarr"),
    )

    assertThat(outcomes).containsExactly(
      ConnectionCheck.Unreachable,
      ConnectionCheck.Unauthorized,
      ConnectionCheck.WrongApplication(appName = "Sonarr"),
      ConnectionCheck.Ok(description = "Lidarr"),
      ConnectionCheck.Failed(detail = "no route"),
    )
  }

  @Test
  fun `the wrong-application outcome names what was actually found`() {
    // Two observations. "This is not Lidarr" is much less useful than "this is a Radarr".
    assertThat(ConnectionCheck.of(true, "Radarr", null, "Lidarr"))
      .isEqualTo(ConnectionCheck.WrongApplication("Radarr"))
    assertThat(ConnectionCheck.of(true, "Prowlarr", null, "Lidarr"))
      .isEqualTo(ConnectionCheck.WrongApplication("Prowlarr"))
  }

  /**
   * Bindery's `/api/v1/health` reports `status` and `version` and nothing that names the
   * application, so there is no identity to check. `null` says that explicitly, rather than
   * leaving a branch that can never be `false`.
   */
  @Test
  fun `a service that does not identify itself skips the identity check entirely`() {
    assertThat(ConnectionCheck.of(true, identity = "anything at all", failure = null, expectedAppName = null))
      .isEqualTo(ConnectionCheck.Ok(description = "anything at all"))
    assertThat(ConnectionCheck.of(true, identity = null, failure = null, expectedAppName = null))
      .isInstanceOf(ConnectionCheck.Ok::class.java)
  }

  @Test
  fun `the identity match is case-insensitive`() {
    assertThat(ConnectionCheck.of(true, "lidarr", null, "Lidarr"))
      .isEqualTo(ConnectionCheck.Ok(description = "lidarr"))
  }

  @Test
  fun `a failure with no message still produces a message`() {
    // `Failed("")` renders as a blank line under the field, which reads as a UI bug.
    assertThat(ConnectionCheck.of(true, null, failure = Exception(), expectedAppName = null))
      .isInstanceOf(ConnectionCheck.Failed::class.java)
    assertThat((ConnectionCheck.of(true, null, Exception(), null) as ConnectionCheck.Failed).detail)
      .isNotBlank()
  }
}
```

`Unauthorized` in the test above is a stand-in: use `LidarrUnauthorizedException()` and
`BinderyUnauthorizedException()` as two of the inputs, and assert **both** map to
`ConnectionCheck.Unauthorized` — that is the second observation of that branch and it proves the
check is not hard-wired to one service's exception type.

- [ ] **Step 2: Write the failing requests-view-model test — all four combinations**

```kotlin
  @Test
  fun `nothing configured is NotConfigured, and nothing else`() = runTest {
    // Not `Ready(emptySet(), emptyList())`. The distinction is the whole severability contract:
    // `Ready` renders a screen and `NotConfigured` renders nothing, and a `Ready` with empty
    // sets would render an empty screen -- exactly the dead UI this plan forbids.
    assertThat(viewModel.uiState.value).isEqualTo(RequestsUiState.NotConfigured)
  }

  @Test
  fun `one configured service is Ready with only that service`() = runTest {
    repository.configure(IntegrationService.LIDARR)
    viewModel.uiState.test {
      assertThat((awaitItem() as RequestsUiState.Ready).services)
        .containsExactly(IntegrationService.LIDARR)
    }
  }

  @Test
  fun `the other configured service alone is Ready with only the other service`() = runTest {
    // The second observation, and the one a test suite that always configures Lidarr never makes.
    repository.configure(IntegrationService.BINDERY)
    viewModel.uiState.test {
      assertThat((awaitItem() as RequestsUiState.Ready).services)
        .containsExactly(IntegrationService.BINDERY)
    }
  }

  @Test
  fun `both configured is Ready with both, in declaration order`() = runTest {
    repository.configure(IntegrationService.LIDARR)
    repository.configure(IntegrationService.BINDERY)
    viewModel.uiState.test {
      // Order is a property: `IntegrationService.entries` order is what every list renders in, so
      // both screens agree without either sorting.
      assertThat((awaitItem() as RequestsUiState.Ready).services.toList())
        .containsExactly(IntegrationService.LIDARR, IntegrationService.BINDERY)
    }
  }

  @Test
  fun `removing the last configured service returns the state to NotConfigured`() = runTest {
    // The reverse transition. Without it, a view model that computed `NotConfigured` only at
    // construction would pass every test above and then leave a dead screen behind after the user
    // removed their last integration.
    repository.configure(IntegrationService.LIDARR)
    viewModel.uiState.test {
      assertThat(awaitItem()).isInstanceOf(RequestsUiState.Ready::class.java)
      repository.unconfigure(IntegrationService.LIDARR)
      assertThat(awaitItem()).isEqualTo(RequestsUiState.NotConfigured)
    }
  }

  @Test
  fun `a search runs only against the services that are configured`() = runTest {
    repository.configure(IntegrationService.LIDARR)

    viewModel.search("blue")

    // Exact mapped list: the candidates come back tagged with their service, and a Bindery
    // candidate appearing here would mean a search was issued against an unconfigured service.
    assertThat(viewModel.uiState.value.let { (it as RequestsUiState.Ready).results }.map { it.service })
      .containsOnly(IntegrationService.LIDARR)
    assertThat(repository.binderySearches).isZero()
  }

  @Test
  fun `requesting a candidate records it and the list shows it`() = runTest {
    repository.configure(IntegrationService.LIDARR)
    viewModel.search("blue")

    viewModel.request(candidate(externalId = "mbid-1", title = "Kind of Blue"))

    // The identifier, not "a request was made". Same rule as every submit test in this plan.
    assertThat(repository.submitted.map { it.externalId }).containsExactly("mbid-1")
  }
```

- [ ] **Step 3: Implement `ConnectionCheck`**

```kotlin
package app.muplay.requests

import app.muplay.integrations.bindery.BinderyUnauthorizedException
import app.muplay.integrations.lidarr.LidarrUnauthorizedException

/**
 * The outcome of "test connection", with one member per thing that can actually be wrong.
 *
 * Five members rather than success-or-failure, because the *advice* differs: an unreachable host
 * and a rejected key send the user to completely different places, and a Sonarr URL pasted into
 * the Lidarr field is the single most likely real mistake — `/ping` is byte-identical across every
 * Servarr application, so without an identity check it produces a green tick and then a stream of
 * 404s.
 */
sealed interface ConnectionCheck {

  /** [description] is whatever the service called itself, shown back to the user as confirmation. */
  data class Ok(val description: String) : ConnectionCheck

  /** Nothing answered the unauthenticated ping. The key is not the problem and must not be blamed. */
  data object Unreachable : ConnectionCheck

  /**
   * Something is there and refused us.
   *
   * The message this renders must **not** say the key is wrong: Lidarr returns a bare 401 with an
   * empty body for a missing key and a wrong key alike, so "wrong" is a guess. "Rejected" is what
   * is known.
   */
  data object Unauthorized : ConnectionCheck

  /** Reachable, authenticated, and a different application. [appName] is what it called itself. */
  data class WrongApplication(val appName: String) : ConnectionCheck

  data class Failed(val detail: String) : ConnectionCheck

  companion object {

    /**
     * Decides the outcome from three observations.
     *
     * [expectedAppName] is `null` for a service that does not identify itself — Bindery's
     * `/api/v1/health` reports `status` and `version` and nothing else — and that is written as a
     * nullable parameter rather than a second code path so the "no identity to check" case is a
     * tested value rather than an unreachable branch.
     */
    fun of(
      reachable: Boolean,
      identity: String?,
      failure: Throwable?,
      expectedAppName: String?,
    ): ConnectionCheck = when {
      !reachable -> Unreachable
      failure is LidarrUnauthorizedException || failure is BinderyUnauthorizedException -> Unauthorized
      failure != null -> Failed(failure.message?.takeIf { it.isNotBlank() } ?: "the connection failed")
      expectedAppName != null && identity != null && !identity.equals(expectedAppName, ignoreCase = true) ->
        WrongApplication(identity)
      else -> Ok(description = identity ?: expectedAppName ?: "connected")
    }
  }
}
```

- [ ] **Step 4: Implement the ui state, the view models and the screens**

`RequestsUiState.kt`:

```kotlin
package app.muplay.requests

import app.muplay.integrations.IntegrationService
import app.muplay.integrations.MediaRequest

/**
 * What the requests screen shows.
 *
 * **[NotConfigured] renders nothing at all** — not an empty list, not a "no requests yet" card,
 * not a "set up an integration" prompt. The plan's severability contract is explicit: a user who
 * runs neither service sees no degradation and no dead UI, and the one affordance that turns the
 * feature on is a settings row, not a screen.
 */
sealed interface RequestsUiState {

  data object NotConfigured : RequestsUiState

  data class Ready(
    /** In `IntegrationService.entries` order, so every list agrees without any screen sorting. */
    val services: Set<IntegrationService>,
    val requests: List<MediaRequest>,
    val searching: Boolean,
    val results: List<RequestCandidate>,
    val error: String?,
  ) : RequestsUiState
}

/** One searchable thing, from either service, in the one shape the list renders. */
data class RequestCandidate(
  val service: IntegrationService,
  val externalId: String,
  val title: String,
  val subtitle: String,
  val coverUrl: String?,
  val alreadyAdded: Boolean,
)
```

`RequestsViewModel` collects `RequestsRepository.configuredServices` and `.all`, maps them into the
state, calls `refresh()` on `init` and on pull-to-refresh, debounces `search()` (**250 ms**, because
a Lidarr lookup is a proxied call to `api.lidarr.audio` that is rate-limited upstream), and fans a
search out only across configured services.

`IntegrationSetupViewModel` holds the URL and key text, calls
`IntegrationBaseUrl.parse(urlText, cleartextPolicy)` on every change and surfaces
`BaseUrlResult.message(service)`, runs `ConnectionCheck` on demand, and saves through
`IntegrationCredentialStore` only when the URL parsed and the check was `Ok`.

The three Composables are ordinary Material 3 screens. They carry `testTag`s, because Task 11's
emulator journey asserts on them:

| `testTag` | On |
|---|---|
| `requests:root` | the requests screen's root — **must not exist when nothing is configured** |
| `requests:section:LIDARR`, `requests:section:BINDERY` | each service's section |
| `requests:search` | the search field |
| `requests:candidate:<externalId>` | one search result row |
| `requests:row:<requestId>` | one stored request row |
| `requests:status:<requestId>` | that row's status text |
| `settings:integrations` | the always-present settings row |
| `setup:url`, `setup:key`, `setup:test`, `setup:save`, `setup:error` | the setup form |

- [ ] **Step 5: Register the destination — conditionally**

`app/src/main/kotlin/app/muplay/ui/navigation/RequestsRoute.kt` declares the two Navigation 3
destinations. `MuPlayApp` collects `RequestsRepository.configuredServices` and:

- **always** provides the `IntegrationsSettingsScreen` destination and the one affordance that
  reaches it (a settings row, or the library app bar's overflow item — see this task's opening
  section);
- registers the **requests** destination only when the set is non-empty.

```kotlin
  // Registered only when at least one integration is configured. Not "registered and hidden": a
  // destination that exists is reachable by deep link, by a restored back stack and by a stale
  // navigation event, and each of those would land a user who runs neither service on a screen
  // for a feature they do not have. `ConventionTest`'s severability rule keeps this the only
  // edge from :app into integrations/.
  if (configuredServices.isNotEmpty()) {
    entry<RequestsRoute> { RequestsScreen(onOpenAlbum = onOpenAlbum) }
  }
```

- [ ] **Step 6: Run, measure, commit**

Run: `./gradlew :feature:requests:test`
Expected: PASS.

`:feature:requests`'s floors are **two** entries, as `:feature:setup`'s are: a BRANCH floor over
`ConnectionCheck*`, `RequestsUiState*`, `RequestsViewModel` and `IntegrationSetupViewModel`
(Tier 1, measured from the JVM run), and a **LINE** floor with `requiresInstrumentedData = true`
over `RequestsScreenKt`, `IntegrationSetupScreenKt` and `IntegrationsSettingsScreenKt` — Compose
code measures ~0% without a real composition, and Task 11's journey is what composes it. Measure
both; do not invent either.

```bash
git add settings.gradle.kts build.gradle.kts app feature/requests
git commit -m "feat(requests): a surface that is absent rather than empty

RequestsUiState.NotConfigured renders nothing, and the requests destination is not
registered at all when no integration is configured -- not registered-and-hidden, because a
registered destination is reachable by deep link, by a restored back stack and by a stale
navigation event. The one always-present affordance is a settings row, which is a switch and
not a feature.

Test connection has five outcomes because four separate things can be wrong: nothing
listening, a rejected key, a Sonarr URL in the Lidarr field (/ping is byte-identical across
every Servarr app), and everything else. The rejected-key message does not claim the key is
wrong, because a bare 401 cannot tell us whether it was wrong or missing."
```

---

## Task 11: The gates — real containers, a journey with nothing configured, and the spec correction

**Files:**
- Create: `ci/lidarr.compose.yml`, `ci/configure-lidarr.sh`
- Create: `ci/bindery.compose.yml`, `ci/configure-bindery.sh`
- Create: `integrations/lidarr/src/test/kotlin/app/muplay/integrations/lidarr/LiveLidarrTest.kt`
- Create: `integrations/bindery/src/test/kotlin/app/muplay/integrations/bindery/LiveBinderyTest.kt`
- Create: `app/src/androidTest/kotlin/app/muplay/IntegrationsJourneyTest.kt`
- Modify: `build-logic/convention/src/main/kotlin/Testing.kt` — generalise the live-task carve-out
- Modify: `build.gradle.kts` — the two new live test tasks; the measured coverage floors
- Modify: `app/src/test/kotlin/app/muplay/ConventionTest.kt` — extend the drift rule
- Modify: `.github/workflows/pr.yml`, `.github/workflows/e2e.yml`
- Modify: `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md` — §8, and §12
- Modify: `ci/mutation-probes.sh`

### Why a live container, and what it can and cannot cover

Spec §10's countermeasure 2 is *"a real server… anything whose subject is Navidrome's behaviour is
tested against a pinned container, not a fixture."* The same argument applies to Lidarr and
Bindery, and Plan 1 already proved the shape works: the Navidrome container starts in 5–11 s, well
inside a fast-tier job.

**But part of Lidarr's API is not offline.** `/api/v1/album/lookup` proxies to
`https://api.lidarr.audio`, so a live-container test of lookup would depend on a third-party
service, would be rate-limited, and would fail while nothing in this repository was wrong. That is
precisely the flakiness the merge gate must not have.

So the line is drawn like this, and it is drawn on purpose:

| Covered by the live container (deterministic, offline) | Covered by fixtures captured from a real instance |
|---|---|
| `/ping` and `/api/v1/health` shapes | album lookup results |
| a wrong key really returns **401 with an empty body** | the `queue` record shape while something downloads |
| `/api/v1/system/status`'s `appName`, `version`, `urlBase` | the album `statistics` shape mid-download |
| a request with no `Accept` header really can get **406** | Bindery's search-result element shape |
| `POST /api/v1/album` with `{}` really returns **400** and a JSON **array** of failures | |
| `/api/v1/rootfolder`, `/api/v1/qualityprofile`, `/api/v1/metadataprofile` shapes | |
| an empty `/api/v1/queue` envelope's field names | |
| Bindery: `?q=` really returns 400 while `?term=` does not | |
| Bindery: a query-string key really is refused on a mutation | |

Every row in the left column is a claim this plan made from source and never executed. **That is
what this task is for.**

- [ ] **Step 1: Pin the containers**

`ci/lidarr.compose.yml`:

```yaml
services:
  lidarr:
    # There is no official Lidarr image; linuxserver and hotio both publish one. PIN A REAL TAG --
    # list them first and record which you chose:
    #   curl -s 'https://registry.hub.docker.com/v2/repositories/linuxserver/lidarr/tags?page_size=50' \
    #     | python3 -c 'import json,sys; [print(t["name"]) for t in json.load(sys.stdin)["results"]]'
    # `latest` is banned here for the same reason the Navidrome image is pinned to 0.63.2: an
    # upstream release must not be able to turn this gate red on a morning nobody changed anything.
    image: lscr.io/linuxserver/lidarr:PINNED_TAG_GOES_HERE
    ports: ["8686:8686"]
    environment:
      PUID: "1000"
      PGID: "1000"
      TZ: Etc/UTC
    healthcheck:
      # `/ping` is unauthenticated and returns {"status":"OK"} -- the one endpoint that can be
      # probed before the API key is known. Match the BODY, not the status code: Lidarr answers
      # 200 with {"status":"Error"} when its config database will not open, and a status-code
      # healthcheck would call that healthy. (This is the same trap ci/navidrome.compose.yml
      # records for /rest/ping.view.)
      test: ["CMD-SHELL", "wget -qO- http://localhost:8686/ping | grep -q OK"]
      interval: 2s
      retries: 60
```

`ci/configure-lidarr.sh` — reads the generated key and creates a root folder, the way
`ci/configure-libraries.sh` does for Navidrome:

```bash
#!/usr/bin/env bash
set -euo pipefail
# Lidarr generates its API key on first start and writes it to /config/config.xml as a 32-char
# lowercase hex GUID with the dashes removed (ConfigFileProvider.cs:
# `Guid.NewGuid().ToString().Replace("-", "")`). There is no environment variable for it, so it
# has to be read back out of the container.
#
# Note that `AuthenticationRequired=DisabledForLocalAddresses` does NOT exempt the API: the
# local-address bypass is attached only to the "UI" authorization policy, while every
# [V1ApiController] falls back to a policy requiring an authenticated user (Startup.cs). So the
# key is needed even from localhost.
CONTAINER=${1:-lidarr}
until docker exec "$CONTAINER" test -f /config/config.xml; do sleep 1; done
KEY=$(docker exec "$CONTAINER" sed -n 's:.*<ApiKey>\(.*\)</ApiKey>.*:\1:p' /config/config.xml)
if [ ${#KEY} -ne 32 ]; then
  echo "expected a 32-character API key, got ${#KEY} characters" >&2
  exit 1
fi

# A root folder, so /api/v1/rootfolder is not an empty array -- LiveLidarrTest asserts on its
# fields, and an empty array would make every one of those assertions vacuous.
docker exec "$CONTAINER" mkdir -p /music
curl -sf -X POST 'http://localhost:8686/api/v1/rootfolder' \
  -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"path":"/music","name":"Music"}' > /dev/null || {
    echo "could not create a root folder; see the plan's Task 5 Step 1 note -- the body this" >&2
    echo "sends is a guess about a setup call MuPlay itself never makes." >&2
    exit 1
  }

# The test reads the key from the environment; it never appears on a URL and never in a fixture.
echo "LIDARR_API_KEY=$KEY" >> "$GITHUB_ENV"
```

`ci/bindery.compose.yml` follows the same shape: `ghcr.io/vavallee/bindery:<PINNED TAG>`, port
**8787**, healthcheck on `/api/v1/health` matching the body `"ok"`. `ci/configure-bindery.sh` reads
the API key from wherever Task 8 Step 1 established it comes from — **this plan does not know, and
that step is where it is found out.**

- [ ] **Step 2: Generalise the live-test carve-out rather than copying it**

`build-logic/convention/src/main/kotlin/Testing.kt` today carries one constant,
`LIVE_NAVIDROME_TEST_TASK_NAME`, whose whole design note is that a hand-synced string drifts. Two
more live tasks means **generalising it, not adding two more constants** — the same argument
`excludeByteBuddyFromInstrumentedTests` makes about per-module fixes:

```kotlin
/**
 * Every test task that runs against a real service container, mapped to the JUnit tag its tests
 * carry.
 *
 * One entry per container, and **one tag per container**, not a shared `"live"` for all three: a
 * task's `useJUnitPlatform { includeTags(...) }` includes by tag, so a shared tag would make
 * `liveLidarrTest` try to run `LiveNavidromeTest` against a Lidarr that is not there.
 *
 * Kept in sync with root `build.gradle.kts`'s identical declaration by hand — `build-logic` is
 * included via `pluginManagement.includeBuild`, which exposes its plugins but not its Kotlin
 * source — and that hand-sync is *checked*, not trusted, by `ConventionTest`'s
 * `the live test task names are not hand-synced into drift`.
 */
internal val LIVE_TEST_TASKS: Map<String, String> = mapOf(
  "liveNavidromeTest" to "live",
  "liveLidarrTest" to "live-lidarr",
  "liveBinderyTest" to "live-bindery",
)
```

`configureJUnit5` becomes:

```kotlin
  tasks.withType<Test>().configureEach {
    useJUnitPlatform {
      // Every live tag is excluded from every ordinary task. A live task is excluded from this
      // rule entirely rather than having it applied and then re-included: JUnit Platform resolves
      // "both included and excluded" by *excluding*, and the only symptom is a task that reports
      // success while running zero tests -- which this project has already been bitten by once.
      if (name !in LIVE_TEST_TASKS) {
        LIVE_TEST_TASKS.values.distinct().forEach { excludeTags(it) }
      }
    }
  }
```

Root `build.gradle.kts` registers the two new tasks beside the existing `liveNavidromeTest`, each
`includeTags` its own tag only.

`ConventionTest`'s existing `the live-Navidrome test task name is not hand-synced into drift` is
**renamed and widened** to read the whole map out of both files and compare them. Widen it; do not
add a second rule beside it, or the next person adds a third.

- [ ] **Step 3: Write the live tests — every claim this plan made and never ran**

`LiveLidarrTest.kt`, tagged `@Tag("live-lidarr")`, against `http://localhost:8686` with the key
from `System.getenv("LIDARR_API_KEY")`:

```kotlin
  @Test
  fun `ping answers without a key, and status does not`() = runTest {
    // The two halves of the handshake design, against the real thing. `/ping` being
    // unauthenticated is what lets the configuration screen distinguish "nothing is listening"
    // from "your key was rejected"; `system/status` needing a key is what makes it a credential
    // check at all.
    assertThat(clientWith(apiKey = "").ping()).isTrue()

    val thrown = runCatching { clientWith(apiKey = "definitely-not-the-key").status() }.exceptionOrNull()
    assertThat(thrown).isInstanceOf(LidarrUnauthorizedException::class.java)
  }

  @Test
  fun `a wrong key really returns 401 with an empty body`() {
    // Asserted at the raw HTTP level, not through the client, because the *client's* behaviour is
    // derived from this fact. If Lidarr ever starts distinguishing a wrong key from a missing one
    // in the body, `LidarrUnauthorizedException`'s deliberately vague message can be improved --
    // and this test failing is how anyone finds out.
    val response = rawGet("/api/v1/system/status", apiKey = "wrong")
    assertThat(response.code).isEqualTo(401)
    assertThat(response.body?.string()).isEmpty()
  }

  @Test
  fun `status identifies this server as Lidarr and reports a urlBase`() = runTest {
    val status = client().status()
    assertThat(status.appName).isEqualTo("Lidarr")
    assertThat(status.isLidarr).isTrue()
    assertThat(status.version).isNotBlank()
    // The container sets no urlBase, so the empty string is the real answer -- and asserting it
    // pins the field's *presence*, which is what the proxied-install path depends on.
    assertThat(status.urlBase).isEmpty()
  }

  @Test
  fun `an empty album post really returns 400 with a json array of validation failures`() = runTest {
    // The single most valuable assertion in this file. `openapi.json` documents this endpoint as
    // returning 200 and declares no field required; the code returns 201 on success and this on
    // failure. `LidarrValidationException`'s parser is written against a *bare array*, and this is
    // where that shape is confirmed against the server rather than against a source reading.
    val response = rawPost("/api/v1/album", body = "{}")

    assertThat(response.code).isEqualTo(400)
    val raw = checkNotNull(response.body).string()
    assertThat(raw.trimStart()).startsWith("[")
    val failures = Json { ignoreUnknownKeys = true }
      .decodeFromString<List<ValidationFailureBody>>(raw)
    assertThat(failures).isNotEmpty()
    assertThat(failures.mapNotNull { it.propertyName }).isNotEmpty()
  }

  @Test
  fun `a request with no acceptable media type really can be refused`() = runTest {
    // `ReturnHttpNotAcceptable = true`. `LidarrAuthInterceptor` sets `Accept` on every request
    // because of this; the assertion records what happens without it, so removing that header is
    // a visible change rather than a mystery 406 in the field.
    val response = rawGet("/api/v1/system/status", accept = "application/xml")
    assertThat(response.code).isIn(406, 200)
    // Whichever it is, record it in the task report -- this plan predicted 406 and did not run it.
  }

  @Test
  fun `the root folder carries the defaults an add depends on`() = runTest {
    val folders = client().rootFolders()

    // `isNotEmpty` first, because every assertion after it is vacuous on an empty list -- which
    // is exactly what an unconfigured container would produce, and exactly the shape this
    // project's rule 3 exists for.
    assertThat(folders).isNotEmpty()
    val music = folders.single { it.path == "/music" }
    assertThat(music.accessible).isTrue()
    assertThat(music.name).isNotBlank()
    // Record the two default profile ids in the task report: if the container leaves them at 0,
    // `LidarrAddTargets`'s fallback branch is the *normal* path, not the exceptional one, and the
    // plan's framing of it should be corrected.
  }

  @Test
  fun `the profile endpoints answer with at least one profile each`() = runTest {
    assertThat(client().qualityProfiles()).isNotEmpty()
    assertThat(client().metadataProfiles()).isNotEmpty()
  }

  @Test
  fun `an empty queue really has the envelope this client reads`() = runTest {
    // Reading the envelope wrong yields an empty list rather than an error, so "it parsed" proves
    // nothing here. The raw body is checked for the four envelope keys by name.
    val raw = checkNotNull(rawGet("/api/v1/queue?pageSize=100&includeUnknownArtistItems=true").body).string()
    assertThat(raw).contains("\"page\"", "\"pageSize\"", "\"totalRecords\"", "\"records\"")
    assertThat(client().queue()).isEmpty()
  }
```

`LiveBinderyTest.kt`, tagged `@Tag("live-bindery")`, carries **the two facts Task 8 could not
execute**, as permanent assertions rather than one-off curl output:

```kotlin
  @Test
  fun `health answers without a key and names a version`() = runTest { /* … */ }

  @Test
  fun `the search parameter is term, and q is refused`() = runTest {
    // Bindery's own documentation says `q`. This is the assertion that keeps this client on the
    // parameter the handler actually reads, and that will go red the day the docs become true.
    assertThat(rawGet("/api/v1/search/book?term=dune").code).isEqualTo(200)
    assertThat(rawGet("/api/v1/search/book?q=dune").code).isEqualTo(400)
  }

  @Test
  fun `a query-string api key is refused on a mutation`() = runTest {
    // Header-only is not just this project's preference here, it is the only thing that works.
    assertThat(rawPost("/api/v1/author/book?apikey=$KEY", body = "{}", withHeaderKey = false).code)
      .isNotEqualTo(201)
  }
```

**If either live suite cannot be stood up in CI**, say so in the task report and leave the
corresponding job out rather than adding a job that is allowed to fail — a green gate that never
ran is the exact defect §10's countermeasures exist to prevent.

- [ ] **Step 4: Add the CI jobs**

`.github/workflows/pr.yml` gains one job per container, modelled line-for-line on the existing
`live-navidrome` job: start the compose file with `--wait`, run the configure script, run the
Gradle task, tear down with `if: always()`, upload reports on failure.

The **`static-analysis`** job needs no change: `ConventionTest` and `verifyReleaseManifest` already
run there, and the two rules Task 1 added ride along.

- [ ] **Step 5: The Tier 2 journeys**

`app/src/androidTest/kotlin/app/muplay/IntegrationsJourneyTest.kt`. Three journeys, and the first
is the one the whole plan turns on.

**Journey A — nothing configured, nothing shown.** With no integration credentials on the device:

```kotlin
  @Test
  fun aUserWhoRunsNeitherServiceSeesNoRequestsSurface() {
    // The severability contract, on a real screen. Not "the ViewModel returned NotConfigured" --
    // that is a unit test and it already exists. This is the composed app, and the absence is
    // asserted on the node tree.
    composeRule.onNodeWithTag("requests:root").assertDoesNotExist()
    composeRule.onNodeWithTag("requests:section:LIDARR").assertDoesNotExist()
    composeRule.onNodeWithTag("requests:section:BINDERY").assertDoesNotExist()
    // ...and the one affordance that turns it on *is* there, so this test cannot pass by the
    // whole app having failed to start.
    composeRule.onNodeWithTag("settings:integrations").assertExists()
  }
```

> **That last assertion is not decoration.** An `assertDoesNotExist` suite passes perfectly on an
> app that crashed at launch, on a blank screen, and on a wrong navigation destination. A positive
> assertion beside the negative ones is what makes the negatives mean something — the same
> principle as this project's rule 6 about gates that cannot fail.

**Journey B — configure Lidarr against the real container, request an album, watch the status.**
Reached over `adb reverse tcp:8686 tcp:8686`, exactly as the Navidrome journey uses
`adb reverse tcp:4533`, and therefore over **cleartext to `localhost`** — which the **debug**
manifest permits and the release manifest does not. Type the URL and key into `setup:url` /
`setup:key`, tap `setup:test`, assert the success text names *Lidarr*, save, and assert
`requests:section:LIDARR` now exists **and** `requests:section:BINDERY` still does not.

**Journey C — a cleartext URL is refused where cleartext is refused.** `IntegrationBaseUrl.parse`
is unit-tested at both policies in Tier 1, so this journey asserts only the wiring: that the
**debug** build (the only one an emulator test can run) really does receive
`CleartextPolicy.Allowed`, by typing an `http://` URL and asserting `setup:error` shows nothing.
The release half is not reachable from an instrumented test at all, and **that is stated here
rather than left as an apparent gap** — it is covered by `ConventionTest`'s
`the cleartext policy and the cleartext manifest cannot disagree` plus
`:app:verifyReleaseManifest`, which are the two things that can observe a release build.

`.github/workflows/e2e.yml` gains the Lidarr container beside the Navidrome one, the extra
`adb reverse`, and `:app:connectedDebugAndroidTest` already covers the new class.

- [ ] **Step 6: Measure every floor, and watch each one fail**

For each of `:integrations:core`, `:integrations:lidarr`, `:integrations:bindery`,
`:integrations:requests` and `:feature:requests`, produce a merged JVM + instrumented report and
read the real numbers out of `jacocoTestReport.xml`:

```bash
./gradlew jacocoTestReport
for m in integrations/core integrations/lidarr integrations/bindery integrations/requests feature/requests; do
  echo "== $m"
  python3 - "$m" <<'PY'
import sys, xml.etree.ElementTree as ET
t = ET.parse(f"{sys.argv[1]}/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml")
for cls in t.iter('class'):
    name = cls.get('name').replace('/', '.')
    for c in cls.findall('counter'):
        if c.get('type') in ('BRANCH', 'LINE'):
            m_, cv = int(c.get('missed')), int(c.get('covered'))
            if m_ + cv:
                print(f"{name:78s} {c.get('type'):6s} {cv}/{cv+m_}  {cv/(cv+m_):.4f}")
PY
done
```

Write each measured ratio, **rounded down to two decimals**, into `coverageFloors`, with a comment
saying what was measured and by which tier. Then, for **every** floor this plan adds, delete one
test, confirm the gate goes red, and restore it. A floor whose matched classes carry no counters of
its own kind passes at every minimum; this project has shipped that once and the check is cheap.

Record the five before/after numbers in the task report. **Do not round up, and do not write a
round number that was not measured.**

- [ ] **Step 7: Run the whole mutation-probe list**

```bash
./ci/mutation-probes.sh            # every probe, including this plan's ~20
./ci/mutation-probes.sh integrations   # just this plan's
```

Every probe added by Tasks 2–9 must report CAUGHT. A probe reported MISSED whose named test *is*
in the failing list means the expected-failure **count** is stale, not that the code is wrong —
re-measure and update the number (the script's own header says exactly this). A probe reported
MISSED whose named test is **not** failing is a real hole: fix the test, not the probe.

**And note honestly what a green run does not mean.** The script's header is explicit: it probes
only what someone thought to probe, and every finding in its history was a *class* of value nobody
had asked about. This plan added its probes against the classes it knew — argument passthrough, a
constant field, a collection order, a mapped status. The next class is by definition not in there.

- [ ] **Step 8: Correct the spec**

`docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`, §8. The current text is:

```markdown
## 8. Optional integrations

- **Bindery** — request audiobooks from inside the app.
- **Lidarr** — request music. `POST /api/v1/album` payload is unverified against a live instance.

Both are opt-in, both fail closed, and neither may block core playback.
```

Replace it with a section that says what was actually found. It must record, at minimum:

1. **Bindery is `github.com/vavallee/bindery`** — MIT, actively developed, v1.32.1 (2026-08-20),
   `ghcr.io/vavallee/bindery`, port 8787 — and **not** `evanbrooks/bindery` (browser book layout,
   archived 2023) or `jarynclouatre/bindery` (an e-book converter, and the only "Bindery" in
   awesome-selfhosted).
2. **Bindery has no request or approval concept.** It is a Readarr replacement: adding a book *is*
   acquiring it. §8's "request" framing was wrong, and MuPlay models acquisition. The
   `mediaType` field defaults to `ebook` and must be sent as `audiobook`. Its search parameter is
   `term`, not the `q` its own docs claim. Its API key is instance-wide and admin-equivalent.
3. **The Lidarr payload is no longer unverified.** Record what it requires — `foreignAlbumId` plus
   a nested `artist` carrying `foreignArtistId`, `qualityProfileId`, `metadataProfileId` and a
   `rootFolderPath` — and record the two traps: `artist.addOptions.searchForMissingAlbums = true`
   silently cancels the album search, and `openapi.json` is Swashbuckle-generated, declares zero
   required fields and documents a 200 where the code returns 201, so it is **not** usable as an
   oracle the way the OpenSubsonic spec is.
4. **Cleartext.** Release builds refuse an `http://` integration URL at configuration time, for the
   reasons in this plan's *Cleartext HTTP* section, and the consequence — a LAN-only plain-HTTP
   service cannot be configured in a release build — is stated as a decision, not discovered as a
   bug.
5. **Both integrations are independently optional and structurally severable**, enforced by
   `ConventionTest`'s `nothing outside integrations depends on an integration`.

Also add one row to **§12's risk table**:

| Risk | Likelihood | Mitigation |
|---|---|---|
| Lidarr's metadata lookup (`api.lidarr.audio`) is slow, rate-limited or down while the user's own server is healthy | Medium | Search is debounced and never auto-retried; the lookup path is covered by recorded fixtures rather than by a merge-gate call to a third party |

- [ ] **Step 9: Final verification, then commit**

```bash
./gradlew clean
./gradlew :app:testDebugUnitTest --tests '*ConventionTest*'
./gradlew lint :app:verifyReleaseManifest :app:assembleRelease
./gradlew test
./gradlew jacocoJvmCoverageVerification
docker compose -f ci/lidarr.compose.yml up -d --wait && ./ci/configure-lidarr.sh && \
  ./gradlew :integrations:lidarr:liveLidarrTest; docker compose -f ci/lidarr.compose.yml down -v
docker compose -f ci/bindery.compose.yml up -d --wait && ./ci/configure-bindery.sh && \
  ./gradlew :integrations:bindery:liveBinderyTest; docker compose -f ci/bindery.compose.yml down -v
./gradlew connectedDebugAndroidTest jacocoTestCoverageVerification
./ci/mutation-probes.sh
```

Every one green. **`:app:assembleRelease` is on this list deliberately**: it is the only command
that compiles `app/src/release/kotlin/CleartextPolicyModule.kt`, and a release-only Hilt module
that does not bind is a failure no other command in this list can see.

```bash
git add -A
git commit -m "chore(integrations): the gates, and the spec corrections Plan 7 earned

Two pinned containers in Tier 1, carrying every claim this plan made from source and never
executed: Lidarr's bare-401-on-a-wrong-key, its 400-with-a-JSON-array on an empty album post,
and Bindery's term-not-q and its refusal of a query-string key on a mutation.

The Tier 2 journey's first assertion is an absence -- no requests surface at all on a device
with nothing configured -- paired with a positive assertion that the settings row is there,
because an assertDoesNotExist suite passes perfectly on an app that crashed at launch.

Spec section 8 is rewritten: Bindery is vavallee/bindery, it has no request concept, and the
Lidarr payload is no longer unverified. Section 12 gains the metadata-lookup risk."
```

---
