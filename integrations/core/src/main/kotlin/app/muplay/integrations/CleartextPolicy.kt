package app.muplay.integrations

/**
 * Whether this build may talk to an integration over unencrypted HTTP.
 *
 * **This is a value, injected, and never a `BuildConfig.DEBUG` branch.** The debug and release
 * variants of `:app` provide different members from variant-specific source sets
 * (`app/src/debug/kotlin/...` and `app/src/release/kotlin/...`), which has two properties a
 * runtime branch would not have:
 *
 * - no code compiled into the release variant names [Allowed], so there is no flag to flip and no
 *   branch to reach. (Precisely that, and not "the release binary does not contain [Allowed]":
 *   `minifyEnabled` is off, so the member's own class is packaged like every other class in this
 *   module -- confirmed by `strings` over the release APK's dex. What the variant source sets buy
 *   is that nothing in a release build ever *names* it, which `ConventionTest`'s
 *   `the cleartext policy and the cleartext manifest cannot disagree` is what keeps true.)
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
