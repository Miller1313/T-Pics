package com.miller.tpics.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/* ───────────────────────────── Modelos ───────────────────────────── */

data class Media(
    val id: String,
    val ref: String = "",              // archivo en un solo trozo
    val parts: List<String> = emptyList(), // archivo grande, en varios trozos
    val partSize: Long = 0,
    val thumb: String = "",
    val kind: String = "i",            // i = foto, v = video
    val name: String = "",
    val mime: String = "",
    val size: Long = 0,
    val date: Long = 0,
    val w: Int = 0,
    val h: Int = 0,
    val dur: Long = 0,
    val tags: List<String> = emptyList(),
    val fav: Boolean = false,
    val trashedAt: Long = 0,
    val hash: String = "",
    val updated: Long = 0,
) {
    val isVideo get() = kind == "v"
    val allRefs: List<String> get() = (if (ref.isNotEmpty()) listOf(ref) else parts) +
        (if (thumb.isNotEmpty()) listOf(thumb) else emptyList())

    fun toJson(): JSONObject {
        val o = JSONObject()
        if (ref.isNotEmpty()) o.put("r", ref)
        if (parts.isNotEmpty()) { o.put("p", JSONArray(parts)); o.put("ps", partSize) }
        if (thumb.isNotEmpty()) o.put("t", thumb)
        o.put("k", kind)
        if (name.isNotEmpty()) o.put("n", name)
        if (mime.isNotEmpty()) o.put("m", mime)
        if (size > 0) o.put("s", size)
        o.put("d", date)
        if (w > 0) o.put("w", w)
        if (h > 0) o.put("h", h)
        if (dur > 0) o.put("du", dur)
        if (tags.isNotEmpty()) {
            val t = JSONObject()
            tags.forEach { t.put(it, true) }
            o.put("tg", t)
        }
        if (fav) o.put("f", true)
        if (trashedAt > 0) o.put("x", trashedAt)
        if (hash.isNotEmpty()) o.put("hs", hash)
        o.put("u", updated)
        return o
    }

    companion object {
        fun fromJson(id: String, o: JSONObject): Media {
            val parts = ArrayList<String>()
            o.optJSONArray("p")?.let { a -> for (i in 0 until a.length()) parts.add(a.optString(i)) }
            val tags = ArrayList<String>()
            o.optJSONObject("tg")?.let { t -> t.keys().forEach { tags.add(it) } }
            return Media(
                id = id,
                ref = o.optString("r"),
                parts = parts,
                partSize = o.optLong("ps"),
                thumb = o.optString("t"),
                kind = o.optString("k", "i"),
                name = o.optString("n"),
                mime = o.optString("m"),
                size = o.optLong("s"),
                date = o.optLong("d"),
                w = o.optInt("w"),
                h = o.optInt("h"),
                dur = o.optLong("du"),
                tags = tags,
                fav = o.optBoolean("f"),
                trashedAt = o.optLong("x"),
                hash = o.optString("hs"),
                updated = o.optLong("u"),
            )
        }
    }
}

data class Tag(val id: String, val name: String, val color: Int)

data class Account(
    val status: String = "pending",   // pending | active | suspended
    val plan: String = "",
    val paidUntil: Long = 0,
) {
    val active: Boolean
        get() = status == "active" && (paidUntil == 0L || paidUntil > System.currentTimeMillis())

    companion object {
        fun fromJson(o: JSONObject?): Account = if (o == null) Account() else Account(
            status = o.optString("status", "pending"),
            plan = o.optString("plan"),
            paidUntil = o.optLong("paidUntil"),
        )
    }
}

fun newId(): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    val rnd = (1..8).map { chars.random() }.joinToString("")
    return java.lang.Long.toString(System.currentTimeMillis(), 36) + rnd
}

/* ─────────────────────── Almacén local + sincronización ───────────────────────
 * Todo se guarda en el teléfono, así la galería abre al instante y sin internet.
 * Con la nube solo se intercambian los cambios desde la última vez.
 */

object Store {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val media = MutableStateFlow<List<Media>>(emptyList())      // ordenadas, más nuevas primero
    val tags = MutableStateFlow<List<Tag>>(emptyList())
    val account = MutableStateFlow(Account())
    val isAdmin = MutableStateFlow(false)
    val apiUrl = MutableStateFlow("")
    val paymentInfo = MutableStateFlow("")
    val syncing = MutableStateFlow(false)
    val loaded = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Firmas "nombre|tamaño" y huellas de lo ya respaldado, para no subir dos veces. */
    @Volatile
    var backedSigs: Set<String> = emptySet()
        private set

    @Volatile
    var backedHashes: Map<String, String> = emptyMap()
        private set

    private lateinit var appContext: Context
    private var uid = ""
    private var lastU = 0L
    private val syncMutex = Mutex()
    private var saveJob: Job? = null

    val canUpload: Boolean get() = isAdmin.value || account.value.active

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    fun say(text: String) { messages.tryEmit(text) }

    private fun cacheFile(): File = File(appContext.filesDir, "store_$uid.json")
    private fun root() = "v2/users/$uid"

    /* ── Abrir / cerrar sesión ── */

    suspend fun open(userId: String) = withContext(Dispatchers.IO) {
        if (uid == userId && loaded.value) return@withContext
        uid = userId
        lastU = 0
        var list = emptyList<Media>()
        var tagList = emptyList<Tag>()
        try {
            val f = cacheFile()
            if (f.exists()) {
                val o = JSONObject(f.readText())
                lastU = o.optLong("lastU")
                apiUrl.value = o.optString("apiUrl")
                paymentInfo.value = o.optString("paymentInfo")
                isAdmin.value = o.optBoolean("admin")
                account.value = Account.fromJson(o.optJSONObject("account"))
                val m = o.optJSONObject("media")
                if (m != null) {
                    val out = ArrayList<Media>(m.length())
                    m.keys().forEach { k -> out.add(Media.fromJson(k, m.getJSONObject(k))) }
                    list = out
                }
                val t = o.optJSONArray("tags")
                if (t != null) {
                    val out = ArrayList<Tag>()
                    for (i in 0 until t.length()) {
                        val x = t.getJSONObject(i)
                        out.add(Tag(x.getString("id"), x.optString("n"), x.optInt("c")))
                    }
                    tagList = out
                }
            }
        } catch (_: Exception) {
            lastU = 0
        }
        setMedia(list)
        tags.value = tagList.sortedBy { it.name.lowercase() }
        loaded.value = true
    }

    fun close() {
        uid = ""
        lastU = 0
        loaded.value = false
        media.value = emptyList()
        tags.value = emptyList()
        account.value = Account()
        isAdmin.value = false
        backedSigs = emptySet()
        backedHashes = emptyMap()
    }

    private fun setMedia(list: List<Media>) {
        val sorted = list.sortedWith(compareByDescending<Media> { it.date }.thenByDescending { it.id })
        media.value = sorted
        val sigs = HashSet<String>(sorted.size)
        val hashes = HashMap<String, String>(sorted.size)
        for (m in sorted) {
            if (m.name.isNotEmpty() && m.size > 0) sigs.add("${m.name}|${m.size}")
            if (m.hash.isNotEmpty()) hashes[m.hash] = m.id
        }
        backedSigs = sigs
        backedHashes = hashes
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        val owner = uid
        saveJob = scope.launch(Dispatchers.IO) {
            delay(800)
            if (owner != uid || owner.isEmpty()) return@launch
            try {
                val o = JSONObject()
                o.put("lastU", lastU)
                o.put("apiUrl", apiUrl.value)
                o.put("paymentInfo", paymentInfo.value)
                o.put("admin", isAdmin.value)
                val a = account.value
                o.put("account", JSONObject().put("status", a.status).put("plan", a.plan).put("paidUntil", a.paidUntil))
                val m = JSONObject()
                media.value.forEach { m.put(it.id, it.toJson()) }
                o.put("media", m)
                val t = JSONArray()
                tags.value.forEach { t.put(JSONObject().put("id", it.id).put("n", it.name).put("c", it.color)) }
                o.put("tags", t)
                val tmp = File(appContext.filesDir, "store_$owner.tmp")
                tmp.writeText(o.toString())
                tmp.renameTo(cacheFile())
            } catch (_: Exception) {
            }
        }
    }

    /* ── Sincronizar ── */

    fun syncNow() {
        scope.launch { sync() }
    }

    suspend fun sync() {
        if (uid.isEmpty()) return
        syncMutex.withLock {
            syncing.value = true
            try {
                // Configuración general
                Db.getObject("v2/config")?.let { c ->
                    apiUrl.value = c.optString("apiUrl").trimEnd('/')
                    paymentInfo.value = c.optString("paymentInfo")
                }

                // ¿Soy administrador? La primera cuenta que se crea lo reclama.
                var admin = Db.get("v2/admins/$uid") == true
                if (!admin) {
                    admin = try {
                        Db.put("v2/admins/$uid", "true"); true
                    } catch (_: HttpError) {
                        false
                    }
                }
                isAdmin.value = admin
                account.value = Account.fromJson(Db.getObject("${root()}/account"))

                // Etiquetas
                val tagObj = Db.getObject("${root()}/tags")
                val tagList = ArrayList<Tag>()
                tagObj?.keys()?.forEach { k ->
                    val x = tagObj.optJSONObject(k) ?: return@forEach
                    tagList.add(Tag(k, x.optString("n"), x.optInt("c")))
                }
                tags.value = tagList.sortedBy { it.name.lowercase() }

                // Fotos: solo lo que cambió desde la última vez
                val changes = try {
                    Db.getObject("${root()}/media", "&orderBy=%22u%22&startAt=${lastU + 1}")
                } catch (e: HttpError) {
                    if (e.code == 400) { lastU = 0; Db.getObject("${root()}/media") } else throw e
                }
                if (changes != null && changes.length() > 0) {
                    val map = LinkedHashMap<String, Media>()
                    if (lastU > 0) media.value.forEach { map[it.id] = it }
                    var maxU = lastU
                    changes.keys().forEach { k ->
                        val x = changes.optJSONObject(k) ?: return@forEach
                        val u = x.optLong("u")
                        if (u > maxU) maxU = u
                        if (x.optBoolean("gone")) map.remove(k) else map[k] = Media.fromJson(k, x)
                    }
                    lastU = maxU
                    setMedia(map.values.toList())
                }
                scheduleSave()
                purgeOldTrash()
            } catch (e: AuthError) {
                say(e.friendly())
            } catch (e: Exception) {
                if (media.value.isEmpty()) say("No se pudo sincronizar: ${e.friendly()}")
            } finally {
                syncing.value = false
            }
        }
    }

    /* ── Cambios en fotos ── */

    private fun pushMedia(changed: List<Media>) {
        if (changed.isEmpty()) return
        val owner = uid
        scope.launch {
            try {
                changed.chunked(200).forEach { group ->
                    val body = JSONObject()
                    group.forEach { m -> body.put(m.id, m.toJson().put("u", Db.SERVER_TIME)) }
                    Db.patch("v2/users/$owner/media", body.toString())
                }
            } catch (e: Exception) {
                say("No se guardó en la nube: ${e.friendly()}")
            }
        }
    }

    private fun update(ids: Collection<String>, change: (Media) -> Media) {
        if (ids.isEmpty()) return
        val set = ids.toHashSet()
        val changed = ArrayList<Media>()
        val now = System.currentTimeMillis()
        val list = media.value.map { m ->
            if (m.id in set) change(m).copy(updated = now).also { changed.add(it) } else m
        }
        setMedia(list)
        scheduleSave()
        pushMedia(changed)
    }

    /** Agrega una foto recién subida (ya guardada en la nube por quien llama). */
    fun addLocal(m: Media) {
        setMedia(media.value.filter { it.id != m.id } + m)
        scheduleSave()
    }

    suspend fun saveNew(m: Media) {
        Db.put("${root()}/media/${m.id}", m.toJson().put("u", Db.SERVER_TIME).toString())
        addLocal(m)
    }

    fun setThumb(id: String, thumbRef: String) = update(listOf(id)) { it.copy(thumb = thumbRef) }

    fun addTags(ids: Collection<String>, tagIds: Collection<String>) =
        update(ids) { it.copy(tags = (it.tags + tagIds).distinct()) }

    fun setTags(ids: Collection<String>, tagIds: Collection<String>) =
        update(ids) { it.copy(tags = tagIds.distinct()) }

    fun removeTag(ids: Collection<String>, tagId: String) =
        update(ids) { it.copy(tags = it.tags - tagId) }

    fun setFav(ids: Collection<String>, fav: Boolean) = update(ids) { it.copy(fav = fav) }

    fun trash(ids: Collection<String>) {
        val now = System.currentTimeMillis()
        update(ids) { it.copy(trashedAt = now) }
    }

    fun restore(ids: Collection<String>) = update(ids) { it.copy(trashedAt = 0) }

    /** Borra para siempre: quita el registro y pide borrar los archivos en Telegram. */
    fun purge(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toHashSet()
        val gone = media.value.filter { it.id in set }
        setMedia(media.value.filter { it.id !in set })
        scheduleSave()
        val owner = uid
        scope.launch {
            try {
                gone.chunked(200).forEach { group ->
                    val body = JSONObject()
                    group.forEach { m ->
                        body.put(m.id, JSONObject().put("gone", true).put("u", Db.SERVER_TIME))
                    }
                    Db.patch("v2/users/$owner/media", body.toString())
                }
                val refs = gone.flatMap { it.allRefs }
                refs.chunked(100).forEach { Api.delete(it) }
            } catch (e: Exception) {
                say("No se completó el borrado: ${e.friendly()}")
            }
        }
    }

    private fun purgeOldTrash() {
        val limit = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val old = media.value.filter { it.trashedAt in 1 until limit }.map { it.id }
        if (old.isNotEmpty()) purge(old)
    }

    /* ── Etiquetas ── */

    fun tagById(id: String): Tag? = tags.value.firstOrNull { it.id == id }

    fun createTag(name: String, color: Int = -1): Tag {
        val clean = name.trim()
        tags.value.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it }
        val tag = Tag(newId(), clean, if (color >= 0) color else tags.value.size % TAG_COLOR_COUNT)
        tags.value = (tags.value + tag).sortedBy { it.name.lowercase() }
        scheduleSave()
        pushTag(tag)
        return tag
    }

    fun updateTag(tag: Tag) {
        tags.value = tags.value.map { if (it.id == tag.id) tag else it }.sortedBy { it.name.lowercase() }
        scheduleSave()
        pushTag(tag)
    }

    private fun pushTag(tag: Tag) {
        val owner = uid
        scope.launch {
            try {
                Db.put(
                    "v2/users/$owner/tags/${tag.id}",
                    JSONObject().put("n", tag.name).put("c", tag.color).toString(),
                )
            } catch (e: Exception) {
                say("No se guardó la etiqueta: ${e.friendly()}")
            }
        }
    }

    fun deleteTag(id: String) {
        tags.value = tags.value.filter { it.id != id }
        val affected = media.value.filter { id in it.tags }.map { it.id }
        removeTag(affected, id)
        scheduleSave()
        val owner = uid
        scope.launch {
            try { Db.delete("v2/users/$owner/tags/$id") } catch (_: Exception) {}
        }
    }

    /* ── Direcciones de archivos ── */

    fun fileUrl(ref: String, mime: String = ""): String =
        "${apiUrl.value}/f/$ref" + if (mime.isNotEmpty()) "?m=${enc(mime)}" else ""

    fun fullUrl(m: Media): String =
        if (m.ref.isNotEmpty()) fileUrl(m.ref, m.mime)
        else "${apiUrl.value}/v?p=${m.parts.joinToString(",")}&ps=${m.partSize}&sz=${m.size}" +
            if (m.mime.isNotEmpty()) "&m=${enc(m.mime)}" else ""

    const val TAG_COLOR_COUNT = 10
}

/* ───────────────────── Servidor intermediario (Worker) ───────────────────── */

object Api {
    private fun base(): String {
        val b = Store.apiUrl.value
        if (b.isEmpty()) throw IllegalStateException("Falta configurar el servidor")
        return b
    }

    /** Sube un trozo (máx. 19 MB) y devuelve su referencia firmada. */
    suspend fun upload(bytes: ByteArray, length: Int, name: String): String {
        val req = Request.Builder()
            .url("${base()}/upload?n=${enc(name)}")
            .header("Authorization", "Bearer ${Auth.token()}")
            .post(bytes.toRequestBody("application/octet-stream".toMediaType(), 0, length))
            .build()
        return JSONObject(Http.exec(req)).getString("ref")
    }

    suspend fun delete(refs: List<String>) {
        if (refs.isEmpty()) return
        val req = Request.Builder()
            .url("${base()}/delete")
            .header("Authorization", "Bearer ${Auth.token()}")
            .post(JSONObject().put("refs", JSONArray(refs)).toString().toRequestBody(Http.JSON))
            .build()
        Http.exec(req)
    }

    /** Admin: convierte file_id antiguos en referencias firmadas para un usuario. */
    suspend fun adminSign(targetUid: String, bot: String, fileIds: List<String>): List<String> {
        val items = JSONArray()
        fileIds.forEach { items.put(JSONObject().put("b", bot).put("f", it)) }
        val req = Request.Builder()
            .url("${base()}/admin/sign")
            .header("Authorization", "Bearer ${Auth.token()}")
            .post(JSONObject().put("uid", targetUid).put("items", items).toString().toRequestBody(Http.JSON))
            .build()
        val arr = JSONObject(Http.exec(req)).getJSONArray("refs")
        return (0 until arr.length()).map { arr.optString(it) }
    }

    suspend fun health(url: String): Boolean {
        val text = Http.exec(Request.Builder().url(url.trimEnd('/') + "/health").build())
        return JSONObject(text).optBoolean("ready")
    }

    suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${Auth.token()}").build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code, "")
            r.body!!.bytes()
        }
    }
}
