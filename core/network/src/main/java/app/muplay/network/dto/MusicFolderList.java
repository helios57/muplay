package app.muplay.network.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

/**
 * See {@link SubsonicResponse}'s class doc for why every constructor here is an explicit
 * {@code @JsonCreator}: Android's D8 desugars records for {@code minSdk < 33}, which breaks
 * Jackson's automatic (annotation-free) Java-records deserialization on a real device even
 * though it works under Robolectric/JVM unit tests.
 */
public record MusicFolderList(@JsonProperty("musicFolder") @Nullable List<MusicFolder> musicFolder) {

  @JsonCreator
  public MusicFolderList {}

  public record MusicFolder(@JsonProperty("id") int id, @JsonProperty("name") @Nullable String name) {

    @JsonCreator
    public MusicFolder {}
  }
}
