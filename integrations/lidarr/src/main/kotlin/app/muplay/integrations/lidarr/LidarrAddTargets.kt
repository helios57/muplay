package app.muplay.integrations.lidarr

/**
 * Where an album Lidarr is asked to add will be filed, and under which profiles.
 *
 * Every field here is one Lidarr's `AlbumController` validators require on the nested artist:
 * `qualityProfileId` and `metadataProfileId` must both be **greater than zero** and must exist,
 * and exactly one of `rootFolderPath`/`path` must be a valid path.
 */
data class LidarrAddTargets(
  val rootFolderPath: String,
  val qualityProfileId: Int,
  val metadataProfileId: Int,
  val monitorOption: String,
  val newItemMonitorOption: String,
) {
  companion object {

    /** `MonitorTypes.All`, and Lidarr's own UI's first option. Used when a folder names none. */
    private const val DEFAULT_MONITOR = "all"

    /**
     * Resolves [rootFolder]'s defaults into a complete set of add targets, or `null` when there is
     * no honest answer.
     *
     * `null` rather than a fabricated id: an add against a profile that does not exist fails with
     * a validation message about profiles, which is strictly worse for a user than being told
     * before they press the button.
     *
     * The inaccessible-folder arm is not defensive padding either. Measured against
     * `3.1.0.4875-ls40`: `POST /api/v1/rootfolder` for a directory the server cannot write answers
     * **400** with `propertyName: "Path"` and `errorMessage: "Folder '/music' is not writable by
     * user 'abc'"` — a message about UNIX ownership shown to someone who was choosing an album.
     */
    fun resolve(
      rootFolder: LidarrRootFolder,
      qualityProfiles: List<LidarrProfile>,
      metadataProfiles: List<LidarrProfile>,
    ): LidarrAddTargets? {
      if (!rootFolder.accessible || rootFolder.path.isBlank()) return null
      val quality = usableId(rootFolder.defaultQualityProfileId, qualityProfiles) ?: return null
      val metadata = usableId(rootFolder.defaultMetadataProfileId, metadataProfiles) ?: return null
      return LidarrAddTargets(
        rootFolderPath = rootFolder.path,
        qualityProfileId = quality,
        metadataProfileId = metadata,
        monitorOption = rootFolder.defaultMonitorOption.ifBlank { DEFAULT_MONITOR },
        newItemMonitorOption = rootFolder.defaultNewItemMonitorOption.ifBlank { DEFAULT_MONITOR },
      )
    }

    /** [preferred] if it is a real id, else the first profile there is, else `null`. */
    private fun usableId(preferred: Int, profiles: List<LidarrProfile>): Int? =
      if (preferred > 0) preferred else profiles.firstOrNull()?.id
  }
}
