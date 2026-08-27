import org.gradle.api.provider.Property

/**
 * Per-module application policy that cannot live in a convention plugin.
 *
 * One flag today. `:app` is an Android Auto media app and the roadmap's `:wear` module is not, and
 * neither of those is a decision a shared plugin can make — but both need the *mechanism* (the
 * manifest requirements, the descriptor check) to come from one place. Same split as the root build
 * script's `coverageFloors`: policy per module, mechanism once.
 *
 * Not an `android { }` block and not a `kotlin { }` block, so `ConventionTest`'s
 * `no module configures android or kotlin blocks directly` rule is untouched — it bans
 * `android`/`androidComponents`/`kotlin`/`compileOptions`/`kotlinOptions`/`compilerOptions` and
 * `extensions.configure`, none of which this is. That rule gains a companion in the same task
 * (`the app module opts in to Android Auto and ships the descriptor it promises`) which asserts
 * that `:app` really does opt in, so deleting the opt-in fails the fast tier rather than silently
 * removing a gate.
 */
abstract class MuPlayApplicationExtension {

  /**
   * Whether this application ships to Android Auto.
   *
   * `true` adds [AUTOMOTIVE_DECLARATIONS] to `verify<Variant>Manifest`'s required list and turns on
   * `verifyAutomotiveDescriptor`. `false` (the convention) leaves both alone — a watch app
   * declaring itself a car app would be a wrong claim in a shipped manifest, and a module with no
   * `res/xml/automotive_app_desc.xml` would fail a descriptor check it was never meant to take.
   */
  abstract val androidAuto: Property<Boolean>
}
