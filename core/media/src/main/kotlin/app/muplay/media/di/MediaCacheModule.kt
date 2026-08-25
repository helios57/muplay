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
import javax.inject.Singleton

/**
 * The media cache binding, kept out of [MediaModule] on purpose.
 *
 * [MediaModule] names no Android and no Media3 type, which is what lets `ci/mutation-probes.sh`
 * and a plain `MediaModuleTest` hold the streaming client's timeout decision to a 1.0000 LINE
 * floor on the **fast** tier. A `Cache` needs a real `Context` and real SQLite, so no JVM test can
 * cover it; folding this provider into that object was tried and measured, and took it to
 * 4/5 = 0.80 lines on JVM-only data — which would have meant either moving the timeout gate onto
 * the emulator or blunting it. One extra file is the cheaper of the three.
 *
 * `androidx.annotation.OptIn`, not `kotlin.OptIn`: Media3's `@UnstableApi` is a Java annotation
 * marked with `androidx.annotation.RequiresOptIn`, which the Kotlin compiler does not enforce at
 * all — Android Lint's `UnsafeOptInUsageError` does, and `check` runs lint.
 */
@Module
@InstallIn(SingletonComponent::class)
object MediaCacheModule {

  /**
   * One `SimpleCache` per process. `@Singleton` is load-bearing rather than a performance choice:
   * a second live instance on the same directory throws `IllegalStateException("Another
   * SimpleCache instance uses the folder")`. Exactly the situation `DataModule`'s note about
   * DataStore refusing a second instance for one file describes, and the same resolution.
   *
   * The one-argument overload, so the directory is the production one — `MediaCache`'s `directory`
   * parameter exists for instrumented tests that need their own, and nothing in the graph should
   * be passing it.
   */
  @Provides
  @Singleton
  @OptIn(UnstableApi::class)
  fun provideMediaCache(@ApplicationContext context: Context): Cache = MediaCache.create(context)
}
