package app.muplay.network.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The envelope every Subsonic response is wrapped in.
 *
 * <p>Every record here that Jackson deserializes carries an explicit {@code @JsonCreator} on its
 * canonical constructor, not just {@code @JsonProperty} on its components. Jackson's automatic
 * Java-records support (used implicitly by default, with no annotations at all, for a record
 * compiled by {@code javac}) depends on {@code Class#isRecord()}/{@code getRecordComponents()} —
 * and Android's D8 compiler desugars records into plain classes for any module with {@code
 * minSdk < 33} (this project's {@code minSdk} is 26), which strips exactly that information at
 * the bytecode level. The result compiles and passes every Robolectric/JVM unit test (Robolectric
 * runs javac output directly, never through D8), but fails on a real device or emulator with
 * {@code InvalidDefinitionException: ... no Creators, like default constructor, exist} — found
 * running Task 8's live contract test against a real Navidrome on API 37, not from any unit
 * test. {@code @JsonCreator} plus per-parameter {@code @JsonProperty} makes the constructor a
 * creator Jackson recognises directly, independent of whether the runtime class is a "real"
 * {@code java.lang.Record}. {@code :app}'s {@code ArchitectureTest.everyDtoRecordHasAnExplicitJacksonCreator}
 * enforces this on every record in this package so a future DTO cannot silently regress it —
 * not linked here as a real {@code @link}, since {@code :core:network} does not depend on
 * {@code :app} and the reference would not resolve.
 */
public record SubsonicResponse(@JsonProperty("subsonic-response") @Nullable Body body) {

  @JsonCreator
  public SubsonicResponse {}

  public record Body(
      @JsonProperty("status") @Nullable String status,
      @JsonProperty("version") @Nullable String version,
      @JsonProperty("type") @Nullable String type,
      @JsonProperty("serverVersion") @Nullable String serverVersion,
      @JsonProperty("openSubsonic") boolean openSubsonic,
      @JsonProperty("error") @Nullable SubsonicError error,
      @JsonProperty("musicFolders") @Nullable MusicFolderList musicFolders,
      @JsonProperty("openSubsonicExtensions") @Nullable List<OpenSubsonicExtension>
              openSubsonicExtensions) {

    @JsonCreator
    public Body {}
  }

  // Named SubsonicError, not Error: a nested type literally named "Error" clashes with
  // java.lang.Error (Error Prone's JavaLangClash check, -Werror in this build). The JSON field
  // name is unaffected — Jackson matches the Body record component "error" by name, not the
  // type's class name.
  public record SubsonicError(@JsonProperty("code") int code, @JsonProperty("message") @Nullable String message) {

    @JsonCreator
    public SubsonicError {}
  }

  public record OpenSubsonicExtension(
      @JsonProperty("name") @Nullable String name,
      @JsonProperty("versions") @Nullable List<Integer> versions) {

    @JsonCreator
    public OpenSubsonicExtension {}
  }
}
