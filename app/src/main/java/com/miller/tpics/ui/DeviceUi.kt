package com.miller.tpics.ui

import android.Manifest
import android.os.Build
import android.app.Activity
import androidx.activity.result.IntentSenderRequest
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.miller.tpics.data.Device
import com.miller.tpics.data.LocalItem
import com.miller.tpics.data.Prefs
import com.miller.tpics.data.Store
import com.miller.tpics.data.UploadItem
import com.miller.tpics.data.UploadQueue
import kotlinx.coroutines.flow.MutableStateFlow

/** Lista de fotos del teléfono, compartida entre la pantalla de carpetas y la de cada carpeta. */
object DeviceCache {
    val items = MutableStateFlow<List<LocalItem>?>(null)
}

@Composable
private fun localThumb(item: LocalItem): ImageRequest {
    val ctx = LocalContext.current
    return remember(item.key) {
        ImageRequest.Builder(ctx).data(item.uri).size(300).memoryCacheKey("l:" + item.key).build()
    }
}

/* ───────────────────── Carpetas del teléfono ───────────────────── */

@Composable
fun DeviceScreen() {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(Device.hasPermission(ctx)) }
    val deviceItems by DeviceCache.items.collectAsState()
    val media by Store.media.collectAsState()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = Device.hasPermission(ctx)
    }

    fun ask() {
        val perms = Device.permissions().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        launcher.launch(perms.toTypedArray())
    }

    LaunchedEffect(granted) {
        if (granted) DeviceCache.items.value = Device.load(ctx)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { Bar("Subir desde el teléfono", "Elige una carpeta") },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val list = deviceItems
            when {
                !granted -> EmptyState(
                    Icons.Outlined.PhoneAndroid,
                    "Permite ver tus fotos",
                    "T-Pics necesita permiso para mostrar las fotos y videos de tu teléfono. Solo se sube lo que tú elijas.",
                ) { Button(onClick = { ask() }) { Text("Dar permiso") } }

                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                list.isEmpty() -> EmptyState(Icons.Outlined.PhoneAndroid, "No se encontraron fotos en el teléfono")

                else -> {
                    val folders = remember(list) { Device.folders(list) }
                    val sigs = remember(media) { Store.backedSigs }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item(key = "all") {
                            FolderCard("Todo el teléfono", list, sigs) {
                                shell.nav.go(Screen.Folder(null, "Todo el teléfono"))
                            }
                        }
                        items(folders, key = { it.id }) { f ->
                            FolderCard(f.name, f.items, sigs) { shell.nav.go(Screen.Folder(f.id, f.name)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderCard(name: String, items: List<LocalItem>, sigs: Set<String>, onClick: () -> Unit) {
    val pending = remember(items, sigs) { items.count { it.sig !in sigs } }
    Column(Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            items.firstOrNull()?.let { cover ->
                AsyncImage(localThumb(cover), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (pending == 0) {
                Icon(
                    Icons.Filled.CloudDone, "Todo respaldado",
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(3.dp),
                    tint = Color.White,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            if (pending == 0) "${items.size} · todo respaldado" else "${items.size} · $pending sin respaldar",
            style = MaterialTheme.typography.bodySmall,
            color = if (pending == 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}

/* ───────────────────── Dentro de una carpeta ───────────────────── */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderScreen(bucketId: String?, name: String) {
    val shell = LocalShell.current
    val all by DeviceCache.items.collectAsState()
    val media by Store.media.collectAsState()
    val columns by Prefs.columns.collectAsState()
    val list = remember(all, bucketId) {
        (all ?: emptyList()).filter { bucketId == null || it.bucketId == bucketId }
    }
    val sigs = remember(media) { Store.backedSigs }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var picking by remember { mutableStateOf(false) }
    val pendingItems = remember(list, sigs) { list.filter { it.sig !in sigs } }
    val backedItems = remember(list, sigs) { list.filter { it.sig in sigs } }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmFree by remember { mutableStateOf(false) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Store.say("Espacio liberado en el teléfono")
            scope.launch { DeviceCache.items.value = Device.load(ctx) }
        }
    }

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selected.size} elegidas") },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, "Cancelar") }
                    },
                    actions = {
                        IconButton(onClick = { selected = pendingItems.map { it.key }.toSet() }) {
                            Icon(Icons.Outlined.SelectAll, "Seleccionar todo")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                )
            } else {
                Bar(name, "${list.size} elementos · ${pendingItems.size} sin respaldar") {
                    if (backedItems.isNotEmpty()) {
                        IconButton(onClick = { confirmFree = true }) {
                            Icon(Icons.Outlined.CleaningServices, "Liberar espacio")
                        }
                    }
                    if (pendingItems.isNotEmpty()) {
                        TextButton(onClick = { selected = pendingItems.map { it.key }.toSet() }) {
                            Text("Elegir todo")
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Button(
                        onClick = {
                            when {
                                Store.apiUrl.value.isEmpty() -> Store.say("Falta configurar el servidor")
                                !Store.canUpload -> Store.say("Tu cuenta aún no está activa para subir")
                                else -> picking = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp).height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Outlined.CloudUpload, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Subir ${selected.size}")
                    }
                }
            }
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            items(list, key = { it.key }) { item ->
                val backed = item.sig in sigs
                val isSelected = item.key in selected
                Box(
                    Modifier.aspectRatio(1f)
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .combinedClickable(
                            onClick = {
                                if (backed) Store.say("Ya está respaldada")
                                else selected = if (isSelected) selected - item.key else selected + item.key
                            },
                            onLongClick = { if (!backed) selected = selected + item.key },
                        )
                ) {
                    Box(
                        Modifier.fillMaxSize().padding(if (isSelected) 10.dp else 0.dp)
                            .clip(RoundedCornerShape(if (isSelected) 10.dp else 3.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        AsyncImage(
                            localThumb(item), null,
                            Modifier.fillMaxSize().alpha(if (backed) 0.45f else 1f),
                            contentScale = ContentScale.Crop,
                        )
                        if (item.isVideo) {
                            Row(
                                Modifier.align(Alignment.BottomEnd).padding(5.dp)
                                    .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.55f))
                                    .padding(start = 4.dp, end = 7.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(14.dp), tint = Color.White)
                                if (item.duration > 0) {
                                    Text(duration(item.duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    when {
                        backed -> Icon(
                            Icons.Filled.CloudDone, "Respaldada",
                            Modifier.padding(6.dp).size(20.dp), tint = MaterialTheme.colorScheme.secondary,
                        )
                        isSelected -> Icon(
                            Icons.Filled.CheckCircle, null,
                            Modifier.padding(4.dp).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        else -> Box(
                            Modifier.padding(6.dp).size(18.dp).clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.25f))
                                .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                        )
                    }
                }
            }
        }
    }

    if (confirmFree) {
        val bytes = backedItems.sumOf { it.size }
        AlertDialog(
            onDismissRequest = { confirmFree = false },
            title = { Text("Liberar espacio") },
            text = {
                Text(
                    "Se borrarán del teléfono ${backedItems.size} archivos de esta carpeta que ya están respaldados " +
                        "(${sizeText(bytes)}). Siguen guardados en T-Pics y puedes descargarlos cuando quieras."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFree = false
                    try {
                        val sender = Device.deleteRequest(ctx, backedItems.map { it.uri })
                        if (sender != null) {
                            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        } else {
                            Store.say("Espacio liberado en el teléfono")
                            scope.launch { DeviceCache.items.value = Device.load(ctx) }
                        }
                    } catch (e: Exception) {
                        Store.say("No se pudo liberar espacio en este teléfono")
                    }
                }) { Text("Borrar del teléfono") }
            },
            dismissButton = { TextButton(onClick = { confirmFree = false }) { Text("Cancelar") } },
        )
    }

    if (picking) {
        TagPickerSheet(
            title = "¿Con qué etiquetas?",
            initial = emptySet(),
            confirm = "Subir ${selected.size}",
            onDismiss = { picking = false },
        ) { chosen ->
            val toUpload = list.filter { it.key in selected }
            UploadQueue.enqueue(toUpload, chosen.toList())
            picking = false
            selected = emptySet()
            shell.nav.go(Screen.Uploads)
        }
    }
}

/* ───────────────────────────── Subidas ───────────────────────────── */

@Composable
fun UploadsScreen() {
    val shell = LocalShell.current
    val queue by UploadQueue.items.collectAsState()
    val done = queue.count { it.status == UploadItem.DONE || it.status == UploadItem.DUPLICATE }
    val failed = queue.count { it.status == UploadItem.ERROR }
    val pending = queue.count { it.status == UploadItem.QUEUED || it.status == UploadItem.RUNNING }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Bar(
                "Subidas",
                if (queue.isEmpty()) null
                else if (pending > 0) "$pending pendientes · $done listas" else "$done listas" + if (failed > 0) " · $failed con error" else "",
            )
        },
    ) { pad ->
        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad)) {
                EmptyState(
                    Icons.Outlined.CloudUpload, "No hay subidas",
                    "Elige fotos de tu teléfono para respaldarlas.",
                ) { Button(onClick = { shell.nav.go(Screen.Device) }) { Text("Elegir fotos") } }
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (pending > 0) {
                LinearProgressIndicator(
                    progress = { if (queue.isEmpty()) 0f else (queue.size - pending).toFloat() / queue.size },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                Text(
                    "Puedes salir de la app: la subida sigue en segundo plano.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed > 0) Button(onClick = { UploadQueue.retryFailed() }) { Text("Reintentar $failed") }
                if (done + failed > 0) OutlinedButton(onClick = { UploadQueue.clearFinished() }) { Text("Limpiar lista") }
            }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(queue.asReversed(), key = { it.id }) { item -> UploadRow(item) }
            }
        }
    }
}

@Composable
private fun UploadRow(item: UploadItem) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val req = remember(item.uri) { ImageRequest.Builder(ctx).data(android.net.Uri.parse(item.uri)).size(160).build() }
        AsyncImage(
            req, null,
            Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            when (item.status) {
                UploadItem.RUNNING -> LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
                UploadItem.ERROR -> Text(
                    item.error.ifEmpty { "No se pudo subir" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    when (item.status) {
                        UploadItem.DONE -> "Respaldada · ${sizeText(item.size)}"
                        UploadItem.DUPLICATE -> "Ya estaba respaldada"
                        else -> "En espera · ${sizeText(item.size)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when (item.status) {
            UploadItem.DONE, UploadItem.DUPLICATE ->
                Icon(Icons.Filled.CloudDone, null, tint = MaterialTheme.colorScheme.secondary)
            UploadItem.ERROR -> Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            UploadItem.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else -> {}
        }
    }
}
