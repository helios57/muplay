package app.muplay

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Deliberately trivial: exists so `:app`'s JaCoCo report has at least one real, non-generated
 * class it can measure non-zero coverage on. See `Jacoco.kt`'s `configureAndroidJacocoReport` —
 * without this, the "does the report actually work" question could not be answered by evidence.
 */
class MuPlayApplicationTest {

  @Test
  fun `constructs without touching Hilt's generated component`() {
    assertThat(MuPlayApplication()).isNotNull()
  }
}
