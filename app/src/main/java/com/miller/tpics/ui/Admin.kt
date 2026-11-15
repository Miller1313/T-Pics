package com.miller.tpics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.miller.tpics.BuildConfig
import com.miller.tpics.data.Account
import com.miller.tpics.data.Api
import com.miller.tpics.data.Auth
import com.miller.tpics.data.Db
import com.miller.tpics.data.HttpError
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

/* ─────────────────────────── Administración ─────────────────────────── */

private data class UserRow(val uid: String, val name: String, val email: String, val created: Long, val account: Account)

private data class LegacyGroup(val oldId: String, val label: String, val records: List<Pair<String, JSONObject>>)

private data class Payment(val date: Long, val amount: String, val ref: String, val months: Int)

private const val DAY = 86_400_000L

private fun stateOf(a: Account): String = when {
    a.status == "suspended" -> "Suspendidos"
    a.status != "active" -> "Pendientes"
    a.expired -> "Vencidos"
    a.isTrial -> "En prueba"
    else -> "Activos"
}

private suspend fun loadUsers(): List<UserRow> = coroutineScope {
    val ids = Db.getObject("v2/users", "&shallow=true") ?: return@coroutineScope emptyList()
    val keys = ArrayList<String>()
    ids.keys().forEach { keys.add(it) }
    keys.map { uid ->
        async {
            val profile = runCatching { Db.getObject("v2/users/$uid/profile") }.getOrNull()
            val account = runCatching { Db.getObject("v2/users/$uid/account") }.getOrNull()
            UserRow(
                uid, profile?.optString("name") ?: "", profile?.optString("email") ?: "",
                profile?.optLong("createdAt") ?: 0L, Account.fromJson(account),
            )
        }
    }.awaitAll().sortedBy { it.name.lowercase() }
}

private suspend fun setAccount(uid: String, status: String, paidUntil: Long, plan: String) {
    Db.patch(
        "v2/users/$uid/account",
        JSONObject().put("status", status).put("paidUntil", paidUntil).put("plan", plan).toString(),
    )
}

/** Nuevo corte: si paga antes de vencer sigue de corrido; si ya venció, cuenta desde hoy. */
private fun nextCut(a: Account, months: Int): Long {
    val now = System.currentTimeMillis()
    val from = if (a.status == "active" && a.paidUntil > now) a.paidUntil else now
    return Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()).plusMonths(months.toLong()).toInstant().toEpochMilli()
}

private suspend fun registerPayment(u: UserRow, amount: String, ref: String, months: Int) {
    val until = nextCut(u.account, months)
    Db.put(
        "v2/users/${u.uid}/payments/${newId()}",
        JSONObject().put("d", System.currentTimeMillis()).put("a", amount).put("r", ref).put("m", months)
            .put("until", until).toString(),
    )
    setAccount(u.uid, "active", until, "mensual")
}

private suspend fun loadPayments(uid: String): List<Payment> {
    val o = Db.getObject("v2/users/$uid/payments") ?: return emptyList()
    val out = ArrayList<Payment>()
    o.keys().forEach { k ->
        val p = o.optJSONObject(k) ?: return@forEach
        out.add(Payment(p.optLong("d"), p.optString("a"), p.optString("r"), p.optInt("m", 1)))
    }
    return out.sortedByDescending { it.date }
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


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdminScreen() {
    val scope = rememberCoroutineScope()
    val api by Store.apiUrl.collectAsState()
    val pay by Store.paymentInfo.collectAsState()
    val trialDays by Store.trialDays.collectAsState()
    val myUid = Auth.session.collectAsState().value?.uid ?: ""

    var tab by remember { mutableStateOf(0) }
    var url by remember(api) { mutableStateOf(api) }
    var payText by remember(pay) { mutableStateOf(pay) }
    var users by remember { mutableStateOf<List<UserRow>?>(null) }
    var busy by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<LegacyGroup>?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Todos") }
    var paying by remember { mutableStateOf<UserRow?>(null) }
    var dating by remember { mutableStateOf<UserRow?>(null) }
    var history by remember { mutableStateOf<Pair<UserRow, List<Payment>>?>(null) }

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
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Usuarios", "Configuración", "Importar").forEachIndexed { i, label ->
                    FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label) })
                }
            }

            if (tab == 0) {
                val list = users
                val clients = (list ?: emptyList()).filter { it.uid != myUid }
                val counts = remember(list) { clients.groupingBy { stateOf(it.account) }.eachCount() }

                Section("Resumen") {
                    Text("${clients.size} clientes", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    listOf("Activos", "En prueba", "Vencidos", "Pendientes", "Suspendidos").forEach { k ->
                        Line(k, "${counts[k] ?: 0}")
                    }
                }

                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Buscar por nombre o correo") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(28.dp),
                )
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val options = listOf("Todos", "Activos", "En prueba", "Vencidos", "Pendientes", "Suspendidos")
                    items(options.size) { i ->
                        FilterChip(selected = filter == options[i], onClick = { filter = options[i] }, label = { Text(options[i]) })
                    }
                }

                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                } else {
                    val q = query.trim().lowercase()
                    val shown = clients.filter { u ->
                        (filter == "Todos" || stateOf(u.account) == filter) &&
                            (q.isEmpty() || u.name.lowercase().contains(q) || u.email.lowercase().contains(q))
                    }
                    if (shown.isEmpty()) {
                        Text(
                            if (clients.isEmpty()) "Aún no se ha registrado ningún cliente." else "Sin resultados.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                    shown.forEach { u ->
                        androidx.compose.runtime.key(u.uid) {
                            var menu by remember { mutableStateOf(false) }
                            val state = stateOf(u.account)
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
                                    .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(u.name.ifEmpty { "(sin nombre)" }, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            u.email, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Box {
                                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Opciones") }
                                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                            DropdownMenuItem(text = { Text("Prueba de 7 días") }, onClick = {
                                                menu = false
                                                run("Activando prueba…") {
                                                    setAccount(u.uid, "active", System.currentTimeMillis() + 7 * DAY, Account.TRIAL); refreshUsers()
                                                }
                                            })
                                            DropdownMenuItem(text = { Text("Prueba de 30 días") }, onClick = {
                                                menu = false
                                                run("Activando prueba…") {
                                                    setAccount(u.uid, "active", System.currentTimeMillis() + 30 * DAY, Account.TRIAL); refreshUsers()
                                                }
                                            })
                                            DropdownMenuItem(text = { Text("Cambiar fecha de corte") }, onClick = { menu = false; dating = u })
                                            DropdownMenuItem(text = { Text("Activar sin vencimiento") }, onClick = {
                                                menu = false
                                                run("Activando…") { setAccount(u.uid, "active", 0, "cortesía"); refreshUsers() }
                                            })
                                            DropdownMenuItem(text = { Text("Ver pagos") }, onClick = {
                                                menu = false
                                                run("Cargando pagos…") { history = u to loadPayments(u.uid) }
                                            })
                                            DropdownMenuItem(text = { Text("Enviar cambio de contraseña") }, onClick = {
                                                menu = false
                                                run("Enviando correo…") {
                                                    Auth.resetPassword(u.email)
                                                    Store.say("Se envió un correo a ${u.email} para cambiar la contraseña")
                                                }
                                            })
                                            if (u.account.status == "suspended") {
                                                DropdownMenuItem(text = { Text("Reactivar") }, onClick = {
                                                    menu = false
                                                    run("Reactivando…") { setAccount(u.uid, "active", u.account.paidUntil, u.account.plan); refreshUsers() }
                                                })
                                            } else {
                                                DropdownMenuItem(
                                                    text = { Text("Suspender", color = MaterialTheme.colorScheme.error) },
                                                    onClick = {
                                                        menu = false
                                                        run("Suspendiendo…") { setAccount(u.uid, "suspended", u.account.paidUntil, u.account.plan); refreshUsers() }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    planText(u.account, false),
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                    color = when (state) {
                                        "Activos" -> MaterialTheme.colorScheme.secondary
                                        "En prueba" -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.error
                                    },
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(enabled = busy.isEmpty(), onClick = { paying = u }) { Text("Registrar pago") }
                            }
                        }
                    }
                    TextButton(
                        enabled = busy.isEmpty(), onClick = { run("Actualizando…") { refreshUsers() } },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) { Text("Actualizar lista") }
                }
            }

            if (tab == 1) {
                Section("Prueba gratis") {
                    Text(
                        "Al registrarse, cada cuenta nueva empieza sola con estos días de prueba. Al terminar, queda pendiente de pago.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Sin prueba", 7 to "7 días", 30 to "30 días").forEach { (days, label) ->
                            FilterChip(selected = trialDays == days, label = { Text(label) }, onClick = {
                                run("Guardando…") {
                                    Db.patch("v2/config", JSONObject().put("trialDays", days).toString())
                                    Store.trialDays.value = days
                                }
                            })
                        }
                    }
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
            }

            if (tab == 2) {
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
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    paying?.let { u ->
        var amount by remember(u.uid) { mutableStateOf("") }
        var ref by remember(u.uid) { mutableStateOf("") }
        var months by remember(u.uid) { mutableStateOf(1) }
        AlertDialog(
            onDismissRequest = { paying = null },
            title = { Text("Pago de ${u.name.ifEmpty { u.email }}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it }, label = { Text("Monto") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = ref, onValueChange = { ref = it }, label = { Text("Referencia (opcional)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Meses que cubre", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 3, 6, 12).forEach { m ->
                            FilterChip(selected = months == m, onClick = { months = m }, label = { Text("$m") })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Nuevo corte: ${dateText(nextCut(u.account, months))}",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = amount.isNotBlank(), onClick = {
                    paying = null
                    run("Registrando pago…") {
                        registerPayment(u, amount.trim(), ref.trim(), months)
                        Store.say("Pago registrado")
                        refreshUsers()
                    }
                }) { Text("Registrar") }
            },
            dismissButton = { TextButton(onClick = { paying = null }) { Text("Cancelar") } },
        )
    }

    dating?.let { u ->
        val start = if (u.account.paidUntil > 0) u.account.paidUntil else System.currentTimeMillis()
        val picker = rememberDatePickerState(initialSelectedDateMillis = start)
        DatePickerDialog(
            onDismissRequest = { dating = null },
            confirmButton = {
                TextButton(onClick = {
                    val day = picker.selectedDateMillis
                    dating = null
                    if (day != null) run("Guardando…") {
                        // El calendario devuelve la medianoche UTC: se lleva al final de ese día.
                        setAccount(u.uid, "active", day + DAY + 4 * 3_600_000L, u.account.plan.ifEmpty { "mensual" })
                        refreshUsers()
                    }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { dating = null }) { Text("Cancelar") } },
        ) { DatePicker(state = picker) }
    }

    history?.let { (u, list) ->
        AlertDialog(
            onDismissRequest = { history = null },
            title = { Text("Pagos de ${u.name.ifEmpty { u.email }}") },
            text = {
                if (list.isEmpty()) {
                    Text("Aún no hay pagos registrados.")
                } else {
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                        list.forEach { p ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text("${p.amount} · ${p.months} mes" + if (p.months > 1) "es" else "", fontWeight = FontWeight.SemiBold)
                                Text(
                                    dateText(p.date) + if (p.ref.isNotEmpty()) " · Ref. ${p.ref}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { history = null }) { Text("Cerrar") } },
        )
    }
}
