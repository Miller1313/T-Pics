package com.miller.tpics.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.LabelOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miller.tpics.data.Auth
import com.miller.tpics.data.AutoBackup
import com.miller.tpics.data.Suggestions
import com.miller.tpics.data.Updater
import com.miller.tpics.data.ensureOpen
import com.miller.tpics.data.Db
import com.miller.tpics.data.Store
import com.miller.tpics.data.ThumbBackfill
import com.miller.tpics.data.UploadQueue
import com.miller.tpics.data.friendly
import kotlinx.coroutines.launch
import org.json.JSONObject

/* ───────────────────────────── Navegación ───────────────────────────── */

sealed interface Screen {
    data object Photos : Screen
    data object Videos : Screen
    data object Favorites : Screen
    data object Untagged : Screen
    data object Tags : Screen
    data class TagView(val tagId: String) : Screen
    data object Device : Screen
    data class Folder(val bucketId: String?, val name: String) : Screen
    data object Uploads : Screen
    data object Auto : Screen
    data object Private : Screen
    data object Trash : Screen
    data object StorageInfo : Screen
    data object Admin : Screen
    data object Settings : Screen
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Photos)
    val current: Screen get() = stack.last()
    val atRoot: Boolean get() = stack.size == 1

    fun go(s: Screen) { stack.add(s) }
    fun root(s: Screen) { stack.clear(); stack.add(s) }
    fun back(): Boolean {
        if (stack.size > 1) { stack.removeAt(stack.lastIndex); return true }
        if (stack[0] != Screen.Photos) { root(Screen.Photos); return true }
        return false
    }
}

class Shell(
    val nav: Nav,
    val openMenu: () -> Unit,
    val openViewer: (ids: List<String>, index: Int, inTrash: Boolean) -> Unit,
)

val LocalShell = compositionLocalOf<Shell> { error("Shell no disponible") }

/* ───────────────────────────── Raíz ───────────────────────────── */

@Composable
fun AppRoot() {
    val session by Auth.session.collectAsState()
    val s = session
    if (s == null) {
        AuthScreen()
    } else {
        val loaded by Store.loaded.collectAsState()
        val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
        LaunchedEffect(s.uid) {
            ensureOpen()
            Store.sync()
            launch { Updater.check() }
            launch { runCatching { AutoBackup.scan(ctx) } }
            ThumbBackfill.run()
        }
        if (loaded) MainShell() else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}

fun signOutEverywhere() {
    UploadQueue.close()
    AutoBackup.close()
    Suggestions.close()
    Store.close()
    Auth.signOut()
}

@Composable
fun MainShell() {
    val nav = remember { Nav() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }

    LaunchedEffect(Unit) {
        Store.messages.collect { snackbar.showSnackbar(it) }
    }

    val shell = remember {
        Shell(
            nav = nav,
            openMenu = { scope.launch { drawer.open() } },
            openViewer = { ids, index, inTrash -> viewer = ViewerRequest(ids, index, inTrash) },
        )
    }

    BackHandler(enabled = viewer == null && (drawer.isOpen || !nav.atRoot || nav.current != Screen.Photos)) {
        if (drawer.isOpen) scope.launch { drawer.close() } else nav.back()
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalShell provides shell) {
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = viewer == null && nav.atRoot,
            drawerContent = {
                DrawerContent(nav.current) { target ->
                    scope.launch { drawer.close() }
                    nav.root(target)
                }
            },
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                when (val screen = nav.current) {
                    Screen.Photos -> GalleryScreen("Fotos", home = true) { it.visible }
                    Screen.Videos -> GalleryScreen("Videos", empty = "Aún no hay videos") { it.visible && it.isVideo }
                    Screen.Favorites -> GalleryScreen("Favoritos", empty = "Marca con ♥ las que más te gusten") { it.visible && it.fav }
                    Screen.Untagged -> GalleryScreen("Sin etiquetar", empty = "Todo está etiquetado") { it.visible && it.tags.isEmpty() }
                    Screen.Trash -> GalleryScreen("Papelera", trash = true, empty = "La papelera está vacía") { it.trashedAt > 0L }
                    is Screen.TagView -> {
                        val tags by Store.tags.collectAsState()
                        val tag = tags.firstOrNull { it.id == screen.tagId }
                        GalleryScreen(tag?.name ?: "Etiqueta", empty = "Ninguna foto con esta etiqueta") {
                            it.visible && screen.tagId in it.tags
                        }
                    }
                    Screen.Tags -> TagsScreen()
                    Screen.Device -> DeviceScreen()
                    is Screen.Folder -> FolderScreen(screen.bucketId, screen.name)
                    Screen.Uploads -> UploadsScreen()
                    Screen.Auto -> AutoScreen()
                    Screen.Private -> PrivateScreen()
                    Screen.StorageInfo -> StorageScreen()
                    Screen.Admin -> AdminScreen()
                    Screen.Settings -> SettingsScreen()
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 72.dp))
            }
        }
        viewer?.let { v -> ViewerOverlay(v) { viewer = null } }
    }
}

/* ─────────────────────────── Menú hamburguesa ─────────────────────────── */

@Composable
private fun DrawerContent(current: Screen, onPick: (Screen) -> Unit) {
    val admin by Store.isAdmin.collectAsState()
    val session by Auth.session.collectAsState()
    val media by Store.media.collectAsState()
    val queue by UploadQueue.items.collectAsState()
    val pending = queue.count { it.status == "queued" || it.status == "running" }
    val suggestions by Suggestions.items.collectAsState()
    val toReview = suggestions.size
    val untagged = remember(media) { media.count { it.visible && it.tags.isEmpty() } }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.width(300.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Column(Modifier.padding(start = 28.dp, end = 24.dp, top = 28.dp, bottom = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(10.dp))
                    Text("T-Pics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    session?.email ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            @Composable
            fun item(label: String, icon: ImageVector, target: Screen, badge: String? = null) {
                NavigationDrawerItem(
                    label = { Text(label) },
                    icon = { Icon(icon, null) },
                    badge = if (badge != null) {
                        { Text(badge, style = MaterialTheme.typography.labelMedium) }
                    } else null,
                    selected = current == target,
                    onClick = { onPick(target) },
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            @Composable
            fun divider() = HorizontalDivider(
                Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            item("Fotos", Icons.Outlined.PhotoLibrary, Screen.Photos)
            item("Videos", Icons.Outlined.VideoLibrary, Screen.Videos)
            item("Favoritos", Icons.Outlined.FavoriteBorder, Screen.Favorites)
            divider()
            item("Etiquetas", Icons.Outlined.Label, Screen.Tags)
            item("Sin etiquetar", Icons.Outlined.LabelOff, Screen.Untagged, if (untagged > 0) "$untagged" else null)
            divider()
            item("Subir desde el teléfono", Icons.Outlined.PhoneAndroid, Screen.Device)
            item("Respaldo automático", Icons.Outlined.Sync, Screen.Auto, if (toReview > 0) "$toReview" else null)
            item("Subidas", Icons.Outlined.CloudUpload, Screen.Uploads, if (pending > 0) "$pending" else null)
            divider()
            item("Carpeta privada", Icons.Outlined.Lock, Screen.Private)
            item("Papelera", Icons.Outlined.Delete, Screen.Trash)
            item("Almacenamiento y plan", Icons.Outlined.Storage, Screen.StorageInfo)
            if (admin) item("Administración", Icons.Outlined.AdminPanelSettings, Screen.Admin)
            item("Ajustes", Icons.Outlined.Settings, Screen.Settings)
        }
    }
}

/* ───────────────────────────── Barra superior ───────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val shell = LocalShell.current
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            if (shell.nav.atRoot) {
                IconButton(onClick = shell.openMenu) { Icon(Icons.Filled.Menu, "Menú") }
            } else {
                IconButton(onClick = { shell.nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, detail: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/* ───────────────────────────── Inicio de sesión ───────────────────────────── */

@Composable
fun AuthScreen() {
    var register by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun submit() {
        error = ""; notice = ""
        val mail = email.trim()
        if (mail.isEmpty() || password.isEmpty() || (register && name.isBlank())) {
            error = "Completa todos los campos"; return
        }
        busy = true
        scope.launch {
            try {
                if (register) {
                    Auth.signUp(mail, password)
                    val uid = Auth.session.value?.uid
                    val profile = JSONObject().put("name", name.trim()).put("email", mail)
                        .put("createdAt", System.currentTimeMillis()).toString()
                    if (uid != null) Store.scope.launch {
                        runCatching { Db.put("v2/users/$uid/profile", profile) }
                    }
                } else {
                    Auth.signIn(mail, password)
                }
            } catch (e: Exception) {
                error = e.friendly()
            } finally {
                busy = false
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(12.dp))
                Text("T-Pics", fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (register) "Crea tu cuenta para respaldar tus fotos." else "Tus fotos y videos, ordenados con etiquetas.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            if (register) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Nombre") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = email, onValueChange = { email = it }, label = { Text("Correo") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Contraseña") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            if (error.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (notice.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(notice, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { submit() }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (register) "Crear cuenta" else "Entrar")
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { register = !register; error = ""; notice = "" }) {
                    Text(if (register) "Ya tengo cuenta" else "Crear una cuenta")
                }
                if (!register) {
                    TextButton(onClick = {
                        val mail = email.trim()
                        error = ""; notice = ""
                        if (mail.isEmpty()) { error = "Escribe tu correo primero"; return@TextButton }
                        scope.launch {
                            try {
                                Auth.resetPassword(mail)
                                notice = "Te enviamos un correo para cambiar la contraseña"
                            } catch (e: Exception) {
                                error = e.friendly()
                            }
                        }
                    }) { Text("Olvidé mi contraseña") }
                }
            }
        }
    }
}

data class ViewerRequest(val ids: List<String>, val index: Int, val inTrash: Boolean)
