package app.muplay;

import static com.google.common.truth.Truth.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

  /**
   * Catches an explicit, textual Kotlin plugin application — {@code id
   * 'org.jetbrains.kotlin.android'}, {@code apply plugin: 'kotlin-android'}, and the like — typed
   * into a build script by a future contributor.
   *
   * <p><b>This is not, and cannot be, a guarantee that no Kotlin plugin is applied.</b> AGP
   * 9.3.1's own built-in Kotlin support applies a real Kotlin Gradle Plugin to every Android
   * module in this project regardless of what any build script says: confirmed empirically via
   * {@code ./gradlew :core:network:properties} (reports {@code kotlin.plugin.loaded.in.projects}
   * and a {@code KotlinAndroidProjectExtension}) and {@code ./gradlew :core:network:tasks --all}
   * (lists {@code compileDebugKotlin}, {@code compileDebugUnitTestKotlin}, {@code
   * compileDebugAndroidTestKotlin} on both {@code :app} and {@code :core:network}). A {@code .kt}
   * file dropped into {@code core/network/src/main/kotlin/} compiles and produces a {@code
   * .class} via {@code compileDebugKotlin} with zero build-script changes — verified directly by
   * doing exactly that during this fix wave. No text scan of any build script, including this
   * one, can see or prevent that, because AGP applies its Kotlin support programmatically, not
   * through a plugin id or {@code apply} statement any grep-like scan would match.
   *
   * <p>The actual, load-bearing guarantee that Kotlin cannot compile in this repository is the
   * filesystem-level ban on {@code .kt} source files ({@link #noKotlinSourceFilesExist}) and
   * {@code .kts} scripts ({@link #noKotlinFilesExistAnywhere}) below — with no Kotlin source to
   * compile, AGP's built-in Kotlin support has nothing to do, registered tasks and all. This rule
   * remains as one more layer of defense-in-depth against the narrower case it can actually
   * catch (a deliberate, explicit plugin declaration), not as a substitute for that filesystem
   * ban and not as evidence Kotlin cannot compile here.
   */
  @Test
  public void noModuleAppliesTheKotlinPluginExplicitly() throws IOException {
    Path root = findRepoRoot();
    List<Path> buildFiles;
    try (Stream<Path> walk = Files.walk(root, 6)) {
      buildFiles =
          walk.filter(p -> p.getFileName().toString().matches("build\\.gradle(\\.kts)?"))
              .filter(p -> !p.toString().contains("/build/"))
              .toList();
    }
    assertWithFailureMessage(
        !buildFiles.isEmpty(),
        "Found zero build.gradle(.kts) files under "
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

  /**
   * "No Kotlin anywhere" (the project's hardest constraint — see the plan's global constraints)
   * is not just about {@code .kt} source files: a {@code build.gradle.kts}, a {@code
   * settings.gradle.kts}, or any other {@code .kts} script is Kotlin too, and {@link
   * #noModuleAppliesTheKotlinPluginExplicitly} above only inspects files that look like Gradle
   * build scripts — it would not, on its own, catch some other stray {@code .kts} file (a init
   * script, a precompiled script plugin, anything) that never happens to match {@code
   * build.gradle(.kts)}. This is the blanket backstop: any {@code .kts} file anywhere in the repo
   * fails the build outright, independent of what it's named or what it contains.
   */
  @Test
  public void noKotlinFilesExistAnywhere() throws IOException {
    Path root = findRepoRoot();
    List<Path> javaFiles;
    List<Path> ktsFiles;
    try (Stream<Path> walk = Files.walk(root)) {
      List<Path> all = walk.filter(p -> !p.toString().contains("/build/")).toList();
      javaFiles = all.stream().filter(p -> p.toString().endsWith(".java")).toList();
      ktsFiles = all.stream().filter(p -> p.toString().endsWith(".kts")).toList();
    }
    // Same sanity check as noKotlinSourceFilesExist: prove the walk actually canvassed the repo
    // before trusting an empty ktsFiles result as a real pass rather than a vacuous one.
    assertWithFailureMessage(
        !javaFiles.isEmpty(),
        "Found zero .java files under "
            + root
            + " — this rule is scanning the wrong directory, not verifying anything.");
    assertWithFailureMessage(
        ktsFiles.isEmpty(),
        "Found .kts file(s) under "
            + root
            + " which are banned outright regardless of content: "
            + ktsFiles);
  }

  @Test
  public void modelModuleDoesNotDependOnAndroid() {
    // allowEmptyShould(true) was justified at bootstrap (Task 1: :core:model had no domain
    // classes yet, so this rule matched zero classes and ArchUnit's default failOnEmptyShould
    // would have rejected it as vacuous). :core:model now has four classes (LibraryRole,
    // MusicLibrary, ServerCapabilities, SubsonicCredentials), so the relaxation is stale — this
    // was the only ArchUnit relaxation in this file, and the Definition of Done bans them.
    // Removed; the rule below now fails loudly (not vacuously) if it ever matches zero classes
    // again, same as every other scanning rule in this file.
    noClasses()
        .that()
        .resideInAPackage("app.muplay.model..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("android..", "androidx..")
        .check(CLASSES);
  }

  /**
   * Enumerates this repo's Gradle modules by walking the filesystem for {@code build.gradle}
   * files (excluding the root project's own), mirroring how {@link
   * #noModuleAppliesTheKotlinPluginExplicitly} already finds them.
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

  private static final String JACKSON_ANNOTATION_PACKAGE_PREFIX = "com.fasterxml.jackson.";
  private static final String JSON_CREATOR = "com.fasterxml.jackson.annotation.JsonCreator";
  private static final String JSON_PROPERTY = "com.fasterxml.jackson.annotation.JsonProperty";

  /**
   * Every Jackson-deserialized DTO record must have an explicit {@code @JsonCreator} canonical
   * constructor with {@code @JsonProperty} on every parameter. Why this matters, in one sentence:
   * Android's D8 compiler strips a record's {@code java.lang.Record} reflection metadata for any
   * module with {@code minSdk < 33} (this project's {@code minSdk} is 26), Robolectric never sees
   * that stripping because it runs undexed {@code javac} output, so Jackson's implicit,
   * annotation-free records deserialization silently works in every JVM unit test and silently
   * fails on a real device — exactly the defect Task 8's live contract test against a real
   * Navidrome found and fixed once already (see {@code SubsonicResponse}'s class doc for the full
   * mechanism). This rule exists so the next DTO record added without having read that class doc
   * fails loudly here instead of silently on a user's phone.
   *
   * <p>A record is in scope for this rule if <em>either</em> of two independent conditions holds
   * — not just literal residence in {@code app.muplay.network.dto}, the one package this
   * repository happens to use for Jackson DTOs today. Plans 2-7 add Lidarr/Bindery/DLNA-DIDL
   * integrations that will plausibly put their own Jackson DTOs in a different package entirely;
   * a hard-coded single package would leave such a record silently unprotected, reproducing this
   * exact on-device failure one directory over.
   *
   * <ol>
   *   <li>its package contains a {@code dto} path segment anywhere under {@code app.muplay} (not
   *       only exactly {@code app.muplay.network.dto} — {@code app.muplay.integrations.lidarr.dto}
   *       or {@code app.muplay.dto.anything} match too), or
   *   <li>it already carries at least one {@code com.fasterxml.jackson.*} annotation somewhere —
   *       on the class itself, on a constructor, or on any constructor parameter — regardless of
   *       package. This catches a record that is already halfway to being a Jackson DTO (one
   *       {@code @JsonProperty} added, the rest forgotten) wherever it happens to live, which is
   *       exactly the half-annotated shape a real mistake looks like, not only a from-scratch
   *       unannotated one.
   * </ol>
   *
   * <p>{@code core.model}'s records ({@code MusicLibrary}, {@code ServerCapabilities}, {@code
   * SubsonicCredentials}, ...) match neither condition — no {@code dto} segment, no Jackson
   * annotation anywhere on them — and are correctly out of scope: they are hand-constructed
   * domain types, never deserialized by Jackson.
   *
   * <p>{@code isAnnotatedWith(String)} (the fully-qualified-name overload, not {@code
   * isAnnotatedWith(Class)}) is used throughout so this rule does not need Jackson on {@code
   * :app}'s own test classpath — ArchUnit reads the annotation directly out of the imported
   * bytecode without needing to load the annotation type.
   */
  @Test
  public void everyDtoRecordHasAnExplicitJacksonCreator() {
    List<JavaClass> dtoRecords =
        CLASSES.stream()
            .filter(JavaClass::isRecord)
            .filter(ArchitectureTest::isInJacksonDtoScope)
            .collect(Collectors.toList());

    assertWithFailureMessage(
        !dtoRecords.isEmpty(),
        "Found zero Jackson-DTO-scoped record classes under app.muplay (a `dto`-segmented"
            + " package, or a record already carrying a Jackson annotation) — this rule is"
            + " scanning the wrong thing, not verifying anything.");

    for (JavaClass dtoRecord : dtoRecords) {
      Set<JavaConstructor> constructors = dtoRecord.getConstructors();
      assertWithFailureMessage(
          constructors.size() == 1,
          dtoRecord.getFullName()
              + " has "
              + constructors.size()
              + " constructor(s) — a record is expected to have exactly one (its canonical"
              + " constructor); this rule does not know which one Jackson would use.");
      JavaConstructor canonical = constructors.iterator().next();
      assertWithFailureMessage(
          canonical.isAnnotatedWith(JSON_CREATOR),
          dtoRecord.getFullName()
              + "'s canonical constructor is missing @JsonCreator. Without it, Jackson falls"
              + " back to its automatic Java-records deserialization, which Android's D8"
              + " compiler silently breaks for minSdk < 33 (this project's minSdk is 26) — see"
              + " SubsonicResponse's class doc. Add @JsonCreator to the canonical (compact)"
              + " constructor, e.g. `@JsonCreator public "
              + dtoRecord.getSimpleName()
              + " {}`.");
      for (JavaParameter parameter : canonical.getParameters()) {
        assertWithFailureMessage(
            parameter.isAnnotatedWith(JSON_PROPERTY),
            dtoRecord.getFullName()
                + "'s canonical constructor parameter #"
                + parameter.getIndex()
                + " is missing @JsonProperty. Add @JsonProperty(\"...\") to that component in"
                + " the record header, matching the server's JSON field name.");
      }
    }
  }

  private static boolean isInJacksonDtoScope(JavaClass javaClass) {
    return packageHasDtoSegment(javaClass.getPackageName()) || alreadyCarriesJacksonAnnotation(javaClass);
  }

  private static boolean packageHasDtoSegment(String packageName) {
    // Deliberately not packageName.split("\\.") -- Error Prone's StringSplitter check (-Werror
    // in this build) flags String.split(String) as surprising (empty-string and regex-metachar
    // pitfalls); a package name has none of those characters, but the boundary checks below are
    // exactly as correct for this purpose and need no regex at all.
    return packageName.equals("dto")
        || packageName.startsWith("dto.")
        || packageName.endsWith(".dto")
        || packageName.contains(".dto.");
  }

  private static boolean alreadyCarriesJacksonAnnotation(JavaClass javaClass) {
    if (hasJacksonAnnotation(javaClass.getAnnotations())) {
      return true;
    }
    for (JavaConstructor constructor : javaClass.getConstructors()) {
      if (hasJacksonAnnotation(constructor.getAnnotations())) {
        return true;
      }
      for (JavaParameter parameter : constructor.getParameters()) {
        if (hasJacksonAnnotation(parameter.getAnnotations())) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasJacksonAnnotation(
      Iterable<? extends JavaAnnotation<?>> annotations) {
    for (JavaAnnotation<?> annotation : annotations) {
      if (annotation.getRawType().getFullName().startsWith(JACKSON_ANNOTATION_PACKAGE_PREFIX)) {
        return true;
      }
    }
    return false;
  }

  private static final String NONNULL = "javax.annotation.Nonnull";
  private static final String NULLABLE = "javax.annotation.Nullable";

  /**
   * Enforces the project's Java-specific discipline convention (spec §10: "{@code
   * @Nullable}/{@code @NonNull} coverage on public API") mechanically rather than by review:
   * every public or protected method and constructor on a public class under {@code app.muplay}
   * must carry a JSR-305 {@code @Nonnull} or {@code @Nullable} annotation on each reference-typed
   * parameter, and on its return type if that return type is itself a reference type. Primitives
   * (including {@code void} — confirmed empirically that {@code JavaClass.isPrimitive()} is
   * {@code true} for a {@code void} return) need no annotation; there is nothing nullable about
   * them.
   *
   * <p>A protected member is in scope only when its declaring class is not {@code final}. A
   * subclass author consumes a protected member of a non-final public class exactly like a public
   * one — it is real, callable API surface — but a protected member of a {@code final} class can
   * never be inherited (no subclass of a {@code final} class can exist), so it is exactly as
   * unreachable from outside the package as a private or package-private member, and is skipped
   * for the same reason those are.
   *
   * <p><b>NullAway alone does not catch an omission here.</b> {@code app.muplay} is a NullAway
   * {@code AnnotatedPackages} package (see the root {@code build.gradle}'s {@code
   * option('NullAway:AnnotatedPackages', 'app.muplay')}), and inside an annotated package an
   * <em>unannotated</em> reference-typed parameter or return is treated as implicitly {@code
   * @Nonnull} by convention — it compiles clean either way, annotation present or not. That gap is
   * exactly how {@code SubsonicErrorException}'s constructor parameter, {@code
   * SubsonicCredentials#toString()}'s return, and {@code SubsonicResponseException}'s protected
   * constructor parameter went unannotated in review after review until this rule was added
   * specifically to close it. (A later review found that the rule as first written still missed
   * the latter two: it visited only {@code public} members, so {@code
   * SubsonicResponseException}'s {@code protected} constructor was never looked at, and its
   * record-override exemption matched {@code SubsonicCredentials#toString()} by name and arity
   * alone, without checking whether that specific method was actually compiler-generated — see
   * {@link #isCompilerGeneratedRecordMember} for the fix.)
   *
   * <p>Scope is deliberately "public class + public/protected member," not merely "public
   * member": {@code SubsonicApi}'s methods are bytecode-public (every interface method is,
   * whether or not the interface itself is declared {@code public}), but the interface itself is
   * package-private, so nothing outside this package can ever call them — annotating Retrofit's
   * dynamically implemented interface would police an API surface that was never actually public.
   *
   * <p>Three narrow exemptions — see {@link #isCompilerSynthesizedExemptMember}:
   *
   * <ul>
   *   <li>An enum's {@code values()}/{@code valueOf(String)}. These are always
   *       compiler-generated with no annotation site of their own: {@code javac} refuses to
   *       compile a hand-written method of that name and arity inside an enum body at all
   *       (confirmed empirically — {@code public static E[] values() { ... }} written inside an
   *       enum fails with "method values() is already defined in enum E"), so there is no
   *       hand-written case this exemption could wrongly swallow.
   *   <li>A record's {@code equals(Object)}/{@code hashCode()}/{@code toString()}, but
   *       <em>only</em> the specific method that is actually compiler-generated, not merely
   *       every method with that name and arity — a record is free to override any of the three
   *       by hand (as {@code SubsonicCredentials#toString()} does, to keep the password out of
   *       logs), and a hand-written override must still be checked. Neither case is marked
   *       {@code ACC_SYNTHETIC} (confirmed via {@code javap -v} on this project's own compiled
   *       classes), so that flag can't be used to tell them apart; {@link
   *       #isCompilerGeneratedRecordMember} explains what bytecode signal is used instead.
   *   <li>Dagger/Hilt-generated classes — for a different reason, see the next paragraph.
   * </ul>
   *
   * <p>Dagger/Hilt's annotation processor generates real, compiled, public classes into this same
   * {@code app.muplay} package (alongside {@code MuPlayApplication} — see {@code
   * DaggerMuPlayApplication_HiltComponents_SingletonC} and friends under {@code
   * build/generated/}), which this test's {@code ClassFileImporter().importPackages("app.muplay")}
   * therefore imports and scans exactly like hand-written code. Nobody edits that code — it is
   * regenerated from scratch on every build — so a missing annotation there is not a fixable
   * defect this rule should report. Every class with a checkable public member (a reference-typed
   * parameter or return) that Dagger/Hilt currently generates for this app is marked, directly or
   * on an enclosing class, with one of three bytecode-visible annotations — see {@link
   * #isGeneratedCode} for exactly which, and why not simply {@code
   * @javax.annotation.processing.Generated} itself. This is the exact same carve-out the root
   * {@code build.gradle}'s NullAway {@code excludedPaths} setting (any path under a {@code
   * build/generated} directory) already makes for the same reason.
   */
  @Test
  public void everyPublicSignatureHasNullabilityAnnotations() {
    List<JavaClass> publicClasses =
        CLASSES.stream()
            .filter(c -> c.getModifiers().contains(JavaModifier.PUBLIC))
            .filter(c -> !isGeneratedCode(c))
            .collect(Collectors.toList());
    assertWithFailureMessage(
        !publicClasses.isEmpty(),
        "Found zero public classes under app.muplay — this rule is scanning the wrong thing,"
            + " not verifying anything.");

    for (JavaClass javaClass : publicClasses) {
      boolean isNonFinal = !javaClass.getModifiers().contains(JavaModifier.FINAL);
      for (JavaConstructor constructor : javaClass.getConstructors()) {
        if (isApiSurfaceMember(constructor, isNonFinal)) {
          assertParametersAnnotated(constructor);
        }
      }
      for (JavaMethod method : javaClass.getMethods()) {
        if (!isApiSurfaceMember(method, isNonFinal)) {
          continue;
        }
        if (isCompilerSynthesizedExemptMember(javaClass, method)) {
          continue;
        }
        assertParametersAnnotated(method);
        JavaClass returnType = method.getRawReturnType();
        if (!returnType.isPrimitive()) {
          assertWithFailureMessage(
              method.isAnnotatedWith(NONNULL) || method.isAnnotatedWith(NULLABLE),
              method.getFullName()
                  + "'s return type ("
                  + returnType.getName()
                  + ") is missing a @Nonnull/@Nullable annotation. NullAway alone will not catch"
                  + " this: an unannotated reference return inside app.muplay's NullAway"
                  + " AnnotatedPackages scope compiles clean as an implicit @Nonnull, which is"
                  + " exactly how this class of omission survives to here undetected.");
        }
      }
    }
  }

  /**
   * True if {@code member} (a constructor or method already known to belong to a public class) is
   * part of that class's externally consumable API surface: {@code public} unconditionally, or
   * {@code protected} when {@code declaringClassIsNonFinal} — see {@link
   * #everyPublicSignatureHasNullabilityAnnotations}'s Javadoc for why a {@code protected} member
   * of a {@code final} class does not qualify. Private and package-private members never qualify.
   */
  private static boolean isApiSurfaceMember(JavaMember member, boolean declaringClassIsNonFinal) {
    if (member.getModifiers().contains(JavaModifier.PUBLIC)) {
      return true;
    }
    return declaringClassIsNonFinal && member.getModifiers().contains(JavaModifier.PROTECTED);
  }

  private static void assertParametersAnnotated(JavaCodeUnit member) {
    for (JavaParameter parameter : member.getParameters()) {
      if (parameter.getRawType().isPrimitive()) {
        continue;
      }
      assertWithFailureMessage(
          parameter.isAnnotatedWith(NONNULL) || parameter.isAnnotatedWith(NULLABLE),
          member.getFullName()
              + "'s parameter #"
              + parameter.getIndex()
              + " ("
              + parameter.getRawType().getName()
              + ") is missing a @Nonnull/@Nullable annotation.");
    }
  }

  // The standard javax.annotation.processing.Generated that every one of Hilt's generated
  // top-level types in this project also carries (confirmed by reading their generated source
  // under app/build/generated/**) is deliberately *not* checked here: it is
  // @Retention(RetentionPolicy.SOURCE) per its own javadoc, so javac strips it before it ever
  // reaches a .class file — confirmed empirically with `javap -v` on this project's own compiled
  // MuPlayApplication_GeneratedInjector.class, which shows no trace of it. An ArchUnit rule reads
  // bytecode, so a SOURCE-retention annotation is invisible to it no matter what. These three are
  // the markers actually present in the .class files this project currently generates (same
  // `javap -v` check, on every class Hilt currently generates for this app) — one per generator
  // that produces a public member with a reference-typed parameter or return that this rule would
  // otherwise flag.
  private static final String DAGGER_GENERATED = "dagger.internal.DaggerGenerated";
  private static final String HILT_GENERATED_ENTRY_POINT = "dagger.hilt.internal.GeneratedEntryPoint";
  private static final String HILT_COMPONENT_TREE_DEPS =
      "dagger.hilt.internal.componenttreedeps.ComponentTreeDeps";

  /**
   * True if {@code javaClass} or any class it is nested in carries one of the markers above.
   * Walking the enclosing-class chain matters because Dagger marks only the top-level generated
   * type (e.g. {@code DaggerMuPlayApplication_HiltComponents_SingletonC}) — a nested type it
   * generates inside that one (e.g. its {@code Builder}) carries no marker of its own, only its
   * enclosing class does.
   */
  private static boolean isGeneratedCode(JavaClass javaClass) {
    for (JavaClass current = javaClass; ; ) {
      if (current.isAnnotatedWith(DAGGER_GENERATED)
          || current.isAnnotatedWith(HILT_GENERATED_ENTRY_POINT)
          || current.isAnnotatedWith(HILT_COMPONENT_TREE_DEPS)) {
        return true;
      }
      Optional<JavaClass> enclosing = current.getEnclosingClass();
      if (enclosing.isEmpty()) {
        return false;
      }
      current = enclosing.get();
    }
  }

  /**
   * See {@link #everyPublicSignatureHasNullabilityAnnotations}'s Javadoc for why these cases,
   * specifically, are exempt rather than fixed by annotating them.
   */
  private static boolean isCompilerSynthesizedExemptMember(JavaClass javaClass, JavaMethod method) {
    int paramCount = method.getParameters().size();
    if (javaClass.isRecord()) {
      boolean isObjectOverrideSignature =
          switch (method.getName()) {
            case "equals" -> paramCount == 1;
            case "hashCode", "toString" -> paramCount == 0;
            default -> false;
          };
      if (isObjectOverrideSignature) {
        return isCompilerGeneratedRecordMember(javaClass, method);
      }
    }
    if (javaClass.isEnum()) {
      return switch (method.getName()) {
        case "values" -> paramCount == 0;
        case "valueOf" -> paramCount == 1;
        default -> false;
      };
    }
    return false;
  }

  /**
   * Distinguishes a record's compiler-generated {@code equals(Object)}/{@code hashCode()}/{@code
   * toString()} from a hand-written override of the identical name and arity — {@code
   * isObjectOverrideSignature} in {@link #isCompilerSynthesizedExemptMember} only proves
   * {@code candidate} has the right name and parameter count, not that the compiler actually
   * generated this particular method, and {@code SubsonicCredentials#toString()} is exactly such
   * a hand-written case: it deliberately overrides the generated {@code toString()} so credentials
   * are never logged.
   *
   * <p>Bytecode carries no {@code ACC_SYNTHETIC} flag on either case (confirmed via {@code
   * javap -v} on this project's own compiled classes) — but it does carry line numbers, and those
   * differ in a way that is provable, not merely conventional. {@code javap -l -p} on {@code
   * SubsonicCredentials.class} shows every implicitly generated member — {@code hashCode()},
   * {@code equals(Object)}, and all three accessors {@code baseUrl()}/{@code username()}/{@code
   * password()} — sharing the exact same {@code LineNumberTable} entry, line 6: the line of the
   * record's own header, {@code "public record SubsonicCredentials("}. That is what javac does
   * with a member it manufactures out of thin air with no body of its own to point at: it
   * attributes the generated code to the declaration line. The hand-written {@code toString()} on
   * that same class instead starts at line 30, the line of its own {@code return} statement — the
   * same behavior any ordinary hand-written method has. {@code javap -l -p} on {@code
   * MusicFolderList.class} (a record that overrides none of the three) confirms the same pattern
   * the other way: {@code toString()}, {@code hashCode()}, {@code equals(Object)}, and its
   * accessor all sit on line 14, the record's header line, because all four really are
   * compiler-generated there.
   *
   * <p>So: {@code candidate} is compiler-generated if and only if some other constructor or
   * method declared directly on the same class reports the identical source line — in ordinary,
   * one-member-per-declaration formatting (which this project uses throughout, and which a
   * formatter would refuse to un-do), two independently hand-written members never legitimately
   * share one line, so a shared line number is itself the proof. The search deliberately excludes
   * other {@code equals}/{@code hashCode}/{@code toString} methods as corroborators — two
   * hand-written overrides among the three should never be able to vouch for each other — and
   * relies on component accessors (present for any record with at least one component, which
   * covers every record in this codebase) plus the canonical constructor (covering the
   * pathological zero-component case) instead.
   */
  private static boolean isCompilerGeneratedRecordMember(JavaClass javaClass, JavaMethod candidate) {
    int candidateLine = candidate.getSourceCodeLocation().getLineNumber();
    if (candidateLine <= 0) {
      // No line-number debug info to compare — cannot prove compiler-generation, so don't exempt.
      // (This project always compiles with debug info; this is a safety net, not an expected path.)
      return false;
    }
    for (JavaConstructor constructor : javaClass.getConstructors()) {
      if (constructor.getSourceCodeLocation().getLineNumber() == candidateLine) {
        return true;
      }
    }
    for (JavaMethod sibling : javaClass.getMethods()) {
      if (isRecordObjectOverrideName(sibling.getName())) {
        continue; // Never let equals/hashCode/toString corroborate one another.
      }
      if (sibling.getSourceCodeLocation().getLineNumber() == candidateLine) {
        return true;
      }
    }
    return false;
  }

  private static boolean isRecordObjectOverrideName(String methodName) {
    return methodName.equals("equals") || methodName.equals("hashCode") || methodName.equals("toString");
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
