package dev.unifiphotosync.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import dev.unifiphotosync.app.data.NasPath
import dev.unifiphotosync.app.smb.NasClient
import dev.unifiphotosync.app.smb.NasEntry
import dev.unifiphotosync.app.smb.NasShare
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.unifiphotosync.app.PhotoSyncApp
import dev.unifiphotosync.app.backup.BackupScope
import dev.unifiphotosync.app.data.Album
import dev.unifiphotosync.app.data.BackupPrefs
import dev.unifiphotosync.app.data.NasConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as PhotoSyncApp).graph

    private val _form = MutableStateFlow<NasConfig?>(null)
    val form = _form.asStateFlow()
    val saved = graph.settings.config.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val prefs = graph.settings.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, BackupPrefs())

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums = _albums.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing = _testing.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        viewModelScope.launch { _form.value = graph.settings.draft.first() }
        viewModelScope.launch {
            _albums.value = runCatching { graph.media.albumsOf(graph.media.loadAll()) }.getOrDefault(emptyList())
        }
    }

    fun edit(c: NasConfig) { _form.value = c }

    /**
     * Browser for choosing where backups go; null when closed. Its top level lists the
     * NAS's shares ([share] == null); inside a share it lists folders. Choosing a folder
     * fills in both the share and the destination.
     */
    data class Picker(
        val share: String? = null,
        val path: String = "",
        val shares: List<NasShare> = emptyList(),
        val folders: List<NasEntry> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _picker = MutableStateFlow<Picker?>(null)
    val picker = _picker.asStateFlow()
    private var pickerJob: Job? = null

    /** Normalizes the form (e.g. a pasted \\nas\share\folder path) and shows the result. */
    private fun normalizedForm(): NasConfig? = _form.value?.normalized()?.also { _form.value = it }

    fun openPicker() {
        val c = normalizedForm() ?: return
        if (c.host.isBlank() || c.username.isBlank()) {
            _message.value = "Fill in the NAS address, username and password first"
            return
        }
        if (c.share.isBlank()) showShares() else browse(c.share, c.destination, fallBack = true)
    }

    /** Top level: the shares on the NAS. */
    fun showShares() {
        val c = _form.value ?: return
        pickerJob?.cancel()
        _picker.value = Picker()
        pickerJob = viewModelScope.launch {
            runCatching { graph.nas.listShares(c) }.fold(
                onSuccess = { shares -> _picker.update { it?.copy(shares = shares, loading = false) } },
                onFailure = { e -> _picker.update { it?.copy(loading = false, error = NasClient.describe(e)) } },
            )
        }
    }

    /**
     * Folders in [path] of [share]. With [fallBack], a saved destination that no longer
     * exists falls back to its parent, and a share that doesn't exist to the share list.
     */
    fun browse(share: String, path: String, fallBack: Boolean = false) {
        val c = _form.value?.copy(share = share) ?: return
        val clean = NasPath.clean(path)
        pickerJob?.cancel()
        _picker.value = Picker(share = share, path = clean)
        pickerJob = viewModelScope.launch {
            runCatching { graph.nas.listFolders(c, clean) }.fold(
                onSuccess = { folders -> _picker.update { it?.copy(folders = folders, loading = false) } },
                onFailure = { e ->
                    when {
                        fallBack && clean.isNotEmpty() -> browse(share, NasPath.parent(clean), fallBack = true)
                        fallBack -> showShares()
                        else -> _picker.update { it?.copy(loading = false, error = NasClient.describe(e)) }
                    }
                },
            )
        }
    }

    /** Goes up one folder, or from a share's root back to the share list. */
    fun pickerUp() {
        val p = _picker.value ?: return
        val share = p.share ?: return
        if (p.path.isEmpty()) showShares() else browse(share, NasPath.parent(p.path))
    }

    fun createFolder(name: String) {
        val p = _picker.value ?: return
        val share = p.share ?: return
        val c = _form.value?.copy(share = share) ?: return
        val target = NasPath.join(p.path, NasPath.safeName(name))
        _picker.update { it?.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { graph.nas.createFolder(c, target) }.fold(
                onSuccess = { browse(share, target) },
                onFailure = { e ->
                    _picker.update { it?.copy(loading = false, error = "Couldn't create folder: ${NasClient.describe(e)}") }
                },
            )
        }
    }

    /** Uses the share and folder being shown as the destination and saves the connection. */
    fun choosePickerFolder() {
        val p = _picker.value ?: return
        val share = p.share ?: return
        val c = _form.value?.copy(share = share, destination = p.path) ?: return
        _form.value = c
        _picker.value = null
        viewModelScope.launch {
            if (!c.isComplete) return@launch
            graph.settings.saveConfig(c)
            _message.value = "Backups will go to " + displayPath(c.share, c.destination)
        }
    }

    fun closePicker() {
        pickerJob?.cancel()
        _picker.value = null
    }

    fun save() = viewModelScope.launch {
        val c = normalizedForm() ?: return@launch
        if (!c.isComplete) {
            _message.value = "Address, share and username are required. Use the folder button to pick a share."
            return@launch
        }
        graph.settings.saveConfig(c)
        _message.value = "Saved"
    }

    fun test() = viewModelScope.launch {
        val c = normalizedForm() ?: return@launch
        if (!c.isComplete) {
            _message.value = "Address, share and username are required. Use the folder button to pick a share."
            return@launch
        }
        _testing.value = true
        _message.value = graph.nas.test(c).fold({ it }, { "Connection failed: ${NasClient.describe(it)}" })
        _testing.value = false
    }

    fun setPrefs(p: BackupPrefs) = viewModelScope.launch {
        graph.settings.savePrefs(p)
        graph.scheduler.apply(p)
    }

    fun backUpNow() = viewModelScope.launch {
        if (graph.settings.config.first() == null) {
            _message.value = "Save the NAS connection first"
            return@launch
        }
        graph.scheduler.backupNow(BackupScope.All)
    }

    fun resetIndex() {
        graph.index.clear()
        _message.value = "Backup history cleared; the next backup will re-check every file against the NAS"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val picker by vm.picker.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var askBackground by remember { mutableStateOf(false) }
    // Turning on automatic or scheduled backup: make sure Android will let it run.
    val setPrefs: (BackupPrefs) -> Unit = { p ->
        val turningOn = (p.autoBackup && !prefs.autoBackup) || (p.scheduled && !prefs.scheduled)
        if (turningOn && !AppPermissions.readyForBackground(context)) askBackground = true
        vm.setPrefs(p)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            BackupStatusBanner()
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Section("UniFi NAS connection")
                Text(
                    "Enter the NAS address and a NAS user with write access, then tap the folder button to choose the share and folder for backups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                form?.let { c -> ConnectionForm(c, vm::edit, onBrowse = vm::openPicker) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.save() }, enabled = form != null && form != saved) { Text("Save") }
                    OutlinedButton(onClick = { vm.test() }, enabled = form != null && !testing) {
                        Text(if (testing) "Testing…" else "Test connection")
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Section("Automatic backup")
                ToggleRow("Back up new photos automatically", prefs.autoBackup) { setPrefs(prefs.copy(autoBackup = it)) }
                ScheduleSettings(prefs, setPrefs)
                Text(
                    "These apply to both automatic and scheduled backups:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                ToggleRow("Only on Wi-Fi / unmetered networks", prefs.unmeteredOnly, enabled = prefs.anyAutomatic) {
                    vm.setPrefs(prefs.copy(unmeteredOnly = it))
                }
                ToggleRow("Only while charging", prefs.chargingOnly, enabled = prefs.anyAutomatic) {
                    vm.setPrefs(prefs.copy(chargingOnly = it))
                }
                Text(
                    "Albums to back up" + if (prefs.albums.isEmpty()) " (all)" else " (${prefs.albums.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                albums.forEach { a ->
                    val checked = prefs.albums.isEmpty() || a.id in prefs.albums
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            enabled = prefs.anyAutomatic,
                            onCheckedChange = { on ->
                                // An empty set means "all"; materialize it before removing one.
                                val current = prefs.albums.ifEmpty { albums.mapTo(HashSet()) { it.id } }
                                val next = if (on) current + a.id else current - a.id
                                val all = albums.mapTo(HashSet()) { it.id }
                                vm.setPrefs(prefs.copy(albums = if (next.containsAll(all)) emptySet() else next))
                            },
                        )
                        Text("${a.name} (${a.count})")
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Section("Background access")
                Text(
                    "What Android needs to allow so backups and downloads keep running when the app is closed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BackgroundAccessPanel()

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Section("Maintenance")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.backUpNow() }) { Text("Back up everything now") }
                    TextButton(onClick = { confirmReset = true }) { Text("Reset history") }
                }
            }
        }
    }

    picker?.let { p ->
        FolderPickerDialog(
            picker = p,
            onOpenShare = { vm.browse(it, "") },
            onOpenFolder = { vm.browse(p.share.orEmpty(), it) },
            onUp = vm::pickerUp,
            onCreate = vm::createFolder,
            onChoose = vm::choosePickerFolder,
            onDismiss = vm::closePicker,
        )
    }

    if (askBackground) BackgroundAccessDialog(onDismiss = { askBackground = false })

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset backup history?") },
            text = {
                Text("The app forgets what it has uploaded. Files already on the NAS are left alone and detected as duplicates on the next backup.")
            },
            confirmButton = { TextButton(onClick = { vm.resetIndex(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConnectionForm(c: NasConfig, onChange: (NasConfig) -> Unit, onBrowse: () -> Unit) {
    var showPassword by remember { mutableStateOf(false) }
    Field("NAS address (IP or hostname)", c.host, KeyboardType.Uri) { onChange(c.copy(host = it)) }
    Field("Username", c.username) { onChange(c.copy(username = it)) }
    OutlinedTextField(
        value = c.password,
        onValueChange = { onChange(c.copy(password = it)) },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show password")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = c.share,
        onValueChange = { onChange(c.copy(share = it)) },
        label = { Text("Share name") },
        supportingText = { Text("Tap the folder button to pick from the NAS's shares") },
        singleLine = true,
        trailingIcon = { IconButton(onClick = onBrowse) { Icon(Icons.Filled.FolderOpen, "Browse NAS shares") } },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = c.destination,
        onValueChange = { onChange(c.copy(destination = it)) },
        label = { Text("Destination folder in the share") },
        placeholder = { Text("Share root") },
        supportingText = { Text("Phone folders are copied inside it, e.g. DCIM/Camera") },
        singleLine = true,
        trailingIcon = { IconButton(onClick = onBrowse) { Icon(Icons.Filled.FolderOpen, "Browse NAS folders") } },
        modifier = Modifier.fillMaxWidth(),
    )
    Field("Domain (usually blank)", c.domain) { onChange(c.copy(domain = it)) }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** The share and folder written the way Windows shows a network path (two leading backslashes). */
private fun displayPath(share: String, path: String): String =
    "\\\\" + share + (if (path.isEmpty()) "" else "\\" + path)

@Composable
private fun FolderPickerDialog(
    picker: SettingsViewModel.Picker,
    onOpenShare: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onUp: () -> Unit,
    onCreate: (String) -> Unit,
    onChoose: () -> Unit,
    onDismiss: () -> Unit,
) {
    var newFolder by remember(picker.share, picker.path) { mutableStateOf<String?>(null) }
    val atShares = picker.share == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (atShares) "Choose a share" else "Choose destination") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!atShares) {
                        IconButton(onClick = onUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
                    }
                    Text(
                        if (atShares) "Shares on the NAS" else displayPath(picker.share.orEmpty(), picker.path),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (picker.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                picker.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    if (atShares) {
                        items(picker.shares, key = { it.name }) { s ->
                            ListItem(
                                leadingContent = { Icon(Icons.Filled.Storage, null, tint = MaterialTheme.colorScheme.primary) },
                                headlineContent = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = s.remark.takeIf { it.isNotBlank() }?.let { r -> { Text(r, maxLines = 1) } },
                                modifier = Modifier.clickable { onOpenShare(s.name) },
                            )
                        }
                    } else {
                        items(picker.folders, key = { it.path }) { f ->
                            ListItem(
                                leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                                headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.clickable { onOpenFolder(f.path) },
                            )
                        }
                    }
                    val empty = if (atShares) picker.shares.isEmpty() else picker.folders.isEmpty()
                    if (!picker.loading && empty && picker.error == null) {
                        item {
                            Text(
                                if (atShares) "No shares available to this user" else "No subfolders",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
                if (!atShares) {
                    val name = newFolder
                    if (name == null) {
                        TextButton(onClick = { newFolder = "" }, enabled = !picker.loading) {
                            Icon(Icons.Filled.CreateNewFolder, null)
                            Text("New folder", Modifier.padding(start = 8.dp))
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { newFolder = it },
                                label = { Text("Folder name") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { onCreate(name); newFolder = null },
                                enabled = name.isNotBlank() && !picker.loading,
                            ) { Text("Create") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // A share has to be opened first; then its root or any folder in it can be used.
            TextButton(onClick = onChoose, enabled = !atShares && !picker.loading) { Text("Use this folder") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
