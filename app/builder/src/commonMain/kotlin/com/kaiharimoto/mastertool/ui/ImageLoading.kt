package com.kaiharimoto.mastertool.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toPath
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory

/**
 * Card art is fetched over the same Ktor stack as the card data.
 *
 * A generous memory cache matters here: a deck pane can show 90 thumbnails at
 * once and scrolling back and forth should never re-decode them. The disk
 * cache matters more: art must survive a cold start with no network, because
 * the venue with no signal is where this app earns its keep.
 *
 * Public for `:studio`, which needs this exact loader rather than Coil's
 * default: the crossfade is what decides whether a shot taken twelve frames
 * after a deal has card faces in it or empty rectangles.
 */
fun configureImageLoader(cacheDir: String?) {
    SingletonImageLoader.setSafe { context: PlatformContext ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(HttpClientFactory.create())) }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, percent = 0.25)
                    .build()
            }
            .apply {
                if (cacheDir != null) {
                    diskCache {
                        DiskCache.Builder()
                            .directory(cacheDir.toPath())
                            .maxSizeBytes(512L * 1024 * 1024)
                            .build()
                    }
                }
            }
            .crossfade(true)
            .build()
    }
}
