package app.muplay

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp

/**
 * Builds Coil's image loader explicitly rather than relying on service-loader discovery of the
 * network fetcher: a missing fetcher fails as "the image just never appears", which is the
 * hardest possible failure to diagnose from a screenshot.
 */
@HiltAndroidApp
class MuPlayApplication : Application(), SingletonImageLoader.Factory {

  override fun newImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
      .components { add(OkHttpNetworkFetcherFactory()) }
      .build()
}
