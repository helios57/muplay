package io.github.helios57.muplay.binaural

/** Something about a profile that the generator refuses, or that the listener should know. */
data class ProfileProblem(val message: String, val blocking: Boolean)

/**
 * What is wrong with [profile], in the words the editor shows. Blocking problems stop a save --
 * the generator could not render them, or they would be unpleasant (a 3 kHz whine, a beat no ear
 * resolves). Warnings are advice and do not.
 */
fun problemsOf(profile: BinauralProfile): List<ProfileProblem> = buildList {
  fun block(message: String) = add(ProfileProblem(message, blocking = true))
  fun warn(message: String) = add(ProfileProblem(message, blocking = false))

  if (profile.name.isBlank()) block("Give the profile a name.")
  if (profile.name.length > Limits.MAX_NAME_LENGTH) {
    block("Keep the name under ${Limits.MAX_NAME_LENGTH} characters.")
  }
  if (profile.segments.isEmpty()) block("A profile needs at least one step.")
  if (profile.segments.size > Limits.MAX_SEGMENTS) block("Use at most ${Limits.MAX_SEGMENTS} steps.")

  profile.segments.forEachIndexed { index, s ->
    val step = "Step ${index + 1}"
    val beats = listOf(s.startBeatHz, s.endBeatHz)
    val carriers = listOf(s.startCarrierHz, s.endCarrierHz)
    if (beats.any { !it.isFinite() || it < Limits.MIN_BEAT_HZ || it > Limits.MAX_BEAT_HZ }) {
      block("$step: the beat must be between ${Limits.MIN_BEAT_HZ} and ${Limits.MAX_BEAT_HZ.toInt()} Hz.")
    } else if (beats.any { it > Limits.WARN_BEAT_HZ }) {
      warn("$step: above about 30 Hz most people hear two tones rather than a beat.")
    }
    if (carriers.any { !it.isFinite() || it < Limits.MIN_CARRIER_HZ || it > Limits.MAX_CARRIER_HZ }) {
      block("$step: the carrier must be between ${Limits.MIN_CARRIER_HZ.toInt()} and ${Limits.MAX_CARRIER_HZ.toInt()} Hz.")
    } else if (carriers.any { it < Limits.RECOMMENDED_CARRIER_MIN_HZ || it > Limits.RECOMMENDED_CARRIER_MAX_HZ }) {
      warn("$step: beats are heard most clearly on a carrier between 100 and 500 Hz.")
    }
    // The right ear's tone is carrier - beat / 2; it must stay a real, audible tone.
    if (beats.indices.any { carriers[it] - beats[it] / 2 < Limits.MIN_CARRIER_HZ }) {
      block("$step: the beat is too wide for this carrier.")
    }
    if (!s.durationSeconds.isFinite() || s.durationSeconds < Limits.MIN_SEGMENT_SECONDS) {
      block("$step: a step lasts at least a second.")
    }
    if (s.curve == CurveShape.EXPONENTIAL && beats.any { it <= 0.0 }) {
      block("$step: an exponential glide needs a beat above zero.")
    }
  }
  if (profile.totalSeconds > Limits.MAX_TOTAL_SECONDS) block("A profile can run for at most 12 hours.")

  if (!profile.amplitude.isFinite() || profile.amplitude <= 0.0 || profile.amplitude > Limits.MAX_AMPLITUDE) {
    block("The tone level must be above zero and at most ${Limits.MAX_AMPLITUDE}.")
  } else if (profile.amplitude > Limits.WARN_AMPLITUDE) {
    warn("That is loud. Binaural beats work at a quiet level.")
  }
  if (!profile.noiseLevel.isFinite() || profile.noiseLevel < 0.0 || profile.noiseLevel > Limits.MAX_NOISE_LEVEL) {
    block("The noise level must be between 0 and ${Limits.MAX_NOISE_LEVEL}.")
  }
  listOf(profile.fadeInSeconds, profile.fadeOutSeconds).forEach {
    if (!it.isFinite() || it < 0.0 || it > Limits.MAX_FADE_SECONDS) block("Fades are between 0 and 30 minutes.")
  }
}.distinct()

fun BinauralProfile.isPlayable(): Boolean = problemsOf(this).none { it.blocking }
