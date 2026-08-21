package app.muplay.model;

/**
 * What a Navidrome library contains.
 *
 * <p>Navidrome hardcodes {@code child.Type = "music"} for every media file and
 * never signals that something is an audiobook, so this is a user decision made
 * once at setup — and it is the only mechanism available.
 */
public enum LibraryRole {
  MUSIC,
  AUDIOBOOKS,
  UNASSIGNED
}
