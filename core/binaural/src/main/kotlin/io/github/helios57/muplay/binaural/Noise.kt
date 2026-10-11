package io.github.helios57.muplay.binaural

import kotlin.math.sqrt

/**
 * A seeded noise source normalised to **unit RMS**, so a profile's `noiseLevel` is the bed's RMS in
 * linear full scale and "-30 dBFS of pink" means the same thing as "-30 dBFS of brown".
 *
 * Deterministic: the same type and seed give the same sequence on every JVM and every device, which
 * is what lets the tests assert on rendered audio rather than on a statistic of a random run.
 *
 * - **White**: uniform, scaled for unit variance.
 * - **Pink** (-3 dB per octave in power spectral density): Paul Kellet's "refined" filter over white,
 *   accurate to a fraction of a dB across the audible range.
 * - **Brown** (-6 dB per octave): white through a leaky integrator. The leak puts the corner near
 *   20 Hz, so below the audible range the spectrum flattens instead of wandering off to DC.
 */
internal class NoiseGenerator(private val type: NoiseType, seed: Long) {

  private var state: Long = if (seed == 0L) SEED_FALLBACK else seed

  // Kellet filter state.
  private var b0 = 0.0
  private var b1 = 0.0
  private var b2 = 0.0
  private var b3 = 0.0
  private var b4 = 0.0
  private var b5 = 0.0
  private var b6 = 0.0

  // Brown integrator state.
  private var brown = 0.0

  /** Uniform in `[-1, 1)`, from xorshift64* -- fast, seedable and identical everywhere. */
  private fun uniform(): Double {
    var x = state
    x = x xor (x ushr 12)
    x = x xor (x shl 25)
    x = x xor (x ushr 27)
    state = x
    val bits = (x * 2685821657736338717L) ushr 11 // 53 random bits
    return bits.toDouble() / (1L shl 52).toDouble() - 1.0
  }

  fun next(): Double {
    val w = uniform()
    return when (type) {
      NoiseType.NONE -> 0.0
      NoiseType.WHITE -> w * WHITE_GAIN
      NoiseType.PINK -> {
        b0 = 0.99886 * b0 + w * 0.0555179
        b1 = 0.99332 * b1 + w * 0.0750759
        b2 = 0.96900 * b2 + w * 0.1538520
        b3 = 0.86650 * b3 + w * 0.3104856
        b4 = 0.55000 * b4 + w * 0.5329522
        b5 = -0.7616 * b5 - w * 0.0168980
        val out = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
        b6 = w * 0.115926
        out * PINK_GAIN
      }
      NoiseType.BROWN -> {
        brown = BROWN_LEAK * brown + w * BROWN_INPUT_GAIN
        brown
      }
    }
  }

  private companion object {
    val SEED_FALLBACK = 0x9E3779B97F4A7C15uL.toLong()

    /** `1 / sqrt(variance of uniform[-1,1))` = `sqrt(3)`. */
    val WHITE_GAIN = sqrt(3.0)

    /**
     * Measured: the Kellet filter's RMS over uniform[-1,1) input is 1.761, inverted. It was 0.0 in
     * the first draft, which made "pink" silent -- `NoiseGeneratorTest` holds all three to unit RMS
     * so that a noise level means the same loudness whichever colour is picked.
     */
    const val PINK_GAIN = 0.568

    const val BROWN_LEAK = 0.9974

    /** `sqrt(3 * (1 - leak^2))`: unit variance out of a leaky integrator driven by uniform noise. */
    val BROWN_INPUT_GAIN = sqrt(3.0 * (1.0 - BROWN_LEAK * BROWN_LEAK))
  }
}
