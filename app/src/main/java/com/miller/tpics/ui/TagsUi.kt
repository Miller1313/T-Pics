package com.miller.tpics.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.miller.tpics.data.Store
import com.miller.tpics.data.Tag

/** Hoja inferior para elegir una o varias etiquetas, o crear una nueva. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagPickerSheet(
    title: String,
    initial: Set<String>,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val tags by Store.tags.collectAsState()
    var chosen by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf("") }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun create() {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val tag = Store.createTag(clean)
        chosen = chosen + tag.id
        name = ""
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            if (tags.isEmpty()) {
                Text(
                    "Todavía no tienes etiquetas. Crea la primera aquí abajo.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { t ->
                        FilterChip(
                            selected = t.id in chosen,
                            onClick = { chosen = if (t.id in chosen) chosen - t.id else chosen + t.id },
                            label = { Text(t.name) },
                            leadingIcon = {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(tagColor(t.color)))
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(30) },
                    placeholder = { Text("Nueva etiqueta") }, singleLine = true,
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = { create() }, enabled = name.isNotBlank()) {
                    Icon(Icons.Outlined.Add, "Crear etiqueta")
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    // Si quedó un nombre escrito sin confirmar, también se crea.
                    val pendingName = name.trim()
                    val result = if (pendingName.isNotEmpty()) chosen + Store.createTag(pendingName).id else chosen
                    onConfirm(result)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text(confirm) }
        }
    }
}

/* ─────────────────────────── Pantalla de etiquetas ─────────────────────────── */

@Composable
fun TagsScreen() {
    val shell = LocalShell.current
    val tags by Store.tags.collectAsState()
    val media by Store.media.collectAsState()
    val counts = remember(media) {
        val c = HashMap<String, Int>()
        media.forEach { m -> if (m.trashedAt == 0L) m.tags.forEach { c[it] = (c[it] ?: 0) + 1 } }
        c
    }
    var editing by remember { mutableStateOf<Tag?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Tag?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { Bar("Etiquetas", if (tags.isNotEmpty()) "${tags.size} etiquetas" else null) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("Nueva") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { pad ->
        if (tags.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad)) {
                EmptyState(
                    Icons.Outlined.Label, "Aún no hay etiquetas",
                    "Las etiquetas te ayudan a clasificar: Familia, Viajes, Trabajo…",
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(tags, key = { it.id }) { t ->
                    var menu by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { shell.nav.go(Screen.TagView(t.id)) }
                            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(tagColor(t.color)))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${counts[t.id] ?: 0} elementos",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Opciones") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Editar") }, onClick = { menu = false; editing = t })
                                DropdownMenuItem(text = { Text("Eliminar") }, onClick = { menu = false; deleting = t })
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        TagDialog(null, onDismiss = { creating = false }) { name, color ->
            Store.createTag(name, color)
            creating = false
        }
    }
    editing?.let { t ->
        TagDialog(t, onDismiss = { editing = null }) { name, color ->
            Store.updateTag(t.copy(name = name, color = color))
            editing = null
        }
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Eliminar “${t.name}”?") },
            text = { Text("Las fotos no se borran; solo pierden esta etiqueta.") },
            confirmButton = {
                TextButton(onClick = { Store.deleteTag(t.id); deleting = null }) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun TagDialog(tag: Tag?, onDismiss: () -> Unit, onSave: (String, Int) -> Unit) {
    var name by remember { mutableStateOf(tag?.name ?: "") }
    var color by remember { mutableStateOf(tag?.color ?: (Store.tags.value.size % TagColors.size)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tag == null) "Nueva etiqueta" else "Editar etiqueta") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(30) },
                    label = { Text("Nombre") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagColors.take(5).forEachIndexed { i, c -> ColorDot(c, color == i) { color = i } }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagColors.drop(5).forEachIndexed { i, c -> ColorDot(c, color == i + 5) { color = i + 5 } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), color) }, enabled = name.isNotBlank()) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun ColorDot(c: androidx.compose.ui.graphics.Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(c))
    }
}
