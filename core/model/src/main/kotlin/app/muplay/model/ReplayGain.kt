package app.muplay.model

/**
 * The ReplayGain values a server reports for one file.
 *
 * Spec section 4: **ReplayGain is exposed but not applied server-side; the client applies it.**
 * Navidrome reads these out of the file's own tags and hands them over on every browse response;
 * nothing computes them here and nothing ever will -- this project does no loudness analysis.
 *
 * Every field is nullable because every field is genuinely optional: an untagged file reports
 * none of them, and a file tagged by an album-oriented tool may carry an album gain and no track
 * gain. `null` means "the file does not say", which is a different fact from `0.0f` ("the file
 * says no adjustment is needed") and the two must not be collapsed.
 *
 * @property trackGainDb the adjustment for this file played on its own, in decibels.
 * @property albumGainDb the adjustment for this file played as part of its album, in decibels.
 * @property peakAmplitude the file's highest sample as a fraction of full scale, so that a
 *   positive gain can be clamped short of clipping. Taken from the track peak, falling back to the
 *   album peak.
 */
data class ReplayGain(
  val trackGainDb: Float?,
  val albumGainDb: Float?,
  val peakAmplitude: Float?,
)
