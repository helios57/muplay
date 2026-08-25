package app.muplay.di

import app.muplay.integrations.CleartextPolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The **release** variant's cleartext policy: unencrypted HTTP is refused at configuration time.
 *
 * The consequence is stated plainly in the plan and in spec section 8: a user whose Lidarr or
 * Bindery is plain HTTP on a LAN cannot configure it in a release build. That is the same rule the
 * Navidrome connection already lives under — this build has never been able to reach a cleartext
 * Navidrome either — and it is the conservative direction for a value stored beside a password.
 * The alternative every comparable app ships, `android:usesCleartextTraffic="true"`, opts the
 * whole application out of Android's network security defaults for *every* host it will ever
 * contact.
 */
@Module
@InstallIn(SingletonComponent::class)
object CleartextPolicyModule {

  @Provides
  @Singleton
  fun provideCleartextPolicy(): CleartextPolicy = CleartextPolicy.Forbidden
}
