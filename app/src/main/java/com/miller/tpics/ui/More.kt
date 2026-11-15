package com.miller.tpics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miller.tpics.BuildConfig
import com.miller.tpics.data.Account
import com.miller.tpics.data.Api
import com.miller.tpics.data.Auth
import com.miller.tpics.data.Db
import com.miller.tpics.data.HttpError
import com.miller.tpics.data.Prefs
import com.miller.tpics.data.Store
import com.miller.tpics.data.friendly
import com.miller.tpics.data.newId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FMT = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale("es"))
private fun dateText(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DATE_FMT)

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(18.dp),
    ) {
        Text(
            title.uppercase(), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun planText(a: Account, admin: Boolean): String = when {
    admin -> "Administrador"
    a.status == "suspended" -> "Suspendida"
    a.status != "active" -> "Pendiente de activación"
    a.paidUntil == 0L -> "Activa · sin vencimiento"
    a.paidUntil < System.currentTimeMillis() -> "Venció el ${dateText(a.paidUntil)}"
    else -> "Activa hasta el ${dateText(a.paidUntil)}"
}

/* ───────────────────── Almacenamiento y plan ───────────────────── */

@Composable
fun StorageScreen() {
    val media by Store.media.collectAsState()
    val account by Store.account.collectAsState()
    val admin by Store.isAdmin.collectAsState()
    val pay by Store.paymentInfo.collectAsState()
    val live = remember(media) { media.filter { it.trashedAt == 0L } }
    val photos = live.count { !it.isVideo }
    val videos = live.count { it.isVideo }
    val bytes = remember(media) { media.sumOf { it.size } }
    val trashed = media.size - live.size

    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { Bar("Almacenamiento y plan") }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Section("Tu respaldo") {
                Text(sizeText(bytes), style = MaterialTheme.typography.headlineSmall)
                Text(
                    "ocupados en la nube", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Line("Fotos", "$photos")
                Line("Videos", "$videos")
                Line("En la papelera", "$trashed")
            }
            Section("Tu plan") {
                Text(planText(account, admin), style = MaterialTheme.typography.titleMedium)
                if (account.plan.isNotEmpty()) Line("Plan", account.plan)
                if (!admin && pay.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(pay, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { Store.syncNow() }) { Text("Actualizar estado") }
            }
        }
    }
}

/* ───────────────────────────── Ajustes ───────────────────────────── */

@Composable
fun SettingsScreen() {
    val theme by Prefs.theme.collectAsState()
    val columns by Prefs.columns.collectAsState()
    val wifi by Prefs.wifiOnly.collectAsState()
    val session by Auth.session.collectAsState()
    var confirmOut by remember { mutableStateOf(false) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { Bar("Ajustes") }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Section("Apariencia") {
                Text("Tema", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Sistema", "Claro", "Oscuro").forEachIndexed { i, label ->
                        FilterChip(selected = theme == i, onClick = { Prefs.setTheme(i) }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Fotos por fila", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (3..6).forEach { n ->
                        FilterChip(selected = columns == n, onClick = { Prefs.setColumns(n) }, label = { Text("$n") })
                    }
                }
            }
            Section("Subidas") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Subir solo con Wi-Fi", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Evita gastar datos móviles al respaldar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = wifi, onCheckedChange = { Prefs.setWifiOnly(it) })
                }
            }
            Section("Cuenta") {
                Text(session?.email ?: "", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { confirmOut = true }) { Text("Cerrar sesión") }
            }
            Text(
                "T-Pics ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(24.dp),
            )
        }
    }

    if (confirmOut) {
        AlertDialog(
            onDismissRequest = { confirmOut = false },
            title = { Text("¿Cerrar sesión?") },
            text = { Text("Tus fotos siguen guardadas en la nube. Las subidas pendientes se cancelan.") },
            confirmButton = { TextButton(onClick = { confirmOut = false; signOutEverywhere() }) { Text("Cerrar sesión") } },
            dismissButton = { TextButton(onClick = { confirmOut = false }) { Text("Cancelar") } },
        )
    }
}

/* ─────────────────────────── Administración ─────────────────────────── */

private data class UserRow(val uid: String, val name: String, val email: String, val account: Account)

private data class LegacyGroup(val oldId: String, val label: String, val records: List<Pair<String, JSONObject>>)

private suspend fun loadUsers(): List<UserRow> = coroutineScope {
    val ids = Db.getObject("v2/users", "&shallow=true") ?: return@coroutineScope emptyList()
    val keys = ArrayList<String>()
    ids.keys().forEach { keys.add(it) }
    keys.map { uid ->
        async {
            val profile = runCatching { Db.getObject("v2/users/$uid/profile") }.getOrNull()
            val account = runCatching { Db.getObject("v2/users/$uid/account") }.getOrNull()
            UserRow(uid, profile?.optString("name") ?: "", profile?.optString("email") ?: "", Account.fromJson(account))
        }
    }.awaitAll().sortedBy { it.name.lowercase() }
}

private suspend fun setAccount(uid: String, status: String, paidUntil: Long) {
    Db.patch(
        "v2/users/$uid/account",
        JSONObject().put("status", status).put("paidUntil", paidUntil).toString(),
    )
}

private val TAG_IN_ORIGIN = Regex("\\(([^)]+)\\)")

/** Copia fotos del T-Pics anterior a la cuenta nueva de un usuario, con sus etiquetas. */
private suspend fun importLegacy(
    records: List<Pair<String, JSONObject>>,
    bot: String,
    targetUid: String,
    onProgress: (Int) -> Unit,
): Int {
    // Etiquetas que ya tiene el usuario destino
    val existing = Db.getObject("v2/users/$targetUid/tags")
    val tagIds = HashMap<String, String>()
    existing?.keys()?.forEach { k ->
        existing.optJSONObject(k)?.optString("n")?.let { tagIds[it.lowercase()] = k }
    }
    var colorIndex = tagIds.size

    var done = 0
    for (group in records.chunked(300)) {
        val valid = group.filter { it.second.optString("file_id").isNotEmpty() }
        val refs = Api.adminSign(targetUid, bot, valid.map { it.second.optString("file_id") })
        val body = JSONObject()
        valid.forEachIndexed { i, (key, o) ->
            val ref = refs.getOrNull(i) ?: ""
            if (ref.isEmpty()) return@forEachIndexed
            val tags = JSONObject()
            TAG_IN_ORIGIN.find(o.optString("origen"))?.groupValues?.get(1)?.split(",")?.forEach { raw ->
                val name = raw.trim()
                if (name.isEmpty() || name == "Sin etiqueta") return@forEach
                val id = tagIds.getOrPut(name.lowercase()) {
                    val fresh = newId()
                    Db.put(
                        "v2/users/$targetUid/tags/$fresh",
                        JSONObject().put("n", name).put("c", colorIndex++ % Store.TAG_COLOR_COUNT).toString(),
                    )
                    fresh
                }
                tags.put(id, true)
            }
            val m = JSONObject()
                .put("r", ref)
                .put("k", if (o.optString("tipo") == "video") "v" else "i")
                .put("d", o.optLong("timestamp", System.currentTimeMillis()))
                .put("u", Db.SERVER_TIME)
            if (tags.length() > 0) m.put("tg", tags)
            body.put("L" + key.replace(Regex("[.#$\\[\\]/]"), "_"), m)
        }
        if (body.length() > 0) Db.patch("v2/users/$targetUid/media", body.toString())
        done += group.size
        onProgress(done)
    }
    return done
}

private fun toRecords(o: JSONObject?): List<Pair<String, JSONObject>> {
    val out = ArrayList<Pair<String, JSONObject>>()
    o?.keys()?.forEach { k -> o.optJSONObject(k)?.let { out.add(k to it) } }
    return out
}

@Composable
fun AdminScreen() {
    val scope = rememberCoroutineScope()
    val api by Store.apiUrl.collectAsState()
    val pay by Store.paymentInfo.collectAsState()
    val myUid = Auth.session.collectAsState().value?.uid ?: ""

    var url by remember(api) { mutableStateOf(api) }
    var payText by remember(pay) { mutableStateOf(pay) }
    var users by remember { mutableStateOf<List<UserRow>?>(null) }
    var busy by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<LegacyGroup>?>(null) }

    fun run(label: String, block: suspend () -> Unit) {
        if (busy.isNotEmpty()) return
        busy = label
        scope.launch {
            try {
                block()
            } catch (e: HttpError) {
                Store.say(if (e.code == 401 || e.code == 403) "Sin permiso. Revisa las reglas de Firebase." else e.friendly())
            } catch (e: Exception) {
                Store.say(e.friendly())
            } finally {
                busy = ""
            }
        }
    }

    suspend fun refreshUsers() { users = loadUsers() }

    LaunchedEffect(Unit) { runCatching { refreshUsers() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { Bar("Administración", busy.ifEmpty { null }) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {

            Section("Servidor") {
                Text(
                    "Dirección de tu Worker de Cloudflare. Todos los usuarios la reciben automáticamente.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it.trim() },
                    placeholder = { Text("https://tpics.tu-cuenta.workers.dev") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(10.dp))
                Button(enabled = busy.isEmpty() && url.startsWith("https://"), onClick = {
                    run("Probando servidor…") {
                        val clean = url.trimEnd('/')
                        val ready = Api.health(clean)
                        Db.patch("v2/config", JSONObject().put("apiUrl", clean).toString())
                        Store.apiUrl.value = clean
                        Store.say(if (ready) "Servidor conectado" else "Conectado, pero faltan variables secretas en el Worker")
                        Store.syncNow()
                    }
                }) { Text("Guardar y probar") }
            }

            Section("Datos de pago") {
                Text(
                    "Este texto lo ven los usuarios con cuenta pendiente o vencida.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = payText, onValueChange = { payText = it },
                    placeholder = { Text("Ej.: Pago Móvil 0412…, monto mensual, y envía el comprobante por WhatsApp.") },
                    minLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(10.dp))
                Button(enabled = busy.isEmpty(), onClick = {
                    run("Guardando…") {
                        Db.patch("v2/config", JSONObject().put("paymentInfo", payText.trim()).toString())
                        Store.paymentInfo.value = payText.trim()
                        Store.say("Guardado")
                    }
                }) { Text("Guardar") }
            }

            Section("Usuarios") {
                val list = users
                if (list == null) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else if (list.isEmpty()) {
                    Text("Aún no hay usuarios registrados.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    list.forEach { u ->
                        val isMe = u.uid == myUid
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(u.name.ifEmpty { "(sin nombre)" } + if (isMe) " · tú" else "", style = MaterialTheme.typography.titleMedium)
                            Text(u.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                planText(u.account, isMe),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isMe || u.account.active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                            )
                            if (!isMe) {
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = busy.isEmpty(), onClick = {
                                        run("Activando…") {
                                            val from = maxOf(System.currentTimeMillis(), u.account.paidUntil)
                                            setAccount(u.uid, "active", from + 30L * 24 * 3600 * 1000)
                                            refreshUsers()
                                        }
                                    }) { Text("+30 días") }
                                    OutlinedButton(enabled = busy.isEmpty(), onClick = {
                                        run("Activando…") { setAccount(u.uid, "active", 0); refreshUsers() }
                                    }) { Text("Sin límite") }
                                    if (u.account.status == "active") {
                                        TextButton(enabled = busy.isEmpty(), onClick = {
                                            run("Suspendiendo…") { setAccount(u.uid, "suspended", u.account.paidUntil); refreshUsers() }
                                        }) { Text("Suspender", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(enabled = busy.isEmpty(), onClick = { run("Actualizando…") { refreshUsers() } }) {
                    Text("Actualizar lista")
                }
            }

            Section("Importar fotos del T-Pics anterior") {
                Text(
                    "Trae las fotos ya subidas, con sus etiquetas. Se puede repetir sin duplicar. " +
                        "Las miniaturas se crean poco a poco después de importar.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(enabled = busy.isEmpty() && api.isNotEmpty(), onClick = {
                    run("Importando tus fotos…") {
                        val data = Db.getOpen(BuildConfig.LEGACY_PERSONAL_DB + "/fotos.json") as? JSONObject
                        val records = toRecords(data)
                        if (records.isEmpty()) {
                            Store.say("No se encontraron fotos personales")
                        } else {
                            val n = importLegacy(records, "p", myUid) { busy = "Importando… $it de ${records.size}" }
                            Store.say("$n fotos importadas a tu cuenta")
                            Store.sync()
                        }
                    }
                }) { Text("Importar mis fotos personales") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(enabled = busy.isEmpty() && api.isNotEmpty(), onClick = {
                    run("Buscando fotos de clientes…") {
                        val records = toRecords(Db.getObject("fotos"))
                        val names = HashMap<String, String>()
                        runCatching {
                            toRecords(Db.getObject("solicitudes_web")).forEach { (_, o) ->
                                val id = o.optString("idInterno").ifEmpty { o.optString("id") }
                                if (id.isNotEmpty()) names[id] = o.optString("nombre")
                            }
                            toRecords(Db.getObject("solicitudes")).forEach { (k, o) -> names[k] = o.optString("nombre") }
                        }
                        groups = records.groupBy { it.second.optString("usuario_id").ifEmpty { "sin usuario" } }
                            .map { (id, list) -> LegacyGroup(id, names[id]?.takeIf { it.isNotEmpty() } ?: id, list) }
                            .sortedByDescending { it.records.size }
                        if (records.isEmpty()) Store.say("No hay fotos de clientes")
                    }
                }) { Text("Buscar fotos de clientes") }

                groups?.forEach { g ->
                    var open by remember(g.oldId) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${g.records.size} fotos", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box {
                            OutlinedButton(enabled = busy.isEmpty(), onClick = { open = true }) { Text("Asignar a…") }
                            DropdownMenu(open, onDismissRequest = { open = false }) {
                                (users ?: emptyList()).forEach { u ->
                                    DropdownMenuItem(
                                        text = { Text(u.name.ifEmpty { u.email }) },
                                        onClick = {
                                            open = false
                                            run("Importando…") {
                                                val n = importLegacy(g.records, "g", u.uid) {
                                                    busy = "Importando… $it de ${g.records.size}"
                                                }
                                                Store.say("$n fotos importadas para ${u.name.ifEmpty { u.email }}")
                                                if (u.uid == myUid) Store.sync()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
