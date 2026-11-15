package com.miller.tpics.data

import android.content.Context
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.miller.tpics.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Carga la sesión y lo ya respaldado cuando Android despierta la app en segundo plano. */
suspend fun ensureOpen(): Boolean {
    val s = Auth.session.value ?: return false
    Store.open(s.uid)
    UploadQueue.open(s.uid)
    AutoBackup.open(s.uid)
    Suggestions.open(s.uid)
    return true
}

/* ─────────────────── Respaldo automático por carpeta ─────────────────── */

data class AutoFolder(
    val bucketId: String,
    val name: String,
    val tagIds: List<String>,   // etiquetas fijas para todo lo que entre a la carpeta
    val suggest: Boolean,       // además, proponer etiquetas por contenido y lugar
    val since: Long = 0,        // hasta qué archivo ya se revisó (fecha de alta, en segundos)
)

object AutoBackup {
    val folders = MutableStateFlow<List<AutoFolder>>(emptyList())
    private lateinit var appContext: Context
    private var owner = ""

    fun init(ctx: Context) { appContext = ctx.applicationContext }

    private fun file() = File(appContext.filesDir, "auto_$owner.json")

    fun open(uid: String) {
        if (owner == uid) return
        owner = uid
        val list = ArrayList<AutoFolder>()
        runCatching {
            val f = file()
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val tags = ArrayList<String>()
                    o.optJSONArray("tags")?.let { t -> for (j in 0 until t.length()) tags.add(t.getString(j)) }
                    list.add(AutoFolder(o.getString("id"), o.optString("name"), tags, o.optBoolean("suggest"), o.optLong("since")))
                }
            }
        }
        folders.value = list
        schedule()
    }

    fun close() {
        owner = ""
        folders.value = emptyList()
        val wm = WorkManager.getInstance(appContext)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(TRIGGER)
    }

    @Synchronized
    private fun save(list: List<AutoFolder>) {
        folders.value = list
        if (owner.isEmpty()) return
        runCatching {
            val a = JSONArray()
            list.forEach {
                a.put(
                    JSONObject().put("id", it.bucketId).put("name", it.name).put("tags", JSONArray(it.tagIds))
                        .put("suggest", it.suggest).put("since", it.since)
                )
            }
            file().writeText(a.toString())
        }
    }

    fun set(folder: AutoFolder) {
        val old = folders.value.firstOrNull { it.bucketId == folder.bucketId }
        save(folders.value.filter { it.bucketId != folder.bucketId } + folder.copy(since = old?.since ?: 0))
        schedule()
    }

    fun remove(bucketId: String) {
        save(folders.value.filter { it.bucketId != bucketId })
        schedule()
    }

    /**
     * Busca archivos nuevos en las carpetas activadas y los manda a la cola de subida.
     * La primera vez toma todo lo que aún no esté respaldado.
     * @return cuántos archivos se pusieron en cola.
     */
    suspend fun scan(ctx: Context): Int {
        val list = folders.value
        if (list.isEmpty() || !Device.hasPermission(ctx) || !Store.canUpload) return 0
        val items = Device.load(ctx)
        val backed = Store.backedSigs
        var total = 0
        val updated = list.map { folder ->
            val inFolder = items.filter { it.bucketId == folder.bucketId }
            val fresh = inFolder.filter { it.added > folder.since && it.sig !in backed }
            if (fresh.isNotEmpty()) {
                UploadQueue.enqueue(fresh, folder.tagIds, auto = true, folder = folder.name, suggest = folder.suggest)
                total += fresh.size
            }
            folder.copy(since = maxOf(folder.since, inFolder.maxOfOrNull { it.added } ?: 0))
        }
        if (updated != folders.value) save(updated)
        return total
    }

    private const val PERIODIC = "auto-periodic"
    private const val TRIGGER = "auto-trigger"

    /** Programa la revisión: al detectar fotos nuevas en el teléfono y, por si acaso, cada hora. */
    fun schedule(rearm: Boolean = false) {
        val wm = WorkManager.getInstance(appContext)
        if (folders.value.isEmpty()) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(TRIGGER)
            return
        }
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoScanWorker>(1, TimeUnit.HOURS).build(),
        )
        val onNewMedia = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(8, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(60, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(
            TRIGGER,
            if (rearm) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoScanWorker>()
                .setConstraints(onNewMedia)
                .setInputData(workDataOf("trigger" to true))
                .build(),
        )
    }
}

class AutoScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val trigger = inputData.getBoolean("trigger", false)
        runCatching {
            if (ensureOpen()) AutoBackup.scan(applicationContext)
        }
        // El aviso de "fotos nuevas" sirve una sola vez: se vuelve a armar para la próxima.
        if (trigger) runCatching { AutoBackup.schedule(rearm = true) }
        return Result.success()
    }
}

/* ─────────────────── Etiquetas sugeridas (pendientes de aprobar) ─────────────────── */

data class Suggestion(val mediaId: String, val folder: String, val labels: List<String>) {
    /** Grupo en el que aparece en la revisión. */
    val group: String get() = labels.firstOrNull() ?: NONE

    companion object {
        const val NONE = "Sin sugerencia"
    }
}

object Suggestions {
    val items = MutableStateFlow<Map<String, Suggestion>>(emptyMap())
    private lateinit var appContext: Context
    private var owner = ""

    fun init(ctx: Context) { appContext = ctx.applicationContext }

    private fun file() = File(appContext.filesDir, "sugerencias_$owner.json")

    fun open(uid: String) {
        if (owner == uid) return
        owner = uid
        val map = LinkedHashMap<String, Suggestion>()
        runCatching {
            val f = file()
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val labels = ArrayList<String>()
                    o.optJSONArray("l")?.let { l -> for (j in 0 until l.length()) labels.add(l.getString(j)) }
                    val s = Suggestion(o.getString("id"), o.optString("f"), labels)
                    map[s.mediaId] = s
                }
            }
        }
        items.value = map
    }

    fun close() {
        owner = ""
        items.value = emptyMap()
    }

    @Synchronized
    private fun save(map: Map<String, Suggestion>) {
        items.value = map
        if (owner.isEmpty()) return
        runCatching {
            val a = JSONArray()
            map.values.forEach { a.put(JSONObject().put("id", it.mediaId).put("f", it.folder).put("l", JSONArray(it.labels))) }
            file().writeText(a.toString())
        }
    }

    @Synchronized
    fun put(s: Suggestion) = save(items.value + (s.mediaId to s))

    @Synchronized
    fun remove(ids: Collection<String>) = save(items.value - ids.toSet())
}

/* ─────────────────── Qué hay en la foto (todo dentro del teléfono) ─────────────────── */

object Labeler {
    // Lo que reconoce el modelo (en inglés) → etiqueta en español.
    private val CATEGORIES: Map<String, String> = buildMap<String, String> {
        fun group(name: String, vararg keys: String) = keys.forEach { put(it, name) }
        group(
            "Comida", "Food", "Cuisine", "Dish", "Meal", "Supper", "Lunch", "Breakfast", "Fast food", "Pizza",
            "Cheeseburger", "Hot dog", "Sushi", "Cake", "Dessert", "Bread", "Fruit", "Vegetable", "Bento",
            "Cookie", "Pie", "Juice", "Coffee", "Cappuccino", "Wine", "Alcohol", "Eating", "Cup", "Ice cream",
        )
        group(
            "Paisajes", "Sky", "Mountain", "Sunset", "Cliff", "Lake", "River", "Forest", "Jungle", "Desert",
            "Waterfall", "Iceberg", "Glacier", "Rock", "Cloud", "Aurora", "Field", "Prairie", "Canyon", "Valley",
            "Sea", "Ocean", "Reef", "Star", "Moon", "Rainbow", "Volcano",
        )
        group("Playa", "Beach", "Sand", "Surfboard", "Swimwear", "Windsurfing", "Wetsuit", "Boat", "Sailboat")
        group(
            "Mascotas", "Dog", "Cat", "Pet", "Bird", "Shetland sheepdog", "Boxer", "Gerbil", "Rabbit", "Horse",
            "Fish", "Aquarium", "Duck", "Turtle", "Bear", "Cattle", "Sheep", "Butterfly", "Insect",
        )
        group("Plantas", "Plant", "Flower", "Petal", "Garden", "Tree", "Twig", "Wreath", "Grass", "Leaf")
        group(
            "Documentos", "Receipt", "Paper", "Document", "Text", "Handwriting", "Poster", "Presentation",
            "Whiteboard", "Newspaper", "Menu", "Passport",
        )
        group("Vehículos", "Car", "Vehicle", "Bus", "Bicycle", "Motorcycle", "Tractor", "Windshield", "Truck", "Airplane", "Wheel", "Tire")
        group(
            "Arquitectura", "Building", "Tower", "Bridge", "Skyline", "Skyscraper", "Cathedral", "Temple",
            "Castle", "Stadium", "Infrastructure", "Stairs", "Brick", "Statue", "Church", "Monument",
        )
        group(
            "Celebraciones", "Wedding", "Birthday", "Party", "Balloon", "Christmas", "Graduation", "Prom",
            "Concert", "Fireworks", "Bride", "Santa claus", "Carnival", "Event", "Bonfire", "Picnic",
        )
        group("Deporte", "Sports", "Soccer", "Baseball", "Basketball", "Badminton", "Rafting", "Running", "Swimming", "Competition", "Team", "Gymnastics", "Cycling", "Skateboard")
        group("Selfies", "Selfie")
        group("Bebés y niños", "Baby", "Child", "Toy", "Playground", "Toddler")
        group("Personas", "Person", "Smile", "Laugh", "Crowd", "Beard", "Hair", "Sitting", "Interaction", "Dress", "Skin", "Fun")
    }

    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.72f).build()) }

    private val places = HashMap<String, String>()

    /** Hasta tres etiquetas propuestas: contenido primero, lugar al final. */
    fun suggest(ctx: Context, uri: Uri, item: UploadItem, thumb: ByteArray?): List<String> {
        val out = LinkedHashSet<String>()

        val lowerName = item.name.lowercase()
        val lowerFolder = item.folder.lowercase()
        if (lowerName.startsWith("screenshot") || lowerName.startsWith("captura") ||
            lowerFolder.contains("screenshot") || lowerFolder.contains("captura")
        ) {
            out.add("Capturas de pantalla")
        } else if (thumb != null) {
            runCatching {
                val bmp = BitmapFactory.decodeByteArray(thumb, 0, thumb.size) ?: return@runCatching
                val found = Tasks.await(labeler.process(InputImage.fromBitmap(bmp, 0)), 20, TimeUnit.SECONDS)
                // Se queda con la categoría más segura; "Personas" solo si no hay nada más concreto.
                val ranked = found.sortedByDescending { it.confidence }.mapNotNull { CATEGORIES[it.text] }.distinct()
                val best = ranked.firstOrNull { it != "Personas" } ?: ranked.firstOrNull()
                if (best != null) out.add(best)
            }
        }

        if (!item.isVideo) place(ctx, uri)?.let { out.add(it) }
        return out.take(3)
    }

    /** Ciudad donde se tomó la foto, si la cámara guardó la ubicación. */
    private fun place(ctx: Context, uri: Uri): String? = runCatching {
        val source = if (Build.VERSION.SDK_INT >= 29) MediaStore.setRequireOriginal(uri) else uri
        val latLong = FloatArray(2)
        val has = ctx.contentResolver.openInputStream(source)?.use { ExifInterface(it).getLatLong(latLong) } ?: false
        if (!has) return@runCatching null
        val key = "%.2f,%.2f".format(Locale.US, latLong[0], latLong[1])
        places[key]?.let { return@runCatching it }
        @Suppress("DEPRECATION")
        val address = Geocoder(ctx, Locale("es")).getFromLocation(latLong[0].toDouble(), latLong[1].toDouble(), 1)
            ?.firstOrNull() ?: return@runCatching null
        val name = address.locality ?: address.subAdminArea ?: address.adminArea ?: return@runCatching null
        places[key] = name
        name
    }.getOrNull()
}

/* ─────────────────── Aviso de versión nueva ─────────────────── */

object Updater {
    /** Número de la última versión publicada, y su enlace de descarga. */
    val latest = MutableStateFlow(0)
    @Volatile var url: String = ""
        private set

    val available: Boolean get() = latest.value > BuildConfig.VERSION_CODE

    suspend fun check() {
        runCatching {
            val text = Http.exec(
                Request.Builder().url("https://api.github.com/repos/Miller1313/T-Pics/releases/latest")
                    .header("Accept", "application/vnd.github+json").build()
            )
            val o = JSONObject(text)
            val n = o.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return
            val assets = o.optJSONArray("assets")
            if (assets != null && assets.length() > 0) {
                url = assets.getJSONObject(0).optString("browser_download_url")
            }
            latest.value = n
        }
    }
}
