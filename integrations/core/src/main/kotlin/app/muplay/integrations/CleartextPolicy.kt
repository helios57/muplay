package app.muplay.integrations

/**
 * Whether this build may talk to an integration over unencrypted HTTP.
 *
 * **This is a value, injected, and never a `BuildConfig.DEBUG` branch.** The debug and release
 * variants of `:app` provide different members from variant-specific source sets
 * (`app/src/debug/kotlin/...` and `app/src/release/kotlin/...`), which has two properties a
 * runtime branch would not have:
 *
 * - **nothing in a release build can produce an [Allowed] value.** The only `@Provides` that
 *   returns one lives in `app/src/debug/kotlin/app/muplay/di/CleartextPolicyModule.kt`, and
 *   variant source sets are mutually exclusive, so a release build has no source of the value and
 *   therefore no flag to flip.
 *
 *   Stated that way deliberately, because a stronger sentence stood here for a while and was
 *   false. It read *"no code compiled into the release variant names [Allowed]"*, and Plan 7
 *   Task 1's review found the counter-example in this very module:
 *   [IntegrationBaseUrl]'s `permitsCleartext` contains `CleartextPolicy.Allowed -> true`, and
 *   `:app` depends on `:integrations:core` with `implementation`, not `debugImplementation`, so
 *   that arm is compiled into release. It has to be — an exhaustive `when` over a sealed interface
 *   cannot be written without naming every member, and that exhaustiveness is the whole reason
 *   this is a sealed interface rather than a `Boolean`. What matters is that no release-compiled
 *   code can ever *reach* it, which follows from there being no release binding that produces one.
 *
 *   It is also not "the release binary does not contain [Allowed]": `minifyEnabled` is off, so the
 *   member's own class is packaged like every other class here — confirmed by `strings` over the
 *   release APK's dex.
 *
 *   `ConventionTest`'s `nothing a release build compiles names CleartextPolicy Allowed` is what
 *   keeps the corrected claim true: it walks every release-compiled Kotlin source in the
 *   repository and permits the literal in exactly two files — the debug variant's policy module,
 *   and `permitsCleartext`'s single `when` arm. The older rule,
 *   `the cleartext policy and the cleartext manifest cannot disagree`, opens three hardcoded paths
 *   and could not have seen this; it still guards the policy/manifest agreement, which is a
 *   different question.
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
