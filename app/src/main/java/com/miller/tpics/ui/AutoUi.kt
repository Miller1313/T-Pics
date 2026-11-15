package com.miller.tpics.ui

import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import com.miller.tpics.data.AutoBackup
import com.miller.tpics.data.AutoFolder
import com.miller.tpics.data.Device
import com.miller.tpics.data.LocalFolder
import com.miller.tpics.data.Media
import com.miller.tpics.data.Prefs
import com.miller.tpics.data.Store
import com.miller.tpics.data.Suggestion
import com.miller.tpics.data.Suggestions
import com.miller.tpics.data.UploadItem
import com.miller.tpics.data.UploadQueue
import kotlinx.coroutines.launch

/* ───────────────────── Módulo: respaldo automático ───────────────────── */

private data class Review(val folder: String, val group: String)

@Composable
fun AutoScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val folders by AutoBackup.folders.collectAsState()
    val suggestions by Suggestions.items.collectAsState()
    val queue by UploadQueue.items.collectAsState()
    val media by Store.media.collectAsState()
    val tags by Store.tags.collectAsState()
    val byId = remember(media) { media.associateBy { it.id } }

    // Solo cuentan las sugerencias de fotos que siguen existiendo.
    val pending = remember(suggestions, byId) { suggestions.values.filter { byId[it.mediaId]?.trashedAt == 0L } }

    var adding by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<Review?>(null) }
    var removing by remember { mutableStateOf<AutoFolder?>(null) }

    val current = review
    if (current != null) {
        val inGroup = pending.filter { it.folder == current.folder && it.group == current.group }
        ReviewScreen(current, inGroup, byId) { review = null }
        return
    }

    val names = remember(folders, pending) { (folders.map { it.name } + pending.map { it.folder }).distinct() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { Bar("Respaldo automático", if (pending.isNotEmpty()) "${pending.size} fotos por revisar" else null) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("Agregar carpeta") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { pad ->
        if (names.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad)) {
                EmptyState(
                    Icons.Outlined.Sync, "Ninguna carpeta con respaldo automático",
                    "Elige una carpeta del teléfono y cada foto nueva que entre ahí se subirá sola.",
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(names, key = { it }) { name ->
                val folder = folders.firstOrNull { it.name == name }
                val mine = pending.filter { it.folder == name }
                val grouped = mine.count { it.labels.isNotEmpty() }
                val uploading = queue.count {
                    it.auto && it.folder == name && (it.status == UploadItem.QUEUED || it.status == UploadItem.RUNNING)
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            val fixed = folder?.tagIds?.mapNotNull { id -> tags.firstOrNull { it.id == id }?.name } ?: emptyList()
                            Text(
                                when {
                                    folder == null -> "Respaldo automático desactivado"
                                    fixed.isNotEmpty() && folder.suggest -> "Etiqueta fija: ${fixed.joinToString(", ")} · con sugerencias"
                                    fixed.isNotEmpty() -> "Etiqueta fija: ${fixed.joinToString(", ")}"
                                    folder.suggest -> "Etiquetas sugeridas para revisar"
                                    else -> "Sin etiquetas"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (folder != null) {
                            IconButton(onClick = { removing = folder }) {
                                Icon(Icons.Outlined.Delete, "Dejar de respaldar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        buildString {
                            append("${mine.size} fotos detectadas · $grouped agrupadas")
                            if (uploading > 0) append(" · $uploading subiendo")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (mine.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val groups = mine.groupBy { it.group }.toList()
                        .sortedWith(compareBy<Pair<String, List<Suggestion>>> { it.first == Suggestion.NONE }.thenByDescending { it.second.size })
                    groups.forEach { (group, list) ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { review = Review(name, group) }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Vista previa de las primeras fotos del grupo
                            list.take(3).forEach { s ->
                                val m = byId[s.mediaId]
                                Box(
                                    Modifier.padding(end = 4.dp).size(44.dp).clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    if (m != null) {
                                        val req = rememberThumb(m)
                                        if (req != null) AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(group, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    if (list.size == 1) "1 foto" else "${list.size} fotos",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { review = Review(name, group) }) { Text("Ver") }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        AddAutoFolder(
            already = folders.map { it.bucketId }.toSet(),
            onDismiss = { adding = false },
        ) { folder ->
            adding = false
            AutoBackup.set(folder)
            scope.launch {
                val n = runCatching { AutoBackup.scan(ctx.applicationContext) }.getOrDefault(0)
                Store.say(
                    when {
                        !Store.canUpload -> "Carpeta guardada. Se respaldará cuando tu cuenta esté activa"
                        n > 0 -> "$n archivos en cola para respaldar"
                        else -> "Activado. Esa carpeta ya estaba respaldada"
                    }
                )
            }
        }
    }

    removing?.let { f ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("¿Dejar de respaldar “${f.name}”?") },
            text = { Text("Lo que ya se subió se queda en T-Pics. Solo dejan de subirse las fotos nuevas.") },
            confirmButton = { TextButton(onClick = { AutoBackup.remove(f.bucketId); removing = null }) { Text("Desactivar") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } },
        )
    }
}

/* ── Elegir carpeta y su configuración ── */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddAutoFolder(already: Set<String>, onDismiss: () -> Unit, onDone: (AutoFolder) -> Unit) {
    val ctx = LocalContext.current
    val deviceItems by DeviceCache.items.collectAsState()
    val tags by Store.tags.collectAsState()
    var chosen by remember { mutableStateOf<LocalFolder?>(null) }
    var fixed by remember { mutableStateOf(setOf<String>()) }
    var suggest by remember { mutableStateOf(true) }
    val granted = remember { Device.hasPermission(ctx) }

    LaunchedEffect(granted) {
        if (granted && DeviceCache.items.value == null) DeviceCache.items.value = Device.load(ctx)
    }

    val folder = chosen
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (folder == null) "Elige una carpeta" else folder.name) },
        text = {
            val list = deviceItems
            when {
                !granted -> Text("Primero entra a “Subir desde el teléfono” y da permiso para ver tus fotos.")
                list == null -> Text("Buscando carpetas…")
                folder == null -> {
                    val folders = remember(list) { Device.folders(list).filter { it.id !in already } }
                    if (folders.isEmpty()) {
                        Text("No hay más carpetas para agregar.")
                    } else {
                        LazyColumn(Modifier.heightIn(max = 380.dp)) {
                            items(folders, key = { it.id }) { f ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                        chosen = f
                                        // Si ya existe una etiqueta con el nombre de la carpeta, se propone como fija.
                                        tags.firstOrNull { it.name.equals(f.name, ignoreCase = true) }?.let { fixed = setOf(it.id) }
                                    }.padding(vertical = 10.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(f.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "${f.items.size} elementos", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Todo lo que hay en esta carpeta y lo que entre después se subirá solo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Etiqueta fija (opcional)", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.forEach { t ->
                            FilterChip(
                                selected = t.id in fixed,
                                onClick = { fixed = if (t.id in fixed) fixed - t.id else fixed + t.id },
                                label = { Text(t.name) },
                                leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(tagColor(t.color))) },
                            )
                        }
                    }
                    if (tags.none { it.name.equals(folder.name, ignoreCase = true) }) {
                        TextButton(onClick = { fixed = fixed + Store.createTag(folder.name).id }) {
                            Text("Crear etiqueta “${folder.name}”")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sugerir etiquetas", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Agrupa las fotos por contenido y lugar para que las apruebes. Nada se etiqueta sin tu visto bueno.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = suggest, onCheckedChange = { suggest = it })
                    }
                }
            }
        },
        confirmButton = {
            if (folder != null) {
                TextButton(onClick = { onDone(AutoFolder(folder.id, folder.name, fixed.toList(), suggest)) }) { Text("Activar") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (folder != null) chosen = null else onDismiss() }) {
                Text(if (folder != null) "Atrás" else "Cancelar")
            }
        },
    )
}

/* ── Revisar un grupo sugerido ── */

/** Quita la etiqueta ya resuelta de la sugerencia; si quedan otras (p. ej. el lugar), la foto pasa a ese grupo. */
private fun resolve(items: List<Suggestion>, group: String) {
    val done = ArrayList<String>()
    items.forEach { s ->
        val rest = s.labels.filter { it != group }
        if (rest.isEmpty() || group == Suggestion.NONE) done.add(s.mediaId) else Suggestions.put(s.copy(labels = rest))
    }
    if (done.isNotEmpty()) Suggestions.remove(done)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewScreen(review: Review, photos: List<Suggestion>, byId: Map<String, Media>, onClose: () -> Unit) {
    val shell = LocalShell.current
    val columns by Prefs.columns.collectAsState()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var picking by remember { mutableStateOf(false) }
    val hasLabel = review.group != Suggestion.NONE

    BackHandler { if (selected.isNotEmpty()) selected = emptySet() else onClose() }

    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val chosen = photos.filter { it.mediaId in selected }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (selected.isEmpty()) review.group else "${selected.size} seleccionadas")
                        Text(
                            if (selected.isEmpty()) "${photos.size} fotos · ${review.folder}" else "Toca más fotos o reasígnalas",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (selected.isNotEmpty()) selected = emptySet() else onClose() }) {
                        Icon(Icons.Filled.Close, "Cerrar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected.isNotEmpty()) {
                        OutlinedButton(onClick = { resolve(chosen, review.group); selected = emptySet() }) {
                            Text(if (hasLabel) "No pertenecen" else "Dejar sin etiqueta")
                        }
                        Button(onClick = { picking = true }, modifier = Modifier.weight(1f).height(48.dp)) {
                            Text("Reasignar ${selected.size}")
                        }
                    } else if (hasLabel) {
                        OutlinedButton(onClick = { resolve(photos, review.group) }) { Text("Descartar") }
                        Button(
                            onClick = {
                                val tag = Store.createTag(review.group)
                                Store.addTags(photos.map { it.mediaId }, listOf(tag.id))
                                resolve(photos, review.group)
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("Aceptar las ${photos.size}") }
                    } else {
                        OutlinedButton(onClick = { resolve(photos, review.group) }) { Text("Dejar sin etiqueta") }
                        Button(onClick = { selected = photos.map { it.mediaId }.toSet(); picking = true }, modifier = Modifier.weight(1f).height(48.dp)) {
                            Text("Etiquetar todas")
                        }
                    }
                }
            }
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceAtMost(4)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            items(photos, key = { it.mediaId }) { s ->
                val m = byId[s.mediaId]
                val isSelected = s.mediaId in selected
                Box(
                    Modifier.aspectRatio(1f)
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { selected = if (isSelected) selected - s.mediaId else selected + s.mediaId }
                ) {
                    Box(
                        Modifier.fillMaxSize().padding(if (isSelected) 10.dp else 0.dp)
                            .clip(RoundedCornerShape(if (isSelected) 10.dp else 3.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        if (m != null) {
                            val req = rememberThumb(m)
                            if (req != null) AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                    if (isSelected) {
                        Icon(
                            Icons.Filled.CheckCircle, null,
                            Modifier.padding(4.dp).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Box(
                            Modifier.padding(6.dp).size(18.dp).clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.25f))
                                .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                        )
                    }
                    // Ver la foto en grande sin salir de la revisión
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(4.dp).size(26.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable { shell.openViewer(photos.map { it.mediaId }, photos.indexOf(s), false) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.OpenInFull, "Ver en grande", Modifier.size(15.dp), tint = Color.White)
                    }
                }
            }
        }
    }

    if (picking) {
        TagPickerSheet(
            title = "Reasignar ${chosen.size}",
            initial = emptySet(),
            confirm = "Guardar",
            onDismiss = { picking = false },
        ) { tagIds ->
            if (tagIds.isNotEmpty()) Store.addTags(chosen.map { it.mediaId }, tagIds)
            resolve(chosen, review.group)
            picking = false
            selected = emptySet()
        }
    }
}

/* ───────────────────────── Carpeta privada ───────────────────────── */

private fun unlock(activity: FragmentActivity, onOk: () -> Unit, onFail: (String) -> Unit) {
    val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
        onFail("Primero configura un bloqueo de pantalla (PIN, patrón o huella) en tu teléfono")
        return
    }
    val prompt = BiometricPrompt(
        activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail("")
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Carpeta privada")
            .setSubtitle("Confirma que eres tú")
            .setAllowedAuthenticators(allowed)
            .build()
    )
}

@Composable
fun PrivateScreen() {
    val activity = LocalContext.current as? FragmentActivity
    var open by remember { mutableStateOf(false) }

    fun ask() {
        if (activity == null) return
        unlock(activity, onOk = { open = true }, onFail = { if (it.isNotEmpty()) Store.say(it) })
    }

    LaunchedEffect(Unit) { ask() }

    if (open) {
        GalleryScreen(
            "Carpeta privada", locked = true,
            empty = "Selecciona fotos en la galería y elige “Mover a carpeta privada”",
        ) { it.priv && it.trashedAt == 0L }
    } else {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { Bar("Carpeta privada") }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                EmptyState(
                    Icons.Outlined.Lock, "Carpeta protegida",
                    "Se abre con tu huella o el PIN del teléfono. Lo que guardes aquí no aparece en la galería ni en las búsquedas.",
                ) { Button(onClick = { ask() }) { Text("Desbloquear") } }
            }
        }
    }
}
