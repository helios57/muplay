package app.muplay.testing;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.Response;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Validates a recorded Subsonic response against the vendored OpenSubsonic OpenAPI spec.
 *
 * <p>This is an oracle external to this codebase: it asserts what the protocol says a response
 * looks like, not what our parser happens to accept.
 *
 * <p>Note the spec requires {@code type}, {@code serverVersion} and {@code openSubsonic} on every
 * response — fields a legacy Subsonic server would not send. Validating against it therefore
 * asserts OpenSubsonic compliance, which is deliberate for a Navidrome client.
 */
public final class OpenApiFixtureValidator {

  private static final String SPEC = "/openapi/opensubsonic-1.16.1.json";

  public OpenApiFixtureValidator() {}

  /**
   * Holds the one parsed {@link OpenApiInteractionValidator} for the whole JVM.
   *
   * <p>Parsing the 453 KB spec costs roughly 150ms warm / 1.6s cold; every fixture test across
   * every module constructs its own {@link OpenApiFixtureValidator}, and repeating that parse per
   * instance would multiply the cost across all of them. A nested holder class is initialized
   * lazily — only on first access to {@link #VALIDATOR} — and the JVM's class-initialization
   * rules guarantee that happens at most once and that concurrent threads block until it
   * completes, so no explicit synchronization is needed here.
   *
   * <p>If the vendored spec is ever missing from the classpath, {@link #readSpec()} still throws
   * — but sharing this holder makes that failure less legible than the old per-instance {@link
   * IllegalStateException}, not equally loud, and that is worth documenting honestly rather than
   * asserting it away. Per JLS 12.4.2: the <em>first</em> thread to touch {@link #VALIDATOR} in
   * the JVM gets {@link ExceptionInInitializerError} with the original {@link
   * IllegalStateException} as its cause — a full, legible chain. Every subsequent touch of this
   * class in that same JVM — the normal case, since Gradle reuses one forked test worker across a
   * whole test task — instead gets a bare {@link NoClassDefFoundError} with no cause at all,
   * because the JVM marks the class permanently unusable after its initializer fails once. That
   * is a real degradation versus throwing {@link IllegalStateException} fresh from every
   * constructor call. It is accepted here rather than engineered around, because it only surfaces
   * if the packaged spec resource goes missing — which fails every test in the fixture suite, not
   * just one, so the first test's full cause chain is what a developer will actually read.
   */
  private static final class Holder {
    private static final OpenApiInteractionValidator VALIDATOR = buildValidator();

    private static OpenApiInteractionValidator buildValidator() {
      return OpenApiInteractionValidator.createForInlineApiSpecification(readSpec())
          // The spec composes every response schema via allOf (e.g. SubsonicSuccessResponse =
          // SubsonicBaseResponse + {status}). Without this flag the validator checks each allOf
          // branch against the *whole* instance independently, so a field declared in one branch
          // (e.g. "status") is reported as an undeclared "additional property" by every other
          // branch that doesn't itself declare it — even a perfectly valid response then fails
          // with zero of the oneOf alternatives matching. withResolveCombinators(true) merges
          // allOf branches before validating, which is what a base+extension schema style like
          // this one requires. Verified: still correctly rejects a response missing a required
          // field, a response with an extra/undefined nested field, a wrong-typed field, and an
          // undefined path.
          .withResolveCombinators(true)
          .build();
    }
  }

  private static String readSpec() {
    try (var in = OpenApiFixtureValidator.class.getResourceAsStream(SPEC)) {
      if (in == null) {
        throw new IllegalStateException("Vendored spec not found on classpath: " + SPEC);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Could not read vendored OpenAPI spec", e);
    }
  }

  /**
   * Asserts that {@code jsonBody} matches the OpenSubsonic spec's response shape for {@code
   * endpointPath}.
   *
   * @param endpointPath the literal key under the spec's {@code paths} map, e.g. {@code
   *     "/rest/ping"}. The vendored spec keys every operation with its full REST path — including
   *     the {@code /rest} prefix and excluding any {@code .view} suffix — so that is what this
   *     method expects too; it does not rewrite or guess at the path.
   * @param jsonBody the full response body
   */
  public void assertValid(@Nonnull String endpointPath, @Nonnull String jsonBody) {
    requireNonBlank(endpointPath, "endpointPath");
    Response response =
        SimpleResponse.Builder.ok()
            .withContentType("application/json")
            .withBody(jsonBody)
            .build();
    ValidationReport report =
        Holder.VALIDATOR.validateResponse(endpointPath, Request.Method.GET, response);
    if (report.hasErrors()) {
      throw new AssertionError(
          "Response does not match the OpenSubsonic spec for "
              + endpointPath
              + ":\n"
              + report);
    }
  }

  /**
   * {@code endpointPath} is {@code @Nonnull}, but this class is called from test code across
   * every module in the project, not all of it under this build's NullAway coverage — so a caller
   * that passes a literal {@code null} (bypassing the compile-time check) or an empty string
   * should still fail as a clear {@link AssertionError} naming the problem, not as a raw
   * {@link NullPointerException} thrown from deep inside a third-party validator, and not as an
   * {@link AssertionError} whose message trails off after "for" with nothing after it.
   */
  private static void requireNonBlank(@Nullable String endpointPath, String paramName) {
    if (endpointPath == null) {
      throw new AssertionError(paramName + " must not be null; got null.");
    }
    if (endpointPath.isBlank()) {
      throw new AssertionError(paramName + " must not be blank; got \"" + endpointPath + "\".");
    }
  }
}
