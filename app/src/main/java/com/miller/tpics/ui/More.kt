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
fun dateText(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DATE_FMT)

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
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
fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

fun planText(a: Account, admin: Boolean): String = when {
    admin -> "Administrador"
    a.status == "suspended" -> "Suspendida"
    a.status != "active" -> "Pendiente de activación"
    a.paidUntil == 0L -> "Activa · sin vencimiento"
    a.paidUntil < System.currentTimeMillis() ->
        (if (a.isTrial) "Prueba terminada el " else "Venció el ") + dateText(a.paidUntil)
    a.isTrial -> "En prueba hasta el ${dateText(a.paidUntil)}"
    else -> "Activa · corte el ${dateText(a.paidUntil)}"
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
