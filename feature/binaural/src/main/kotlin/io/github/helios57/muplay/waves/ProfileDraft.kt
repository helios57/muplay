package io.github.helios57.muplay.waves

import io.github.helios57.muplay.binaural.BinauralProfile
import io.github.helios57.muplay.binaural.CurveShape
import io.github.helios57.muplay.binaural.EndBehaviour
import io.github.helios57.muplay.binaural.NoiseType
import io.github.helios57.muplay.binaural.ProfileProblem
import io.github.helios57.muplay.binaural.Segment
import io.github.helios57.muplay.binaural.problemsOf

/** One step of a profile as the editor holds it: text, because a half-typed number is not one. */
data class SegmentDraft(
  val startBeat: String,
  val endBeat: String,
  val startCarrier: String,
  val endCarrier: String,
  val minutes: String,
  val curve: CurveShape = CurveShape.LINEAR,
) {
  fun toSegment(): Segment? {
    val numbers = listOf(startBeat, endBeat, startCarrier, endCarrier, minutes).map(::parseNumber)
    if (numbers.any { it == null }) return null
    val (sb, eb, sc, ec, min) = numbers.map { it!! }
    return Segment(sb, eb, sc, ec, min * 60.0, curve)
  }

  companion object {
    fun of(s: Segment) = SegmentDraft(
      startBeat = formatHz(s.startBeatHz), endBeat = formatHz(s.endBeatHz),
      startCarrier = formatHz(s.startCarrierHz), endCarrier = formatHz(s.endCarrierHz),
      minutes = formatHz(s.durationSeconds / 60.0), curve = s.curve,
    )
  }
}

/**
 * **A profile being edited.** Numbers stay text until [toProfile], so the editor can show "13."
 * while it is typed, and a comma is read as a decimal point -- the owner's keyboard is Swiss.
 */
data class ProfileDraft(
  val id: String,
  val name: String,
  val segments: List<SegmentDraft>,
  val amplitude: Double,
  val noiseType: NoiseType,
  val noiseLevel: Double,
  val fadeInSeconds: String,
  val fadeOutSeconds: String,
  val endBehaviour: EndBehaviour,
  val description: String,
) {
  /** The profile this draft describes, or null while any number in it does not parse. */
  fun toProfile(): BinauralProfile? {
    val steps = segments.map { it.toSegment() ?: return null }
    val fadeIn = parseNumber(fadeInSeconds) ?: return null
    val fadeOut = parseNumber(fadeOutSeconds) ?: return null
    return BinauralProfile(
      id = id, name = name.trim(), segments = steps, amplitude = amplitude,
      noiseType = noiseType, noiseLevel = if (noiseType == NoiseType.NONE) 0.0 else noiseLevel,
      fadeInSeconds = fadeIn, fadeOutSeconds = fadeOut, endBehaviour = endBehaviour,
      description = description,
    )
  }

  /** Everything to tell the listener about this draft, unreadable numbers first. */
  fun problems(): List<ProfileProblem> {
    val unreadable = buildList {
      segments.forEachIndexed { i, s ->
        if (s.toSegment() == null) add(ProfileProblem("Step ${i + 1}: every field needs a number.", blocking = true))
      }
      if (parseNumber(fadeInSeconds) == null || parseNumber(fadeOutSeconds) == null) {
        add(ProfileProblem("Fades need a number of seconds.", blocking = true))
      }
    }
    return unreadable.ifEmpty { problemsOf(toProfile()!!) }
  }

  val canSave: Boolean get() = problems().none { it.blocking }

  fun withSegment(index: Int, transform: (SegmentDraft) -> SegmentDraft) =
    copy(segments = segments.mapIndexed { i, s -> if (i == index) transform(s) else s })

  /** A new step continuing from where the last one ends, steady for five minutes. */
  fun withStepAdded(): ProfileDraft {
    val last = segments.lastOrNull()
    val step = SegmentDraft(
      startBeat = last?.endBeat ?: "10", endBeat = last?.endBeat ?: "10",
      startCarrier = last?.endCarrier ?: "200", endCarrier = last?.endCarrier ?: "200",
      minutes = "5",
    )
    return copy(segments = segments + step)
  }

  fun withStepRemoved(index: Int) =
    if (segments.size <= 1) this else copy(segments = segments.filterIndexed { i, _ -> i != index })

  companion object {
    fun of(profile: BinauralProfile) = ProfileDraft(
      id = profile.id, name = profile.name, segments = profile.segments.map(SegmentDraft::of),
      amplitude = profile.amplitude, noiseType = profile.noiseType, noiseLevel = profile.noiseLevel,
      fadeInSeconds = formatHz(profile.fadeInSeconds), fadeOutSeconds = formatHz(profile.fadeOutSeconds),
      endBehaviour = profile.endBehaviour, description = profile.description,
    )
  }
}

/** "13,46", " 13.46 " and "13.46" are all 13.46; anything else is no number. */
fun parseNumber(text: String): Double? =
  text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
