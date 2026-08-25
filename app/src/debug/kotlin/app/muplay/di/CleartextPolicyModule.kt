package app.muplay.di

import app.muplay.integrations.CleartextPolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The **debug** variant's cleartext policy.
 *
 * Permitted here and only here, because `app/src/debug/AndroidManifest.xml` is the only manifest
 * in this repository that carries `usesCleartextTraffic`, and because the Tier 1 live-container
 * tests and the Tier 2 emulator journey both talk to services over plain HTTP on localhost.
 *
 * There is a file with this exact fully-qualified name in `app/src/release/kotlin/` providing
 * `Forbidden`. Variant source sets are mutually exclusive, so exactly one of the two is compiled
 * into any given build, and no code in a release build names `Allowed`. `ConventionTest`'s
 * `the cleartext policy and the cleartext manifest cannot disagree` is what keeps this pair
 * honest.
 */
@Module
@InstallIn(SingletonComponent::class)
object CleartextPolicyModule {

  @Provides
  @Singleton
  fun provideCleartextPolicy(): CleartextPolicy = CleartextPolicy.Allowed
}
