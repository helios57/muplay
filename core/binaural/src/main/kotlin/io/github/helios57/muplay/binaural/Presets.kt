package io.github.helios57.muplay.binaural

/**
 * The profiles the app ships with. Each can be edited on the phone (the edit is stored as an
 * override under the same id, and "Reset" removes it) and duplicated into a profile of the user's
 * own.
 *
 * **These numbers are design defaults, not a clinical protocol.** They follow the conventions in
 * `docs/BINAURAL.md`: the band a beat sits in (delta below 4 Hz, theta 4 to 8, alpha 8 to 12, SMR 12
 * to 15, beta 15 to 30), carriers between 100 and 300 Hz where beats are heard most clearly, calm
 * programs on low carriers, and slow glides -- roughly 0.2 to 0.5 Hz a minute on the way down,
 * because the whole idea rests on the brain following the beat, which big jumps would defeat. No
 * vendor's preset table could be read to copy from, so none is claimed.
 *
 * The one number that is not ours is Concentrate's: 13.46 Hz, left 158.44 Hz and right 144.98 Hz,
 * is the owner's own example from the app they used before. `left = carrier + beat / 2` and
 * `right = carrier - beat / 2` reproduce it exactly from carrier 151.71 Hz.
 *
 * Ids are stable forever: an override is keyed by them, so renaming an id would orphan every
 * listener's edits of that profile.
 */
object Presets {

  private const val MIN = 60.0

  val meditate = BinauralProfile.static(
    id = "meditate", name = "Meditate", beatHz = 7.0, carrierHz = 200.0,
    durationSeconds = 20 * MIN, noiseType = NoiseType.PINK, noiseLevel = 0.02,
    description = "Theta at the edge of alpha. Calm, inward attention.",
  )

  val focus = BinauralProfile.static(
    id = "focus", name = "Focus", beatHz = 18.0, carrierHz = 250.0,
    durationSeconds = 45 * MIN,
    description = "Beta. Alert, active thinking.",
  )

  val concentrate = BinauralProfile.static(
    id = "concentrate", name = "Concentrate", beatHz = 13.46, carrierHz = 151.71,
    durationSeconds = 45 * MIN,
    description = "Low beta at the SMR band: relaxed but sustained attention.",
  )

  val creative = BinauralProfile.static(
    id = "creative", name = "Creative", beatHz = 8.0, carrierHz = 200.0,
    durationSeconds = 30 * MIN, noiseType = NoiseType.PINK, noiseLevel = 0.02,
    description = "The alpha and theta border, where ideas drift.",
  )

  val sleep = BinauralProfile.static(
    id = "sleep", name = "Sleep", beatHz = 3.5, carrierHz = 150.0,
    durationSeconds = 60 * MIN, noiseType = NoiseType.BROWN, noiseLevel = 0.03,
    fadeOutSeconds = 120.0,
    description = "Light delta, just under theta, for falling asleep.",
  )

  val deepSleep = BinauralProfile.static(
    id = "deep-sleep", name = "Deep sleep", beatHz = 2.0, carrierHz = 120.0,
    durationSeconds = 90 * MIN, noiseType = NoiseType.BROWN, noiseLevel = 0.03,
    fadeOutSeconds = 300.0,
    description = "Delta, the slow waves of deep sleep.",
  )

  val relax = BinauralProfile.static(
    id = "relax", name = "Relax", beatHz = 10.0, carrierHz = 200.0,
    durationSeconds = 20 * MIN, noiseType = NoiseType.PINK, noiseLevel = 0.02,
    description = "Alpha. Relaxed wakefulness.",
  )

  val hypnosis = BinauralProfile.static(
    id = "hypnosis", name = "Hypnosis", beatHz = 6.0, carrierHz = 150.0,
    durationSeconds = 30 * MIN, noiseType = NoiseType.PINK, noiseLevel = 0.02,
    description = "Deep theta: trance, dream-like awareness.",
  )

  /**
   * The owner's own description: *"starts with meditation, goes to sleep and deep sleep / hypnosis
   * for 20 minutes (slow) then comes back up"*. Five minutes from meditation to the edge of sleep,
   * five more into sleep, twenty minutes held low and drifting slowly, then six minutes back up to
   * alert -- the return is what makes it a nap rather than a night.
   */
  val powerNap = BinauralProfile(
    id = "power-nap", name = "Power nap",
    segments = listOf(
      Segment(startBeatHz = 8.0, endBeatHz = 6.0, startCarrierHz = 200.0, endCarrierHz = 175.0, durationSeconds = 5 * MIN),
      Segment(startBeatHz = 6.0, endBeatHz = 4.0, startCarrierHz = 175.0, endCarrierHz = 150.0, durationSeconds = 5 * MIN),
      Segment(startBeatHz = 4.0, endBeatHz = 2.5, startCarrierHz = 150.0, endCarrierHz = 130.0, durationSeconds = 20 * MIN, curve = CurveShape.SMOOTH),
      Segment(startBeatHz = 2.5, endBeatHz = 8.0, startCarrierHz = 130.0, endCarrierHz = 180.0, durationSeconds = 3 * MIN, curve = CurveShape.SMOOTH),
      Segment(startBeatHz = 8.0, endBeatHz = 14.0, startCarrierHz = 180.0, endCarrierHz = 220.0, durationSeconds = 3 * MIN),
    ),
    noiseType = NoiseType.PINK, noiseLevel = 0.02,
    description = "Meditation, down into sleep, twenty slow minutes deep, then back up awake.",
  )

  val sleepOnset = BinauralProfile(
    id = "fall-asleep", name = "Fall asleep",
    segments = listOf(
      Segment(startBeatHz = 10.0, endBeatHz = 7.0, startCarrierHz = 200.0, endCarrierHz = 170.0, durationSeconds = 10 * MIN),
      Segment(startBeatHz = 7.0, endBeatHz = 4.0, startCarrierHz = 170.0, endCarrierHz = 140.0, durationSeconds = 20 * MIN),
      Segment(startBeatHz = 4.0, endBeatHz = 2.5, startCarrierHz = 140.0, endCarrierHz = 120.0, durationSeconds = 15 * MIN),
      Segment(beatHz = 2.5, carrierHz = 120.0, durationSeconds = 10 * MIN),
    ),
    noiseType = NoiseType.BROWN, noiseLevel = 0.03, fadeOutSeconds = 300.0,
    description = "Alpha to theta to delta over 45 minutes, then fades out.",
  )

  val night = BinauralProfile(
    id = "deep-sleep-night", name = "Deep sleep night",
    segments = listOf(
      Segment(startBeatHz = 6.0, endBeatHz = 2.0, startCarrierHz = 150.0, endCarrierHz = 120.0, durationSeconds = 20 * MIN),
      Segment(startBeatHz = 2.0, endBeatHz = 1.5, startCarrierHz = 120.0, endCarrierHz = 120.0, durationSeconds = 440 * MIN, curve = CurveShape.SMOOTH),
      Segment(beatHz = 1.5, carrierHz = 120.0, durationSeconds = 20 * MIN),
    ),
    amplitude = 0.06, noiseType = NoiseType.BROWN, noiseLevel = 0.025, fadeOutSeconds = 1200.0,
    description = "Down into delta and held there for eight hours, quietly.",
  )

  val deepMeditation = BinauralProfile(
    id = "deep-meditation", name = "Deep meditation",
    segments = listOf(
      Segment(startBeatHz = 10.0, endBeatHz = 7.0, startCarrierHz = 200.0, endCarrierHz = 180.0, durationSeconds = 10 * MIN),
      Segment(startBeatHz = 7.0, endBeatHz = 6.0, startCarrierHz = 180.0, endCarrierHz = 170.0, durationSeconds = 15 * MIN, curve = CurveShape.SMOOTH),
      Segment(startBeatHz = 6.0, endBeatHz = 8.0, startCarrierHz = 170.0, endCarrierHz = 190.0, durationSeconds = 5 * MIN),
    ),
    noiseType = NoiseType.PINK, noiseLevel = 0.02,
    description = "Alpha into theta, a long stay, and a gentle return.",
  )

  val focusSession = BinauralProfile(
    id = "focus-session", name = "Focus session",
    segments = listOf(
      Segment(startBeatHz = 12.0, endBeatHz = 18.0, startCarrierHz = 200.0, endCarrierHz = 250.0, durationSeconds = 5 * MIN),
      Segment(startBeatHz = 18.0, endBeatHz = 16.0, startCarrierHz = 250.0, endCarrierHz = 250.0, durationSeconds = 33 * MIN, curve = CurveShape.SMOOTH),
      Segment(startBeatHz = 16.0, endBeatHz = 10.0, startCarrierHz = 250.0, endCarrierHz = 200.0, durationSeconds = 12 * MIN),
    ),
    description = "Up into beta, held for half an hour, then eased off.",
  )

  val wakeUp = BinauralProfile(
    id = "wake-up", name = "Wake up",
    segments = listOf(
      Segment(startBeatHz = 6.0, endBeatHz = 10.0, startCarrierHz = 160.0, endCarrierHz = 200.0, durationSeconds = 5 * MIN),
      Segment(startBeatHz = 10.0, endBeatHz = 18.0, startCarrierHz = 200.0, endCarrierHz = 260.0, durationSeconds = 10 * MIN),
    ),
    description = "From theta up to beta in fifteen minutes.",
  )

  /** Programs first (the dynamic ones), then the steady tones, each in the order a day runs. */
  val all: List<BinauralProfile> = listOf(
    powerNap, sleepOnset, night, deepMeditation, focusSession, wakeUp,
    meditate, relax, focus, concentrate, creative, hypnosis, sleep, deepSleep,
  )

  private val byId = all.associateBy { it.id }

  fun builtIn(id: String): BinauralProfile? = byId[id]
}
