package app.muplay.cast.control

/**
 * What a renderer says it can do, read from its own `AVTransport` service description.
 *
 * Read rather than guessed, and rather than discovered by trying: a device that cannot seek should
 * show **no seek bar** (Task 8 reports this in `availableCommands`), not one that produces
 * `710 Seek mode not supported` on every drag. Offering a control that silently fails is the
 * defect class this plan is written against.
 *
 * The alternative -- send `REL_TIME`, catch 710, retry `ABS_TIME` -- was considered and rejected:
 * it makes the *first* seek of every session on an `ABS_TIME`-only device fail visibly before
 * succeeding, and it leaves the UI no way to know in advance whether seeking works at all.
 */
data class RendererCapabilities(
  /** `A_ARG_TYPE_SeekMode`'s `allowedValueList`, in the order the device declared it. */
  val seekModes: List<String>,
  val supportsSetNextUri: Boolean,
) {
  /**
   * The mode to seek with, or `null` when this device cannot seek by time at all.
   *
   * [REL_TIME] is preferred where offered because it means "relative to the start of the track",
   * which is the position this app has. [ABS_TIME] is the fallback. `TRACK_NR` and
   * `X_DLNA_REL_BYTE` are seek modes but not *time* seek modes, and a byte offset is not something
   * this app can compute for a transcoded stream.
   */
  val preferredSeekMode: String?
    get() = when {
      REL_TIME in seekModes -> REL_TIME
      ABS_TIME in seekModes -> ABS_TIME
      else -> null
    }

  companion object {
    const val REL_TIME: String = "REL_TIME"
    const val ABS_TIME: String = "ABS_TIME"

    /** The action a renderer must declare before [UpnpRenderer.setNextUri] will call it. */
    const val ACTION_SET_NEXT_URI: String = "SetNextAVTransportURI"

    /**
     * What is assumed when the SCPD cannot be read.
     *
     * Optimistic about seeking (`REL_TIME` is what Sonos and almost every renderer accepts; being
     * wrong costs one failed seek and a `false` return) and pessimistic about
     * [ACTION_SET_NEXT_URI] (being wrong there costs a `401` at every track transition, which is
     * a gap in the middle of an album -- and on some firmware clears the queue outright).
     */
    val DEFAULT: RendererCapabilities =
      RendererCapabilities(seekModes = listOf(REL_TIME), supportsSetNextUri = false)

    /**
     * The two facts this app needs out of an `AVTransport` SCPD.
     *
     * **A regex rather than a DOM parse, and this is the one place in this plan where that is the
     * right answer.** Both facts are flat string lists inside uniquely-named elements, and the
     * document is not one this app acts on structurally -- nothing here becomes a URL to dial, the
     * way `DeviceDescription`'s output does, which is why *that* one gets a real parse and a
     * DOCTYPE refusal and this one does not. If a later plan needs more of the SCPD than these two
     * facts, that is the point to promote it.
     */
    fun fromScpd(xml: String): RendererCapabilities {
      // Taken apart rather than chained through `?.`: a chain of safe calls over values the
      // platform never makes null (`groupValues`, `get`) compiles to null checks whose other arm
      // no test can reach, and this module gates itself on BRANCH.
      val block = SEEK_MODE_BLOCK.find(xml) ?: return DEFAULT
      val modes = ALLOWED_VALUE.findAll(block.groupValues[1]).map { it.groupValues[1] }.toList()

      if (modes.isEmpty()) return DEFAULT
      return RendererCapabilities(
        seekModes = modes,
        supportsSetNextUri = SET_NEXT_URI_ACTION.containsMatchIn(xml),
      )
    }

    private val SEEK_MODE_BLOCK = Regex(
      "<name>\\s*A_ARG_TYPE_SeekMode\\s*</name>.*?<allowedValueList>(.*?)</allowedValueList>",
      RegexOption.DOT_MATCHES_ALL,
    )
    private val ALLOWED_VALUE = Regex("<allowedValue>\\s*(.*?)\\s*</allowedValue>")
    private val SET_NEXT_URI_ACTION = Regex("<name>\\s*$ACTION_SET_NEXT_URI\\s*</name>")
  }
}
