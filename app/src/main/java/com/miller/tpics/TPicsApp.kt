package com.miller.tpics

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.miller.tpics.data.Auth
import com.miller.tpics.data.AutoBackup
import com.miller.tpics.data.Suggestions
import com.miller.tpics.data.Http
import com.miller.tpics.data.Prefs
import com.miller.tpics.data.Store
import com.miller.tpics.data.UploadQueue
import okhttp3.Interceptor

class TPicsApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Auth.init()
        Store.init(this)
        UploadQueue.init(this)
        AutoBackup.init(this)
        Suggestions.init(this)
    }

    /**
     * Cargador de imágenes: agrega la sesión a cada pedido y guarda en disco
     * todo lo que baja, así cada miniatura se descarga una sola vez.
     */
    override fun newImageLoader(): ImageLoader {
        val withSession = Interceptor { chain ->
            val req = chain.request()
            val api = Store.apiUrl.value
            if (api.isNotEmpty() && req.url.toString().startsWith(api)) {
                val token = runCatching { Auth.tokenBlocking() }.getOrNull()
                if (token != null) {
                    return@Interceptor chain.proceed(
                        req.newBuilder().header("Authorization", "Bearer $token").build()
                    )
                }
            }
            chain.proceed(req)
        }
        return ImageLoader.Builder(this)
            .okHttpClient { Http.client.newBuilder().addInterceptor(withSession).build() }
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("imagenes"))
                    .maxSizeBytes(768L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }
}
