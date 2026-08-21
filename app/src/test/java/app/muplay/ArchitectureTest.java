package app.muplay;

import static com.google.common.truth.Truth.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
   * Parses the module paths declared via {@code include ...} in {@code settings.gradle} (e.g.
   * {@code ":app"}, {@code ":core:model"}).
   *
   * <p>Deliberately not {@code trimmed.startsWith("include")}: that would also match {@code
   * includeBuild(...)}, Gradle's composite-build directive, which takes a directory rather than a
   * project path and has nothing to do with this repo's own module list.
   */
  private static List<String> readIncludedModules(Path root) throws IOException {
    Path settingsFile = root.resolve("settings.gradle");
    Pattern modulePathPattern = Pattern.compile(":[\\w.-]+(?::[\\w.-]+)*");
    List<String> modules = new ArrayList<>();
    for (String line : Files.readAllLines(settingsFile)) {
      String trimmed = line.trim();
      if (!(trimmed.startsWith("include ") || trimmed.startsWith("include("))) {
        continue;
      }
      Matcher matcher = modulePathPattern.matcher(trimmed);
      while (matcher.find()) {
        modules.add(matcher.group());
      }
    }
    assertWithFailureMessage(
        !modules.isEmpty(),
        "Found zero included modules while parsing "
            + settingsFile
            + " — this parsing is broken, not verifying anything.");
    return modules;
  }

  /** {@code ":core:model"} under {@code root} resolves to {@code <root>/core/model}. */
  private static Path modulePathToDirectory(Path root, String modulePath) {
    return root.resolve(modulePath.substring(1).replace(':', '/'));
  }

  /**
   * Reads the {@code package} declaration out of a {@code .java} file and combines it with the
   * file's name to get its fully qualified class name. Assumes the standard
   * one-public-top-level-class-per-file convention, matching its filename, that every source file
   * in this repo already follows.
   */
  private static String fullyQualifiedClassName(Path javaFile) throws IOException {
    String packageDeclaration;
    try (Stream<String> lines = Files.lines(javaFile)) {
      packageDeclaration =
          lines
              .map(String::trim)
              .filter(line -> line.startsWith("package "))
              .findFirst()
              .orElseThrow(
                  () -> new IllegalStateException("No package declaration found in " + javaFile));
    }
    String packageName =
        packageDeclaration
            .substring("package ".length(), packageDeclaration.indexOf(';'))
            .trim();
    String simpleName = javaFile.getFileName().toString().replace(".java", "");
    return packageName + "." + simpleName;
  }

  /**
   * The two bans below (no {@code System.currentTimeMillis()} outside {@code app.muplay.di}, no
   * {@code Thread.sleep}) only mean something if {@code CLASSES} actually contains classes from
   * every module that ships production code under {@code app.muplay}. {@code
   * noClasses()...should()...check(CLASSES)} silently, trivially passes when zero classes match —
   * so a module that stops flowing onto {@code :app}'s test classpath (for example, a dropped
   * {@code testImplementation project(...)} line) would make those two rules vacuously green
   * instead of failing, hiding exactly the coverage gap they exist to catch. This was a real,
   * confirmed gap for {@code :testing}: it was only ever pulled in as a {@code testImplementation}
   * of {@code :core:network}, never of {@code :app}, so its classes were never on {@code :app}'s
   * test classpath and the two bans never saw {@code app.muplay.testing.*} at all.
   *
   * <p>Rather than pin a hand-maintained list of representative classes — which stops protecting
   * a module the moment someone forgets to add an entry for it, exactly the failure mode this
   * test exists to catch — this test derives the expected module set from {@code
   * settings.gradle}'s {@code include} declarations directly, so a module a later plan adds is
   * covered automatically with no list to remember to update.
   *
   * <p>For each included module: if it has no {@code src/main/java} directory, or that directory
   * has zero {@code .java} files, the module is skipped — it legitimately has nothing to check yet
   * (true of {@code :core:model} for one task in this project's history, before its first domain
   * class landed). But if it has {@code .java} files and <em>none</em> of them appear in {@code
   * CLASSES}, that is exactly the silent coverage gap this test exists to catch, and this fails
   * loudly, naming the module and the files it expected {@code CLASSES} to contain.
   */
  @Test
  public void classImportCoversEveryModuleTheBansMustReach() throws IOException {
    Path root = findRepoRoot();
    for (String module : readIncludedModules(root)) {
      Path mainSourceDir = modulePathToDirectory(root, module).resolve("src/main/java");
      if (!Files.isDirectory(mainSourceDir)) {
        continue; // No main source set at all for this module — legitimately nothing to check.
      }
      List<Path> javaFiles;
      try (Stream<Path> walk = Files.walk(mainSourceDir)) {
        javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
      }
      if (javaFiles.isEmpty()) {
        continue; // Module exists but has zero production classes yet — legitimately nothing to
        // check, same situation :core:model was in before Task 2.
      }
      List<String> fqcns = new ArrayList<>();
      for (Path javaFile : javaFiles) {
        fqcns.add(fullyQualifiedClassName(javaFile));
      }
      boolean anyClassPresent = fqcns.stream().anyMatch(CLASSES::contain);
      assertWithFailureMessage(
          anyClassPresent,
          "Module "
              + module
              + " has "
              + javaFiles.size()
              + " .java file(s) under "
              + mainSourceDir
              + " ("
              + fqcns
              + ") but none of them appear in this test's class import. The"
              + " System.currentTimeMillis/Thread.sleep bans below cannot see this module's code"
              + " — check its dependency wiring (e.g. a testImplementation project('"
              + module
              + "') line in app/build.gradle).");
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
