package io.github.helios57.muplay.waves

import io.github.helios57.muplay.binaural.BinauralProfile
import io.github.helios57.muplay.binaural.leftHz
import io.github.helios57.muplay.binaural.rightHz
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToLong

const val WAVES_TITLE = "Waves"
const val PROGRAMS_HEADING = "Programs"
const val TONES_HEADING = "Steady tones"
const val YOURS_HEADING = "Your profiles"
const val STOP_LABEL = "Stop"
const val PLAY_LABEL = "Play"
const val EDIT_LABEL = "Edit"
const val NEW_PROFILE_LABEL = "New profile"
const val SAVE_LABEL = "Save"
const val TRY_LABEL = "Try it"
const val RESET_LABEL = "Reset to default"
const val DUPLICATE_LABEL = "Duplicate"
const val DELETE_LABEL = "Delete"
const val ADD_STEP_LABEL = "Add step"
const val REMOVE_STEP_LABEL = "Remove step"
const val TONE_VOLUME_LABEL = "Tone volume"
const val ACKNOWLEDGE_LABEL = "I understand"
const val EDITED_BADGE = "Edited"
const val EDITOR_TITLE = "Edit profile"
const val BACK_LABEL = "Back"

/** Shown once, until acknowledged, and kept on the screen as a single line afterwards. */
const val SAFETY_TEXT = "Binaural beats need headphones: each ear hears a slightly different tone, and the " +
  "beat is the difference your brain hears. Keep the volume low. Do not listen while driving or " +
  "operating machinery. If you have epilepsy, a seizure disorder or a heart condition, ask a doctor " +
  "first. This is not medical advice: binaural beats may help you relax, but their effects on sleep " +
  "and focus are not proven."
const val SAFETY_REMINDER = "Headphones on, volume low. Not while driving."

/** 13.46 -> "13.46", 8.0 -> "8", 2.5 -> "2.5": at most two decimals, never trailing zeros. */
fun formatHz(hz: Double): String =
  BigDecimal(hz).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** 2160 s -> "36 min", 28800 s -> "8 h", 5400 s -> "1 h 30 min", 45 s -> "1 min". */
fun formatLength(seconds: Double): String {
  val minutes = maxOf(1L, (seconds / 60.0).roundToLong())
  val hours = minutes / 60
  val rest = minutes % 60
  return when {
    hours == 0L -> "$minutes min"
    rest == 0L -> "$hours h"
    else -> "$hours h $rest min"
  }
}

/** A position on a clock face: "12:05", or "1:02:03" past an hour. */
fun formatClock(seconds: Double): String {
  val total = seconds.toLong().coerceAtLeast(0)
  val h = total / 3600
  val m = total % 3600 / 60
  val s = total % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun leftRightText(carrierHz: Double, beatHz: Double): String =
  "Left ${formatHz(leftHz(carrierHz, beatHz))} Hz · right ${formatHz(rightHz(carrierHz, beatHz))} Hz"

/**
 * The shape of a program as the beats it turns at: Power nap's 8, 6, 4, 2.5, 8, 14 reads
 * "8 → 2.5 → 14 Hz" -- down, and back up. Intermediate points on a straight run say nothing.
 */
fun programPath(profile: BinauralProfile): String {
  val points = buildList {
    profile.segments.firstOrNull()?.let { add(it.startBeatHz) }
    profile.segments.forEach { add(it.endBeatHz) }
  }.fold(mutableListOf<Double>()) { acc, hz -> if (acc.lastOrNull() != hz) acc += hz; acc }
  val turns = points.filterIndexed { i, hz ->
    i == 0 || i == points.lastIndex || (hz - points[i - 1]) * (points[i + 1] - hz) < 0
  }
  return turns.joinToString(" → ") { formatHz(it) } + " Hz"
}

/** One line for a row on the list. */
fun summaryOf(profile: BinauralProfile): String =
  if (profile.isStatic) {
    val s = profile.segments.single()
    "${formatHz(s.startBeatHz)} Hz · ${io.github.helios57.muplay.binaural.Band.of(s.startBeatHz).label} · " +
      formatLength(profile.totalSeconds)
  } else {
    "${formatLength(profile.totalSeconds)} · ${programPath(profile)}"
  }

/** The second line: the tones themselves for a steady profile, the description for a program. */
fun detailOf(profile: BinauralProfile): String =
  if (profile.isStatic) {
    val s = profile.segments.single()
    leftRightText(s.startCarrierHz, s.startBeatHz)
  } else {
    profile.description
  }

fun notificationText(status: BinauralStatus.Playing): String {
  if (status.stopping) return "Stopping…"
  val remaining = status.profile.totalSeconds - status.positionSeconds
  val left = if (status.profile.endBehaviour == io.github.helios57.muplay.binaural.EndBehaviour.HOLD_LAST && remaining <= 0) {
    "holding"
  } else {
    "${formatLength(remaining.coerceAtLeast(0.0))} left"
  }
  return "${status.sample.band.label} · ${formatHz(status.sample.beatHz)} Hz · $left"
}
