package app.muplay.requests

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The two destination keys this module declares.
 *
 * Two properties, both of which have cost this repository a device run before. Navigation 3 throws
 * at **composition** if one route class is registered twice -- `ConventionTest`'s `no navigation
 * graph registers one route class twice` scans for that on the fast tier -- and
 * `rememberNavBackStack` saves the stack through `rememberSaveable`, which needs a `KSerializer` per
 * key. A key that is not `@Serializable` compiles, runs, and fails on process death.
 */
class RequestsRoutesTest {

  @Test
  fun `the two routes are distinct key classes`() {
    // Distinct *classes*, not merely distinct values: `entryProvider` keys its entries on the class,
    // and two objects of one class would be one entry silently shadowing the other.
    assertThat(IntegrationsRoute).isNotEqualTo(RequestsRoute)
    assertThat(IntegrationsRoute::class.java).isNotEqualTo(RequestsRoute::class.java)
    assertThat(IntegrationsRoute).isInstanceOf(NavKey::class.java)
    assertThat(RequestsRoute).isInstanceOf(NavKey::class.java)
  }

  @Test
  fun `both routes survive being saved and restored`() {
    // What `rememberNavBackStack` does on process death. Without `@Serializable` this throws at
    // runtime on a device and passes every compile and every JVM test that does not do this.
    val json = Json { }

    assertThat(json.decodeFromString<IntegrationsRoute>(json.encodeToString(IntegrationsRoute)))
      .isEqualTo(IntegrationsRoute)
    assertThat(json.decodeFromString<RequestsRoute>(json.encodeToString(RequestsRoute)))
      .isEqualTo(RequestsRoute)
  }
}
