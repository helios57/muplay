package app.muplay.library

/**
 * **Its own file, on purpose.** Every top-level declaration in one Kotlin file compiles into that
 * file's single file-class, and this project's coverage floors can only filter by class name (a
 * `"CLASS"`-element JaCoCo rule; see the root `build.gradle.kts`'s `coverageFloors` doc). While
 * this function shared `CoverArt.kt` with the `@Composable CoverArtImage`, the pair measured
 * `CoverArtKt` 3/56 BRANCH — the Composable's 52 synthetic branches, never executed from a JVM
 * test, drowning this function's own 3/4 — and no floor could be put over the half that a unit
 * test does cover. Task 9's review (N-7a) showed that was a file-layout choice presented as a
 * constraint. Split, `CoverArtCacheKeyKt` measures 3/4 BRANCH, 1/1 LINE and is gated today.
 *
 * The same split is this module's existing convention for the same reason: `LibraryUiState.kt` vs
 * `LibraryScreen.kt`, `SetupUiState.kt` vs `SetupScreen.kt`. Do not fold this back in.
 */

/**
 * The Coil cache key for one piece of cover art.
 *
 * **Derived from the art id and the requested size, and from nothing else** — in particular not
 * from the request URL. An authenticated Subsonic cover-art URL carries `u`, `t` and a **fresh
 * salt** per request, so Coil's default URL-derived key would miss the memory and disk caches on
 * every single load and re-download every cover on every scroll.
 *
 * This is the same defect Tempo shipped on the playback side (its Media3 cache key included the
 * auth token and the bitrate, so changing bitrate orphaned the entire cache), and the same rule
 * this project's global constraints state for Media3: the key comes from the item id.
 */
internal fun coverArtCacheKey(coverArtId: String, sizePx: Int?): String =
  "$coverArtId@${sizePx?.toString() ?: "full"}"
