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
      @Nullable SubsonicError error,
      @Nullable MusicFolderList musicFolders) {}

  // Named SubsonicError, not Error: a nested type literally named "Error" clashes with
  // java.lang.Error (Error Prone's JavaLangClash check, -Werror in this build). The JSON field
  // name is unaffected — Jackson matches the Body record component "error" by name, not the
  // type's class name.
  public record SubsonicError(int code, @Nullable String message) {}
}
