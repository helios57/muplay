package app.muplay.network.dto;

import java.util.List;
import javax.annotation.Nullable;

public record MusicFolderList(@Nullable List<MusicFolder> musicFolder) {

  public record MusicFolder(int id, @Nullable String name) {}
}
