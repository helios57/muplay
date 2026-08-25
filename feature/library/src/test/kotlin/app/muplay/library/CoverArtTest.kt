package app.muplay.library

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoverArtTest {

  @Test
  fun `the key is derived from the art id and the size`() {
    assertThat(coverArtCacheKey("al-abc_0", 256)).isEqualTo("al-abc_0@256")
    assertThat(coverArtCacheKey("al-abc_0", null)).isEqualTo("al-abc_0@full")
  }

  @Test
  fun `two different sizes of the same art are different cache entries`() {
    // Coil stores a decoded bitmap per key; sharing one key across sizes would serve a 64px
    // thumbnail into a full-width slot.
    assertThat(coverArtCacheKey("al-abc_0", 64)).isNotEqualTo(coverArtCacheKey("al-abc_0", 512))
  }

  @Test
  fun `the key contains nothing from the request url`() {
    // The whole point. An authenticated cover-art URL carries `u`, `t` and a fresh `s` per
    // request, so a URL-derived key can never hit the cache and every scroll re-downloads every
    // cover. Asserting the absence of those parameter names is what stops someone "simplifying"
    // this to `url` later.
    val key = coverArtCacheKey("al-abc_0", 256)

    assertThat(key).doesNotContain("t=")
    assertThat(key).doesNotContain("s=")
    assertThat(key).doesNotContain("u=")
    assertThat(key).doesNotContain("http")
  }

  @Test
  fun `the same art at the same size always produces the same key`() {
    repeat(8) { assertThat(coverArtCacheKey("al-abc_0", 256)).isEqualTo("al-abc_0@256") }
  }
}
