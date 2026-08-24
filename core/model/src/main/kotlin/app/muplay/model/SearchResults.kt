package app.muplay.model

/** The three result lists a Subsonic `search3` returns, each scoped to one library. */
data class SearchResults(
  val artists: List<Artist>,
  val albums: List<Album>,
  val songs: List<Song>,
) {
  val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}
