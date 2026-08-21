package app.muplay.model;

import javax.annotation.Nonnull;

/** A Navidrome library, addressed by {@code musicFolderId} in the Subsonic API. */
public record MusicLibrary(int id, @Nonnull String name, @Nonnull LibraryRole role) {

  @Nonnull
  public MusicLibrary withRole(@Nonnull LibraryRole newRole) {
    return new MusicLibrary(id, name, newRole);
  }
}
