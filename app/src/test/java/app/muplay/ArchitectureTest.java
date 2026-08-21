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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
   * Enumerates this repo's Gradle modules by walking the filesystem for {@code build.gradle}
   * files (excluding the root project's own), mirroring how {@link #noModuleAppliesTheKotlinPlugin}
   * already finds them.
   *
   * <p>This — not a {@code settings.gradle} parse — is what actually determines the module list
   * {@link #classImportCoversEveryModuleTheBansMustReach} checks {@link #CLASSES} against. An
   * earlier version of that test parsed {@code settings.gradle}'s {@code include ...}
   * declarations line by line, which silently dropped any module declared on a continuation line
   * (an {@code include(} whose argument list, or a comma-separated {@code include} list, wraps
   * across multiple lines) — the loop below then simply never iterated over the dropped module:
   * no assertion, no failure, the exact vacuous-pass defect this whole test exists to eliminate,
   * just moved up one layer. Walking the filesystem for {@code build.gradle} files cannot be
   * defeated by how {@code settings.gradle} happens to be formatted. A directory that has a
   * {@code build.gradle} but was never wired into {@code settings.gradle} is an equally real
   * anomaly, and this deliberately still fails loudly on it rather than silently ignoring it:
   * such a module's classes will never appear in {@link #CLASSES} either, for the same underlying
   * reason.
   *
   * @return each discovered module's Gradle path (e.g. {@code ":core:model"}) mapped to its
   *     directory
   */
  private static Map<String, Path> discoverModulesFromBuildFiles(Path root) throws IOException {
    List<Path> buildFiles;
    try (Stream<Path> walk = Files.walk(root, 6)) {
      buildFiles =
          walk.filter(p -> p.getFileName().toString().equals("build.gradle"))
              .filter(p -> !p.toString().contains("/build/"))
              .filter(p -> !root.equals(p.getParent())) // exclude the root project's own
              .toList();
    }
    Map<String, Path> directoryByModulePath = new LinkedHashMap<>();
    for (Path buildFile : buildFiles) {
      Path moduleDir = buildFile.getParent();
      String modulePath = ":" + root.relativize(moduleDir).toString().replace('/', ':');
      directoryByModulePath.put(modulePath, moduleDir);
    }
    assertWithFailureMessage(
        !directoryByModulePath.isEmpty(),
        "Found zero module build.gradle files under "
            + root
            + " (excluding the root project's own) — this discovery is broken, not verifying"
            + " anything.");
    return directoryByModulePath;
  }

  /**
   * A second, independent count of the declared module set, used only to cross-check {@link
   * #discoverModulesFromBuildFiles} — not as the source of truth for which modules {@link
   * #classImportCoversEveryModuleTheBansMustReach} actually checks. This is exactly the kind of
   * check that would have caught that test's own earlier bug: a module silently dropped by one
   * derivation produces no visible symptom on its own, but it does produce a mismatched count
   * against an independent derivation, and a mismatch fails loudly instead of quietly
   * under-covering.
   *
   * <p>Reads {@code settings.gradle} as one whole string rather than line by line — a
   * line-oriented reader is exactly what missed a continuation line before — and strips comments
   * first so a commented-out {@code include(...)} cannot inflate the count. Matches every {@code
   * include ...} / {@code include(...)} statement (parenthesized or not, single- or multi-line)
   * and counts the quoted module-path tokens inside each one.
   */
  private static int countModulesDeclaredInSettingsGradle(Path root) throws IOException {
    String text = Files.readString(root.resolve("settings.gradle"));
    String withoutComments = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\n]*", "");
    Pattern modulePathPattern = Pattern.compile(":[\\w.-]+(?::[\\w.-]+)*");
    Pattern includeStatementPattern =
        Pattern.compile(
            "include\\s*\\(?\\s*((?:['\"]" + modulePathPattern.pattern() + "['\"]\\s*,?\\s*)+)\\)?");
    int count = 0;
    Matcher statementMatcher = includeStatementPattern.matcher(withoutComments);
    while (statementMatcher.find()) {
      Matcher moduleMatcher = modulePathPattern.matcher(statementMatcher.group(1));
      while (moduleMatcher.find()) {
        count++;
      }
    }
    return count;
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
   * <p>The module set this test checks comes from {@link #discoverModulesFromBuildFiles} (a
   * filesystem walk, not a hand-maintained list — see that method's doc for why a hand-maintained
   * list, and even an earlier {@code settings.gradle}-parsing version of this test, both silently
   * under-covered). {@link #countModulesDeclaredInSettingsGradle} cross-checks its count
   * independently so a systematic under-count in the filesystem walk cannot go unnoticed either.
   *
   * <p>For each discovered module: if it has no {@code src/main/java} directory, or that
   * directory has zero {@code .java} files, the module is skipped — it legitimately has nothing
   * to check yet (true of {@code :core:model} for one task in this project's history, before its
   * first domain class landed). But if it has {@code .java} files and <em>none</em> of them
   * appear in {@code CLASSES}, that is exactly the silent coverage gap this test exists to catch,
   * and this fails loudly, naming the module and the files it expected {@code CLASSES} to
   * contain.
   */
  @Test
  public void classImportCoversEveryModuleTheBansMustReach() throws IOException {
    Path root = findRepoRoot();
    Map<String, Path> moduleDirectories = discoverModulesFromBuildFiles(root);
    int declaredInSettings = countModulesDeclaredInSettingsGradle(root);
    assertWithFailureMessage(
        moduleDirectories.size() == declaredInSettings,
        "Module discovery disagreement: found "
            + moduleDirectories.size()
            + " module(s) with a build.gradle on disk "
            + moduleDirectories.keySet()
            + ", but settings.gradle declares "
            + declaredInSettings
            + " module(s). One of these two derivations is silently seeing fewer (or more)"
            + " modules than the other — investigate rather than trusting either count blindly;"
            + " this disagreement is exactly the class of bug this cross-check exists to catch.");
    for (Map.Entry<String, Path> entry : moduleDirectories.entrySet()) {
      String module = entry.getKey();
      Path mainSourceDir = entry.getValue().resolve("src/main/java");
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
