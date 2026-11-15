package com.miller.tpics.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.miller.tpics.data.Auth
import com.miller.tpics.data.Files
import com.miller.tpics.data.Media
import com.miller.tpics.data.Prefs
import com.miller.tpics.data.Store
import com.miller.tpics.data.friendly
import kotlinx.coroutines.launch
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/* ───────────────────────────── Utilidades ───────────────────────────── */

private val ES = Locale("es")
private val DAY_FMT = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES)
private val DAY_YEAR_FMT = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", ES)
private val FULL_FMT = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", ES)

fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

fun dayLabel(day: LocalDate, today: LocalDate): String = when {
    day == today -> "Hoy"
    day == today.minusDays(1) -> "Ayer"
    day.year == today.year -> day.format(DAY_FMT).replaceFirstChar { it.uppercase() }
    else -> day.format(DAY_YEAR_FMT)
}

fun fullDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(FULL_FMT)

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%d KB".format(bytes shr 10)
    else -> "$bytes B"
}

private sealed interface Cell {
    data class Header(val label: String, val key: String) : Cell
    data class Item(val m: Media, val index: Int) : Cell
}

private fun buildCells(items: List<Media>): List<Cell> {
    val out = ArrayList<Cell>(items.size + 64)
    val today = LocalDate.now()
    var last: LocalDate? = null
    items.forEachIndexed { i, m ->
        val day = dayOf(m.date)
        if (day != last) {
            out.add(Cell.Header(dayLabel(day, today), "h$day"))
            last = day
        }
        out.add(Cell.Item(m, i))
    }
    return out
}

/* ───────────────────────────── Miniatura ───────────────────────────── */

@Composable
fun rememberThumb(m: Media): ImageRequest? {
    val ctx = LocalContext.current
    val api by Store.apiUrl.collectAsState()
    return remember(m.id, m.thumb, m.ref, api) {
        val ref = m.thumb.ifEmpty { if (!m.isVideo) m.ref else "" }
        if (api.isEmpty() || ref.isEmpty()) null
        else ImageRequest.Builder(ctx)
            .data(Store.fileUrl(ref))
            .memoryCacheKey("t:" + m.id + ":" + ref.takeLast(8))
            .diskCacheKey(ref)
            .size(360)
            .build()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThumbCell(
    m: Media,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val inset by animateDpAsState(if (selected) 10.dp else 0.dp, label = "inset")
    Box(
        Modifier
            .aspectRatio(1f)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier.fillMaxSize().padding(inset)
                .clip(RoundedCornerShape(if (selected) 10.dp else 3.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            val req = rememberThumb(m)
            if (req != null) {
                AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (m.isVideo) {
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(start = 4.dp, end = 7.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(14.dp), tint = Color.White)
                    if (m.dur > 0) {
                        Text(duration(m.dur), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (m.fav) {
                Icon(
                    Icons.Filled.Favorite, null,
                    Modifier.align(Alignment.BottomStart).padding(6.dp).size(14.dp), tint = Color.White,
                )
            }
        }
        if (selecting) {
            if (selected) {
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
        }
    }
}

/* ───────────────────────────── Galería ───────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    title: String,
    home: Boolean = false,
    trash: Boolean = false,
    empty: String = "Aún no hay fotos",
    filter: (Media) -> Boolean,
) {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by Store.media.collectAsState()
    val tags by Store.tags.collectAsState()
    val columns by Prefs.columns.collectAsState()
    val syncing by Store.syncing.collectAsState()
    val api by Store.apiUrl.collectAsState()
    val admin by Store.isAdmin.collectAsState()
    val account by Store.account.collectAsState()

    var chip by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val shown = remember(all, chip, query, tags) {
        val q = query.trim().lowercase()
        val matching = if (q.isEmpty()) emptySet() else tags.filter { it.name.lowercase().contains(q) }.map { it.id }.toSet()
        all.filter { m ->
            filter(m) && (chip == null || chip in m.tags) &&
                (q.isEmpty() || m.name.lowercase().contains(q) || m.tags.any { it in matching })
        }
    }
    val cells = remember(shown) { buildCells(shown) }
    val selecting = selected.isNotEmpty()

    BackHandler(enabled = selecting || searching) {
        if (selecting) selected = emptySet() else { searching = false; query = "" }
    }

    fun act(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { block() } catch (e: Exception) { Store.say(e.friendly()) } finally { busy = false }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selected.size}") },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, "Cancelar") }
                    },
                    actions = {
                        if (trash) {
                            IconButton(onClick = { Store.restore(selected); selected = emptySet() }) {
                                Icon(Icons.Outlined.RestoreFromTrash, "Restaurar")
                            }
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(Icons.Outlined.DeleteForever, "Borrar para siempre")
                            }
                        } else {
                            IconButton(onClick = { picking = true }) { Icon(Icons.Outlined.Label, "Etiquetar") }
                            IconButton(onClick = { Store.setFav(selected, true); selected = emptySet() }) {
                                Icon(Icons.Outlined.FavoriteBorder, "Favorito")
                            }
                            IconButton(onClick = { Store.trash(selected); selected = emptySet() }) {
                                Icon(Icons.Outlined.Delete, "A la papelera")
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Más") }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Seleccionar todo") },
                                        onClick = { menu = false; selected = shown.map { it.id }.toSet() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Descargar al teléfono") },
                                        onClick = {
                                            menu = false
                                            val list = shown.filter { it.id in selected }
                                            selected = emptySet()
                                            act {
                                                list.forEach { Files.saveToDevice(ctx, it) }
                                                Store.say("${list.size} guardadas en la carpeta T-Pics")
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Compartir") },
                                        onClick = {
                                            menu = false
                                            val list = shown.filter { it.id in selected }.take(20)
                                            selected = emptySet()
                                            act { Files.share(ctx, list) }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Quitar de favoritos") },
                                        onClick = { menu = false; Store.setFav(selected, false); selected = emptySet() },
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                )
            } else if (searching) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            placeholder = { Text("Buscar por etiqueta o nombre") },
                            singleLine = true, modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                            shape = RoundedCornerShape(28.dp),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cerrar búsqueda")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            } else {
                Bar(
                    title = title,
                    subtitle = if (shown.isNotEmpty()) "${shown.size} elementos" else null,
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Buscar") }
                        IconButton(onClick = { Prefs.setColumns(if (columns >= 6) 3 else columns + 1) }) {
                            Icon(Icons.Outlined.GridView, "Tamaño de cuadrícula")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (home && !selecting) {
                ExtendedFloatingActionButton(
                    onClick = { shell.nav.go(Screen.Device) },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text("Subir") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (syncing || busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

            if (home && api.isEmpty()) {
                Notice(
                    if (admin) "Falta conectar el servidor" else "La app aún se está configurando",
                    if (admin) "Pega la dirección de tu Worker de Cloudflare para poder ver y subir fotos."
                    else "Vuelve a intentarlo en un rato.",
                    if (admin) "Configurar" else null,
                ) { shell.nav.go(Screen.Admin) }
            } else if (home && !admin && !account.active) {
                val pay by Store.paymentInfo.collectAsState()
                Notice(
                    if (account.status == "suspended") "Tu cuenta está suspendida"
                    else if (account.status == "active") "Tu plan venció"
                    else "Tu cuenta está pendiente de activación",
                    pay.ifEmpty { "Cuando se confirme tu pago podrás subir fotos." },
                    "Ya pagué, actualizar",
                ) { Store.syncNow() }
            }

            if (home && tags.isNotEmpty() && !selecting) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    items(tags, key = { it.id }) { t ->
                        FilterChip(
                            selected = chip == t.id,
                            onClick = { chip = if (chip == t.id) null else t.id },
                            label = { Text(t.name) },
                            leadingIcon = {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(tagColor(t.color)))
                            },
                        )
                    }
                }
            }

            if (shown.isEmpty()) {
                EmptyState(
                    Icons.Outlined.PhotoLibrary,
                    if (query.isNotEmpty() || chip != null) "Sin resultados" else empty,
                    if (home && query.isEmpty() && chip == null) "Toca “Subir” para respaldar las fotos de tu teléfono." else null,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        cells,
                        key = { c -> when (c) { is Cell.Header -> c.key; is Cell.Item -> c.m.id } },
                        span = { c -> if (c is Cell.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                        contentType = { c -> if (c is Cell.Header) 0 else 1 },
                    ) { c ->
                        when (c) {
                            is Cell.Header -> Text(
                                c.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 14.dp, top = 18.dp, bottom = 8.dp),
                            )
                            is Cell.Item -> ThumbCell(
                                m = c.m,
                                selected = c.m.id in selected,
                                selecting = selecting,
                                onClick = {
                                    if (selecting) {
                                        selected = if (c.m.id in selected) selected - c.m.id else selected + c.m.id
                                    } else {
                                        shell.openViewer(shown.map { it.id }, c.index, trash)
                                    }
                                },
                                onLongClick = { selected = selected + c.m.id },
                            )
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        TagPickerSheet(
            title = "Etiquetar ${selected.size}",
            initial = emptySet(),
            confirm = "Aplicar",
            onDismiss = { picking = false },
        ) { chosen ->
            Store.addTags(selected, chosen)
            picking = false
            selected = emptySet()
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Borrar para siempre?") },
            text = { Text("Se eliminarán ${selected.size} elementos. Esto no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = { Store.purge(selected); selected = emptySet(); confirmDelete = false }) {
                    Text("Borrar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun Notice(title: String, detail: String, action: String?, onAction: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

/* ───────────────────────────── Visor ───────────────────────────── */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerOverlay(request: ViewerRequest, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by Store.media.collectAsState()
    val tags by Store.tags.collectAsState()
    var hidden by remember { mutableStateOf(setOf<String>()) }
    val byId = remember(all) { all.associateBy { it.id } }
    val list = remember(byId, hidden) { request.ids.filter { it !in hidden }.mapNotNull { byId[it] } }

    var picking by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    BackHandler { onClose() }

    if (list.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val pager = rememberPagerState(initialPage = request.index.coerceIn(0, list.size - 1)) { list.size }
    val current = list[pager.currentPage.coerceIn(0, list.size - 1)]

    fun act(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { block() } catch (e: Exception) { Store.say(e.friendly()) } finally { busy = false }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, key = { list[it].id }, modifier = Modifier.fillMaxSize()) { page ->
            val m = list[page]
            if (m.isVideo) {
                VideoPage(m, active = pager.currentPage == page)
            } else {
                val zoom = rememberZoomState()
                val req = remember(m.id, m.ref) {
                    ImageRequest.Builder(ctx)
                        .data(Store.fullUrl(m))
                        .diskCacheKey(m.ref.ifEmpty { "full:" + m.id })
                        .placeholderMemoryCacheKey("t:" + m.id + ":" + m.thumb.ifEmpty { m.ref }.takeLast(8))
                        .build()
                }
                AsyncImage(
                    req, null,
                    modifier = Modifier.fillMaxSize().zoomable(zoom),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        // Barra superior
        Row(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = Color.White) }
            Text(
                fullDate(current.date), color = Color.White,
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
            )
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            IconButton(onClick = { info = true }) { Icon(Icons.Outlined.Info, "Detalles", tint = Color.White) }
        }

        // Etiquetas + acciones
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .navigationBarsPadding().padding(top = 24.dp),
        ) {
            val mine = tags.filter { it.id in current.tags }
            if (mine.isNotEmpty() && !request.inTrash) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(mine, key = { it.id }) { t ->
                        Row(
                            Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.16f))
                                .clickable { picking = true }.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(tagColor(t.color)))
                            Spacer(Modifier.width(6.dp))
                            Text(t.name, color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (request.inTrash) {
                    ViewerAction(Icons.Outlined.RestoreFromTrash, "Restaurar") {
                        Store.restore(listOf(current.id)); hidden = hidden + current.id
                    }
                    ViewerAction(Icons.Outlined.DeleteForever, "Borrar") {
                        Store.purge(listOf(current.id)); hidden = hidden + current.id
                    }
                } else {
                    ViewerAction(
                        if (current.fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Favorito",
                        tint = if (current.fav) Coral else Color.White,
                    ) { Store.setFav(listOf(current.id), !current.fav) }
                    ViewerAction(Icons.Outlined.Label, "Etiquetas") { picking = true }
                    ViewerAction(Icons.Outlined.Download, "Descargar") {
                        act { Files.saveToDevice(ctx, current); Store.say("Guardada en la carpeta T-Pics") }
                    }
                    ViewerAction(Icons.Outlined.Share, "Compartir") { act { Files.share(ctx, listOf(current)) } }
                    ViewerAction(Icons.Outlined.Delete, "Papelera") {
                        Store.trash(listOf(current.id)); hidden = hidden + current.id
                    }
                }
            }
        }
    }

    if (picking) {
        TagPickerSheet(
            title = "Etiquetas",
            initial = current.tags.toSet(),
            confirm = "Guardar",
            onDismiss = { picking = false },
        ) { chosen ->
            Store.setTags(listOf(current.id), chosen)
            picking = false
        }
    }

    if (info) {
        AlertDialog(
            onDismissRequest = { info = false },
            title = { Text("Detalles") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (current.name.isNotEmpty()) Text(current.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(fullDate(current.date))
                    if (current.size > 0) Text(sizeText(current.size))
                    if (current.w > 0 && current.h > 0) Text("${current.w} × ${current.h} px")
                    if (current.isVideo && current.dur > 0) Text("Duración ${duration(current.dur)}")
                    val names = tags.filter { it.id in current.tags }.joinToString(", ") { it.name }
                    Text(if (names.isEmpty()) "Sin etiquetas" else "Etiquetas: $names")
                }
            },
            confirmButton = { TextButton(onClick = { info = false }) { Text("Cerrar") } },
        )
    }
}

@Composable
private fun ViewerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint)
        Spacer(Modifier.height(3.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun VideoPage(m: Media, active: Boolean) {
    var playing by remember(m.id) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) playing = false }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (playing) {
            VideoPlayer(m)
        } else {
            val req = rememberThumb(m)
            if (req != null) AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Box(
                Modifier.size(76.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .clickable { playing = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, "Reproducir", Modifier.size(44.dp), tint = Color.White)
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPlayer(m: Media) {
    val ctx = LocalContext.current
    val token by produceState<String?>(null, m.id) {
        value = runCatching { Auth.token() }.getOrNull()
    }
    val t = token
    if (t == null) {
        CircularProgressIndicator(color = Color.White)
        return
    }
    val player = remember(m.id, t) {
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $t"))
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(http))
            .build().apply {
                setMediaItem(MediaItem.fromUri(Store.fullUrl(m)))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { c -> PlayerView(c).apply { this.player = player } },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}
