package com.miller.tpics.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/* ─────────────────── Fotos y videos del teléfono ─────────────────── */

data class LocalItem(
    val uri: Uri,
    val id: Long,
    val name: String,
    val size: Long,
    val date: Long,
    val bucketId: String,
    val bucketName: String,
    val isVideo: Boolean,
    val duration: Long,
    val mime: String,
    val w: Int,
    val h: Int,
    val added: Long = 0,
) {
    val sig: String get() = "$name|$size"
    val key: String get() = (if (isVideo) "v" else "i") + id
}

data class LocalFolder(val id: String, val name: String, val items: List<LocalItem>)

object Device {

    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= 29 -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * Borra del teléfono archivos ya respaldados. En Android 11+ devuelve un pedido
     * que el sistema muestra al usuario para confirmar; en versiones anteriores borra directo.
     */
    fun deleteRequest(ctx: Context, uris: List<Uri>): android.content.IntentSender? {
        if (uris.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= 30) {
            return MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender
        }
        uris.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        return null
    }

    fun hasPermission(ctx: Context): Boolean {
        fun ok(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 34 && ok(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) return true
        return permissions().any { it != Manifest.permission.ACCESS_MEDIA_LOCATION && ok(it) }
    }

    suspend fun load(ctx: Context): List<LocalItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<LocalItem>()
        runCatching { query(ctx, false, out) }
        runCatching { query(ctx, true, out) }
        out.sortByDescending { it.date }
        out
    }

    private fun query(ctx: Context, video: Boolean, out: MutableList<LocalItem>) {
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val cols = arrayListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            "datetaken",
            "bucket_id",
            "bucket_display_name",
            MediaStore.MediaColumns.DATE_ADDED,
        )
        if (video) cols.add("duration")

        ctx.contentResolver.query(collection, cols.toTypedArray(), null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val iMod = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val iMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val iW = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val iH = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val iTaken = c.getColumnIndex("datetaken")
            val iBid = c.getColumnIndex("bucket_id")
            val iBname = c.getColumnIndex("bucket_display_name")
            val iAdded = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val iDur = if (video) c.getColumnIndex("duration") else -1

            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val taken = if (iTaken >= 0) c.getLong(iTaken) else 0L
                val mod = if (iMod >= 0) c.getLong(iMod) * 1000 else 0L
                out.add(
                    LocalItem(
                        uri = ContentUris.withAppendedId(collection, id),
                        id = id,
                        name = (if (iName >= 0) c.getString(iName) else null) ?: "archivo_$id",
                        size = if (iSize >= 0) c.getLong(iSize) else 0L,
                        date = if (taken > 0) taken else mod,
                        bucketId = (if (iBid >= 0) c.getString(iBid) else null) ?: "0",
                        bucketName = (if (iBname >= 0) c.getString(iBname) else null) ?: "Almacenamiento",
                        isVideo = video,
                        duration = if (iDur >= 0) c.getLong(iDur) else 0L,
                        mime = (if (iMime >= 0) c.getString(iMime) else null)
                            ?: if (video) "video/mp4" else "image/jpeg",
                        w = if (iW >= 0) c.getInt(iW) else 0,
                        h = if (iH >= 0) c.getInt(iH) else 0,
                        added = if (iAdded >= 0) c.getLong(iAdded) else 0L,
                    )
                )
            }
        }
    }

    fun folders(items: List<LocalItem>): List<LocalFolder> =
        items.groupBy { it.bucketId }
            .map { (id, list) -> LocalFolder(id, list.first().bucketName, list) }
            .sortedWith(
                compareByDescending<LocalFolder> { it.name.equals("Camera", true) || it.name.equals("Cámara", true) }
                    .thenByDescending { it.items.firstOrNull()?.date ?: 0 }
            )
}

/* ───────────────────────────── Miniaturas ───────────────────────────── */

object Thumbs {
    private const val EDGE = 480

    fun fromUri(ctx: Context, uri: Uri, isVideo: Boolean): ByteArray? {
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.loadThumbnail(uri, Size(EDGE, EDGE), null)
            } else if (isVideo) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    r.frameAtTime?.let { scale(it) }
                } finally {
                    r.release()
                }
            } else {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                bytes?.let { decode(it) }
            }
        }.getOrNull() ?: return null
        return encode(bmp)
    }

    /** Para fotos antiguas: crea la miniatura a partir del archivo completo. */
    fun fromBytes(bytes: ByteArray): ByteArray? {
        val bmp = runCatching { decode(bytes) }.getOrNull() ?: return null
        return encode(bmp)
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val rotation = runCatching {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val scaled = scale(raw)
        if (rotation == 0f) return scaled
        val m = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, m, true)
    }

    private fun scale(src: Bitmap): Bitmap {
        val short = minOf(src.width, src.height)
        if (short <= EDGE) return src
        val f = EDGE.toFloat() / short
        return Bitmap.createScaledBitmap(src, (src.width * f).toInt().coerceAtLeast(1), (src.height * f).toInt().coerceAtLeast(1), true)
    }

    private fun encode(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }
}

/* ─────────────────── Guardar en el teléfono / compartir ─────────────────── */

object Files {

    private fun fileName(m: Media): String {
        if (m.name.isNotEmpty()) return m.name
        val ext = if (m.isVideo) "mp4" else "jpg"
        return "TPics_${m.id}.$ext"
    }

    private fun mimeOf(m: Media): String =
        m.mime.ifEmpty { if (m.isVideo) "video/mp4" else "image/jpeg" }

    /** Descarga el archivo original a la galería del teléfono (carpeta T-Pics). */
    suspend fun saveToDevice(ctx: Context, m: Media) = withContext(Dispatchers.IO) {
        val req = okhttp3.Request.Builder().url(Store.fullUrl(m))
            .header("Authorization", "Bearer ${Auth.token()}").build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code, "")
            val input = r.body!!.byteStream()
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(m))
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(m))
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        (if (m.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/T-Pics",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = if (m.isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = ctx.contentResolver.insert(collection, values)
                    ?: throw IllegalStateException("No se pudo crear el archivo")
                ctx.contentResolver.openOutputStream(uri)!!.use { input.copyTo(it) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "T-Pics"
                )
                dir.mkdirs()
                File(dir, fileName(m)).outputStream().use { input.copyTo(it) }
            }
        }
    }

    /** Descarga a una carpeta temporal y abre el menú de compartir de Android. */
    suspend fun share(ctx: Context, list: List<Media>) {
        val uris = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            list.map { m ->
                val f = File(dir, fileName(m))
                val req = okhttp3.Request.Builder().url(Store.fullUrl(m))
                    .header("Authorization", "Bearer ${Auth.token()}").build()
                Http.client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw HttpError(r.code, "")
                    f.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                }
                FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            }
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(list[0])
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
