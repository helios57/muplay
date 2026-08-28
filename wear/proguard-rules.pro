# R8 rules for the watch APK. `configureReleaseBuild` (build-logic) names this file for every
# application module, so it has to exist here as well as in `:app`.
#
# Empty of rules by design, and that is a claim this repository checks rather than asserts:
# `verifyReleaseArtifact` reads the `.aab` itself and proves it is minified, carries no debug-only
# type and permits no cleartext. Everything `:app`'s own rules keep -- Room's generated `_Impl`,
# Hilt's components, kotlinx-serialization's `$serializer`, Retrofit's `Proxy`, Media3's
# `Bundle`-based session IPC -- reaches this module through the same libraries' own consumer rules,
# which is where a library's keep rules belong. Add a rule here only with the R8 failure that
# needed it written beside it.
