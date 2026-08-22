plugins {
  id("muplay.jvm.library")
}

dependencies {
  // Backs OpenApiFixtureValidator: validates recorded Subsonic fixtures against the vendored
  // OpenSubsonic spec in src/main/resources. `implementation`, not `api` — consumers call
  // OpenApiFixtureValidator.assertValid(String, String) only, never reference
  // com.atlassian.oai.validator.* directly, so there is nothing to leak onto their classpath.
  implementation(libs.openapi.validator)
}
