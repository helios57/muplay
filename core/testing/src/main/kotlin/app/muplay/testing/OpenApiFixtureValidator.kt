package app.muplay.testing

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleResponse

/**
 * Validates a recorded Subsonic response against the vendored OpenSubsonic OpenAPI spec.
 *
 * This is an oracle external to this codebase: it asserts what the protocol says a response looks
 * like, not what our own parser happens to accept. Every later task's fixture tests call
 * [assertValid] against a real recorded (or hand-built) response body, so a fixture that drifts
 * from the published spec fails here even if this codebase's own parsing code would happily accept
 * it.
 *
 * Note the spec requires `type`, `serverVersion` and `openSubsonic` on every response — fields a
 * legacy (non-OpenSubsonic) Subsonic server would not send. Validating against it therefore asserts
 * OpenSubsonic compliance, which is deliberate for a Navidrome client.
 *
 * Scope boundary, deliberate rather than an oversight: [assertValid] always sends
 * `Content-Type: application/json` and can only validate a response the spec itself describes with
 * an `application/json` media type. Seven of the vendored spec's 87 paths — `/rest/download`,
 * `/rest/getAvatar`, `/rest/getCaptions`, `/rest/getCoverArt`, `/rest/getTranscodeStream`,
 * `/rest/hls.m3u8`, `/rest/stream` — declare no `application/json` response at all (binary media,
 * `application/octet-stream`, `text/plain`, `application/vnd.apple.mpegurl`, ...). Those endpoints
 * are not JSON-fixture-shaped in the first place, so there is nothing for a JSON-fixture oracle
 * like this one to validate against for them regardless of this class's own choices; a fixture test
 * for one of those endpoints needs a different kind of oracle (e.g. asserting the raw byte stream
 * or content headers), not this one.
 */
object OpenApiFixtureValidator {

  private const val SPEC_RESOURCE = "/openapi/opensubsonic-1.16.1.json"

  /**
   * The one parsed [OpenApiInteractionValidator] for the whole JVM, built on first access to
   * [assertValid] and reused by every call after that.
   *
   * Parsing the 453 KB spec is the expensive part of every call; a validator per module or per
   * assertion would multiply that cost across the whole fixture suite and put the tier 1 PR gate
   * budget at risk. `by lazy` (the default `SYNCHRONIZED` mode) gives thread-safe, run-once
   * initialization the same way a Java "initialization-on-demand holder" class would, but with one
   * difference that matters here: if [readSpec] ever throws — the vendored spec resource missing
   * from the classpath — Kotlin's `Lazy` does *not* cache the failure. It leaves the delegate
   * uninitialized and retries [buildValidator] on the next access, so every subsequent call to
   * [assertValid] gets the same fresh, fully-legible [IllegalStateException], not a degraded
   * `NoClassDefFoundError` with no cause the way a failed static initializer would produce on a
   * second touch. That is what "fail just as loud as any other case, every time" requires.
   */
  private val validator: OpenApiInteractionValidator by lazy { buildValidator() }

  private fun buildValidator(): OpenApiInteractionValidator =
    OpenApiInteractionValidator.createForInlineApiSpecification(readSpec())
      // The spec composes every response schema via allOf (e.g. SubsonicSuccessResponse =
      // SubsonicBaseResponse + an inline {status} extension). Without this flag, the validator
      // checks each allOf branch against the *whole* instance independently, so a field declared
      // in one branch (e.g. "status") is reported as an undeclared "additional property" by every
      // other branch that does not itself declare it — even a perfectly valid response then fails,
      // with none of the allOf branches (and so neither of the "subsonic-response" oneOf
      // alternatives) matching. withResolveCombinators(true) merges allOf branches before
      // validating, which a base+extension schema style like this one requires.
      //
      // This does not relax additional-properties strictness: nothing in the spec's schemas
      // declares "additionalProperties": false explicitly, but the validator's own schema
      // transform (AdditionalPropertiesInjectionTransformer) closes any object schema that
      // declares "properties" and does not otherwise say "additionalProperties" or
      // "unevaluatedProperties" itself — merging allOf branches first means that closure applies
      // to the *merged* property set, so a field neither branch declares is still rejected.
      // Verified both directions by injection: removing this flag makes even "accepts a valid
      // ping" fail (every allOf branch flags the other's fields as undeclared, so nothing
      // validates at all — confirming the flag is load-bearing, not decorative); restoring it and
      // running the suite again, "rejects an extra undefined nested field" still correctly throws
      // (confirming the merge does not also merge away each branch's closed-object boundary). See
      // the task report for the exact commands and captured ValidationReport output.
      .withResolveCombinators(true)
      .build()

  private fun readSpec(): String {
    val stream =
      OpenApiFixtureValidator::class.java.getResourceAsStream(SPEC_RESOURCE)
        ?: throw IllegalStateException("Vendored spec not found on classpath: $SPEC_RESOURCE")
    return stream.use { it.readBytes().toString(Charsets.UTF_8) }
  }

  /**
   * Asserts that [jsonBody] matches the OpenSubsonic spec's response shape for [endpointPath].
   *
   * @param endpointPath the literal key under the spec's `paths` map, e.g. `"/rest/ping"`. The
   *   vendored spec keys every operation with its full REST path — including the `/rest` prefix
   *   and excluding any `.view` suffix — so that is what this expects too; it does not rewrite or
   *   guess at the path. An unknown path fails loudly, naming the path in the failure message,
   *   rather than silently passing.
   * @param jsonBody the full response body, e.g. `{"subsonic-response":{...}}`.
   * @param method the HTTP verb whose response schema to validate against, defaulting to `GET`
   *   since nearly every OpenSubsonic endpoint is `GET`-only. One vendored path,
   *   `/rest/getTranscodeDecision`, declares only a `post` operation and no `get` at all — passing
   *   [Request.Method.POST] is how a caller validates that one. The default keeps every existing
   *   two-argument call site unaffected.
   */
  fun assertValid(endpointPath: String, jsonBody: String, method: Request.Method = Request.Method.GET) {
    val response =
      SimpleResponse.Builder.ok()
        .withContentType("application/json")
        .withBody(jsonBody)
        .build()
    val report = validator.validateResponse(endpointPath, method, response)
    if (report.hasErrors()) {
      val displayPath = endpointPath.ifBlank { "<blank endpointPath>" }
      throw AssertionError("Response does not match the OpenSubsonic spec for $displayPath:\n$report")
    }
  }
}
