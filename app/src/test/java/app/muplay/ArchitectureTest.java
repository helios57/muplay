package app.muplay;

import static com.google.common.truth.Truth.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.Test;

public class ArchitectureTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("app.muplay");

  /**
   * Locates the repository root by walking up from the current working directory until a
   * directory containing {@code settings.gradle} is found.
   *
   * <p>Gradle test tasks run with the module directory (e.g. {@code app/}) as the working
   * directory, not the repository root, so a hard-coded relative path such as {@code ".."} is
   * fragile: it silently breaks if the test task's working directory ever changes, or if this
   * test is executed from a different module. Walking up to the marker file is robust to both.
   */
  private static Path findRepoRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 16; depth++) {
      if (Files.exists(candidate.resolve("settings.gradle"))) {
        return candidate;
      }
      Path parent = candidate.getParent();
      if (parent == null) {
        break;
      }
      candidate = parent;
    }
    throw new IllegalStateException(
        "Could not locate repository root (a directory containing settings.gradle) by walking"
            + " up from "
            + Path.of("").toAbsolutePath());
  }

  /** The project is Java-only. A stray Kotlin plugin must fail the build. */
  @Test
  public void noModuleAppliesTheKotlinPlugin() throws IOException {
    Path root = findRepoRoot();
    List<Path> buildFiles;
    try (Stream<Path> walk = Files.walk(root, 6)) {
      buildFiles =
          walk.filter(p -> p.getFileName().toString().equals("build.gradle"))
              .filter(p -> !p.toString().contains("/build/"))
              .toList();
    }
    assertWithFailureMessage(
        !buildFiles.isEmpty(),
        "Found zero build.gradle files under "
            + root
            + " — this rule is scanning the wrong directory, not verifying anything.");
    for (Path p : buildFiles) {
      String text = Files.readString(p);
      assertThat(text).doesNotContain("kotlin");
    }
  }

  @Test
  public void noKotlinSourceFilesExist() throws IOException {
    Path root = findRepoRoot();
    List<Path> javaFiles;
    List<Path> ktFiles;
    try (Stream<Path> walk = Files.walk(root)) {
      List<Path> all = walk.filter(p -> !p.toString().contains("/build/")).toList();
      javaFiles = all.stream().filter(p -> p.toString().endsWith(".java")).toList();
      ktFiles = all.stream().filter(p -> p.toString().endsWith(".kt")).toList();
    }
    // Sanity check: if we found no .java files either, the walk is scanning an empty or wrong
    // tree, and an empty ktFiles result would be a false pass rather than a real one.
    assertWithFailureMessage(
        !javaFiles.isEmpty(),
        "Found zero .java files under "
            + root
            + " — this rule is scanning the wrong directory, not verifying anything.");
    assertThat(ktFiles).isEmpty();
  }

  @Test
  public void modelModuleDoesNotDependOnAndroid() {
    // :core:model has no domain classes yet at this bootstrap stage (Task 1 only produces module
    // structure), so ArchUnit's default failOnEmptyShould would otherwise reject this rule for
    // matching zero classes. allowEmptyShould(true) is scoped to this one rule only — it does not
    // relax the other rules in this file — and the rule engages for real the moment the first
    // class lands in app.muplay.model.
    noClasses()
        .that()
        .resideInAPackage("app.muplay.model..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("android..", "androidx..")
        .allowEmptyShould(true)
        .check(CLASSES);
  }

  /**
   * The two bans below (no {@code System.currentTimeMillis()} outside {@code app.muplay.di}, no
   * {@code Thread.sleep}) only mean something if {@code CLASSES} actually contains classes from
   * every module that ships production or shared-test-infrastructure code under {@code
   * app.muplay}. {@code noClasses()...should()...check(CLASSES)} silently, trivially passes when
   * zero classes match — so a module that stops flowing onto {@code :app}'s test classpath (for
   * example, a dropped {@code testImplementation project(...)} line) would make those two rules
   * vacuously green instead of failing, hiding exactly the coverage gap they exist to catch. This
   * was a real, confirmed gap for {@code :testing}: it is only ever pulled in as a {@code
   * testImplementation} of {@code :core:network}, never of {@code :app}, so its classes were
   * never on {@code :app}'s test classpath and the two bans never saw
   * {@code app.muplay.testing.*} at all.
   *
   * <p>This test pins one representative, stable class per module currently expected to flow into
   * {@code CLASSES} and fails loudly, naming the missing module, if any of them disappears from
   * the import. If a module is intentionally dropped from this scan, remove its entry here
   * explicitly rather than letting the coverage silently shrink.
   */
  @Test
  public void classImportCoversEveryModuleTheBansMustReach() {
    Map<String, String> representativeClassByModule =
        Map.of(
            ":app", "app.muplay.MuPlayApplication",
            ":core:model", "app.muplay.model.SubsonicCredentials",
            ":core:network", "app.muplay.network.SubsonicAuth",
            ":testing", "app.muplay.testing.OpenApiFixtureValidator");
    for (Map.Entry<String, String> entry : representativeClassByModule.entrySet()) {
      assertWithFailureMessage(
          CLASSES.contain(entry.getValue()),
          "Expected module "
              + entry.getKey()
              + " to be covered by this test's class import (checked via "
              + entry.getValue()
              + "), so the System.currentTimeMillis/Thread.sleep bans actually reach it. Either"
              + " this class was renamed/removed (update this map to a class that still exists),"
              + " or "
              + entry.getKey()
              + " silently stopped flowing onto :app's test classpath — check its dependency wiring"
              + " (e.g. a testImplementation project(...) line in app/build.gradle).");
    }
  }

  @Test
  public void nobodyCallsSystemCurrentTimeMillisOutsideDi() {
    noClasses()
        .that()
        .resideOutsideOfPackage("app.muplay.di..")
        .should()
        .callMethod(System.class, "currentTimeMillis")
        .because("inject java.time.Clock so tests are deterministic")
        .check(CLASSES);
  }

  @Test
  public void nobodyCallsThreadSleep() {
    noClasses()
        .should()
        .callMethod(Thread.class, "sleep", long.class)
        .because("sleeps make tests flaky; await a real signal instead")
        .check(CLASSES);
  }

  private static void assertWithFailureMessage(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
