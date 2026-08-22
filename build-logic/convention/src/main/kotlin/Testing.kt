import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * JUnit 5 for every module: `useJUnitPlatform()` on every `Test` task (this covers Android unit
 * test tasks too — `AndroidUnitTest` is itself a `Test` subtype), plus the jupiter engine, the
 * platform launcher JUnit 5 needs at runtime, and AssertJ. One place, so no module reaches for
 * JUnit 4 or a mock framework out of habit.
 */
internal fun Project.configureJUnit5() {
  tasks.withType<Test>().configureEach {
    useJUnitPlatform()
  }

  dependencies {
    add("testImplementation", libs.findLibrary("junit-jupiter").get())
    add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
    add("testImplementation", libs.findLibrary("assertj").get())
  }
}
