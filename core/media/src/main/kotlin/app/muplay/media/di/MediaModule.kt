package app.muplay.media.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import app.muplay.media.MediaCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * The media layer's object graph.
 *
 * The `OkHttpClient` here is **not** the one `:core:network` uses, and that is deliberate rather
 * than an oversight. `:core:network` issues short JSON requests where a call timeout is a safety
 * net; this one reads a media body that is legitimately open for the length of a track, where a
 * call timeout is a guaranteed mid-song failure. Two clients with different, correct policies beats
 * one client with the union of both.
 *
 * The other `@Singleton` this layer contributes, `NavidromeLoadErrorHandlingPolicy`, is bound by
 * its own `@Inject` constructor and scoped on the class rather than provided from here — see that
 * class's own note. Only bindings that genuinely need a builder live in this module.
 */
@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

  @Provides
  @Singleton
  fun provideMediaCallFactory(): Call.Factory =
    OkHttpClient.Builder()
      .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      // How long a *read* may stall, not how long the whole body may take.
      .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      // No `callTimeout`: the default is already "none", and setting one would cap the total
      // duration of a streaming read, i.e. cut off any track longer than the cap. Stated as a
      // comment because "we did not set it" and "we thought about it and must not set it" are
      // different facts, and only one of them survives a refactor.
      .build()

  /**
   * One `SimpleCache` per process. `@Singleton` is load-bearing rather than a performance choice:
   * a second live instance on the same directory throws `IllegalStateException("Another
   * SimpleCache instance uses the folder")`. Exactly the situation `DataModule`'s note about
   * DataStore refusing a second instance for one file describes, and the same resolution.
   *
   * The one-argument overload, so the directory is the production one -- `MediaCache`'s
   * `directory` parameter exists for instrumented tests that need their own, and nothing in the
   * graph should be passing it.
   */
  @Provides
  @Singleton
  // `androidx.annotation.OptIn`, not `kotlin.OptIn`: Media3's `@UnstableApi` is a Java annotation
  // marked with `androidx.annotation.RequiresOptIn`, which the Kotlin compiler does not enforce --
  // Android Lint's `UnsafeOptInUsageError` does, and `check` runs lint. On the function rather
  // than on the object, so the timeout providers above stay free of it.
  @OptIn(UnstableApi::class)
  fun provideMediaCache(@ApplicationContext context: Context): Cache = MediaCache.create(context)

  private const val CONNECT_TIMEOUT_SECONDS = 15L
  private const val READ_TIMEOUT_SECONDS = 30L
}
