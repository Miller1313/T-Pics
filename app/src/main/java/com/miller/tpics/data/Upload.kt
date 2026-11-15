package com.miller.tpics.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.miller.tpics.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class UploadItem(
    val id: String,
    val uri: String,
    val name: String,
    val size: Long,
    val isVideo: Boolean,
    val mime: String,
    val date: Long,
    val w: Int,
    val h: Int,
    val dur: Long,
    val tags: List<String>,
    val status: String = QUEUED,
    val progress: Float = 0f,
    val error: String = "",
) {
    companion object {
        const val QUEUED = "queued"
        const val RUNNING = "running"
        const val DONE = "done"
        const val DUPLICATE = "dup"
        const val ERROR = "error"
    }
}

/** Cola de subida. Sobrevive a cerrar la app y sigue en segundo plano. */
object UploadQueue {
    const val PART_SIZE = 18 * 1024 * 1024
    const val MAX_FILE = 400L * 1024 * 1024
    const val CHANNEL = "uploads"

    val items = MutableStateFlow<List<UploadItem>>(emptyList())
    private lateinit var appContext: Context
    private var owner = ""

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Subidas", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun file() = File(appContext.filesDir, "queue_$owner.json")

    fun open(uid: String) {
        owner = uid
        val list = ArrayList<UploadItem>()
        runCatching {
            val f = file()
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val tags = ArrayList<String>()
                    o.optJSONArray("tags")?.let { t -> for (j in 0 until t.length()) tags.add(t.getString(j)) }
                    var status = o.optString("status", UploadItem.QUEUED)
                    if (status == UploadItem.RUNNING) status = UploadItem.QUEUED
                    list.add(
                        UploadItem(
                            id = o.getString("id"), uri = o.getString("uri"), name = o.optString("name"),
                            size = o.optLong("size"), isVideo = o.optBoolean("video"), mime = o.optString("mime"),
                            date = o.optLong("date"), w = o.optInt("w"), h = o.optInt("h"), dur = o.optLong("dur"),
                            tags = tags, status = status, error = o.optString("error"),
                        )
                    )
                }
            }
        }
        items.value = list
        if (list.any { it.status == UploadItem.QUEUED }) start()
    }

    fun close() {
        WorkManager.getInstance(appContext).cancelUniqueWork("uploads")
        owner = ""
        items.value = emptyList()
    }

    @Synchronized
    private fun persist() {
        if (owner.isEmpty()) return
        runCatching {
            val a = JSONArray()
            items.value.forEach { i ->
                a.put(
                    JSONObject().put("id", i.id).put("uri", i.uri).put("name", i.name).put("size", i.size)
                        .put("video", i.isVideo).put("mime", i.mime).put("date", i.date).put("w", i.w)
                        .put("h", i.h).put("dur", i.dur).put("tags", JSONArray(i.tags))
                        .put("status", i.status).put("error", i.error)
                )
            }
            file().writeText(a.toString())
        }
    }

    @Synchronized
    fun set(id: String, save: Boolean = true, change: (UploadItem) -> UploadItem) {
        items.value = items.value.map { if (it.id == id) change(it) else it }
        if (save) persist()
    }

    fun enqueue(list: List<LocalItem>, tags: List<String>) {
        val waiting = items.value.filter { it.status == UploadItem.QUEUED || it.status == UploadItem.RUNNING }
            .map { it.uri }.toHashSet()
        val fresh = list.filter { it.uri.toString() !in waiting }.map {
            UploadItem(
                id = newId(), uri = it.uri.toString(), name = it.name, size = it.size, isVideo = it.isVideo,
                mime = it.mime, date = it.date, w = it.w, h = it.h, dur = it.duration, tags = tags,
            )
        }
        synchronized(this) {
            items.value = items.value + fresh
            persist()
        }
        start()
    }

    fun retryFailed() {
        synchronized(this) {
            items.value = items.value.map {
                if (it.status == UploadItem.ERROR) it.copy(status = UploadItem.QUEUED, error = "", progress = 0f) else it
            }
            persist()
        }
        start()
    }

    fun clearFinished() {
        synchronized(this) {
            items.value = items.value.filter {
                it.status == UploadItem.QUEUED || it.status == UploadItem.RUNNING
            }
            persist()
        }
    }

    fun next(): UploadItem? = items.value.firstOrNull { it.status == UploadItem.QUEUED }

    val pending: Int get() = items.value.count { it.status == UploadItem.QUEUED || it.status == UploadItem.RUNNING }

    fun start() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (Prefs.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val req = OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(appContext).enqueueUniqueWork("uploads", ExistingWorkPolicy.KEEP, req)
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private fun notification(text: String, done: Int, total: Int): Notification =
        NotificationCompat.Builder(applicationContext, UploadQueue.CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("T-Pics · respaldando")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .build()

    private fun info(text: String, done: Int, total: Int): ForegroundInfo {
        val n = notification(text, done, total)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(41, n)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info("Preparando…", 0, 0)

    override suspend fun doWork(): Result {
        runCatching { setForeground(info("Preparando…", 0, 0)) }
        var done = 0
        while (true) {
            val item = UploadQueue.next() ?: break
            val total = done + UploadQueue.pending
            runCatching { setForeground(info(item.name, done, total)) }
            UploadQueue.set(item.id) { it.copy(status = UploadItem.RUNNING, progress = 0f, error = "") }
            try {
                val duplicate = uploadOne(item)
                UploadQueue.set(item.id) {
                    it.copy(status = if (duplicate) UploadItem.DUPLICATE else UploadItem.DONE, progress = 1f)
                }
            } catch (e: Exception) {
                val offline = e is java.io.IOException && e !is java.io.FileNotFoundException
                if (offline) {
                    // Sin red: se deja en cola y WorkManager reintenta cuando vuelva la conexión.
                    UploadQueue.set(item.id) { it.copy(status = UploadItem.QUEUED, progress = 0f) }
                    return Result.retry()
                }
                UploadQueue.set(item.id) { it.copy(status = UploadItem.ERROR, error = e.friendly()) }
            }
            done++
        }
        return Result.success()
    }

    /** Sube un trozo, respetando las pausas que pide Telegram. */
    private suspend fun sendPart(bytes: ByteArray, length: Int, name: String): String {
        var attempt = 0
        while (true) {
            try {
                return Api.upload(bytes, length, name)
            } catch (e: HttpError) {
                attempt++
                if (e.code == 429 && attempt <= 8) {
                    val wait = runCatching { JSONObject(e.body).optLong("retryAfter", 5) }.getOrDefault(5L)
                    delay((wait + 1) * 1000)
                } else if (e.code >= 500 && attempt <= 3) {
                    delay(2000L * attempt)
                } else {
                    throw e
                }
            }
        }
    }

    private fun readFull(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** @return true si ya estaba respaldado (no se subió de nuevo). */
    private suspend fun uploadOne(item: UploadItem): Boolean = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val uri = Uri.parse(item.uri)
        val resolver = ctx.contentResolver

        // 1) Huella del contenido, para no subir dos veces el mismo archivo.
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        (resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException("El archivo ya no existe")).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                digest.update(buf, 0, r)
                total += r
            }
        }
        if (total == 0L) throw IllegalStateException("Archivo vacío")
        if (total > UploadQueue.MAX_FILE) throw IllegalStateException("Supera el máximo de 400 MB")
        val hash = digest.digest().joinToString("") { "%02x".format(it) }

        Store.backedHashes[hash]?.let { existing ->
            if (item.tags.isNotEmpty()) Store.addTags(listOf(existing), item.tags)
            return@withContext true
        }

        // 2) Miniatura
        val thumbRef = Thumbs.fromUri(ctx, uri, item.isVideo)?.let { sendPart(it, it.size, "mini_${item.name}.jpg") } ?: ""
        UploadQueue.set(item.id, save = false) { it.copy(progress = 0.05f) }

        // 3) Archivo original, en trozos si es grande
        val refs = ArrayList<String>()
        resolver.openInputStream(uri)!!.use { input ->
            val buf = ByteArray(minOf(total, UploadQueue.PART_SIZE.toLong()).toInt())
            var sent = 0L
            var index = 0
            while (sent < total) {
                val n = readFull(input, buf)
                if (n <= 0) break
                val partName = if (total <= UploadQueue.PART_SIZE) item.name else "${item.name}.part${index + 1}"
                refs.add(sendPart(buf, n, partName))
                sent += n
                index++
                val p = 0.05f + 0.95f * (sent.toFloat() / total)
                UploadQueue.set(item.id, save = false) { it.copy(progress = p) }
            }
        }
        if (refs.isEmpty()) throw IllegalStateException("No se pudo leer el archivo")

        // 4) Registro en la nube
        val media = Media(
            id = newId(),
            ref = if (refs.size == 1) refs[0] else "",
            parts = if (refs.size > 1) refs else emptyList(),
            partSize = if (refs.size > 1) UploadQueue.PART_SIZE.toLong() else 0,
            thumb = thumbRef,
            kind = if (item.isVideo) "v" else "i",
            name = item.name,
            mime = item.mime,
            size = total,
            date = if (item.date > 0) item.date else System.currentTimeMillis(),
            w = item.w,
            h = item.h,
            dur = item.dur,
            tags = item.tags,
            hash = hash,
            updated = System.currentTimeMillis(),
        )
        Store.saveNew(media)
        false
    }
}

/* ─────────────── Miniaturas para fotos antiguas (importadas) ─────────────── */

object ThumbBackfill {
    @Volatile private var running = false

    /** Crea miniaturas para fotos que no tienen, poco a poco, mientras la app está abierta. */
    suspend fun run(limit: Int = 40) {
        if (running || Store.apiUrl.value.isEmpty() || !Store.canUpload) return
        running = true
        try {
            val todo = Store.media.value
                .filter { it.thumb.isEmpty() && !it.isVideo && it.ref.isNotEmpty() && it.trashedAt == 0L }
                .take(limit)
            for (m in todo) {
                try {
                    val bytes = Api.bytes(Store.fileUrl(m.ref))
                    val thumb = Thumbs.fromBytes(bytes) ?: continue
                    val ref = Api.upload(thumb, thumb.size, "mini_${m.id}.jpg")
                    Store.setThumb(m.id, ref)
                    delay(1200)
                } catch (e: HttpError) {
                    if (e.code == 429 || e.code == 402) break
                } catch (_: Exception) {
                }
            }
        } finally {
            running = false
        }
    }
}
