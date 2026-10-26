package com.example.todo

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

// deletedAt == 0 মানে কাজটি সাধারণ তালিকায় আছে; 0 এর বেশি মানে রিসাইকেল বিনে আছে
data class Todo(
    val id: Long,
    val text: String,
    val done: Boolean = false,
    val deletedAt: Long = 0L
)

private const val PREFS = "todo_prefs"
private const val KEY_ITEMS = "items"
private const val KEY_RETENTION = "retention_days"
private const val KEY_BIN = "bin_enabled"
private const val DAY_MS = 24L * 60 * 60 * 1000

// ---------- ডেটা ও সংরক্ষণ ----------

class Store(private val context: Context) {
    val todos = mutableStateListOf<Todo>()
    var retentionDays by mutableStateOf(30) // 0 = স্বয়ংক্রিয়ভাবে মুছবে না
    var binEnabled by mutableStateOf(true)

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        load()
        purgeExpired()
    }

    private fun load() {
        retentionDays = prefs.getInt(KEY_RETENTION, 30)
        binEnabled = prefs.getBoolean(KEY_BIN, true)
        val json = prefs.getString(KEY_ITEMS, null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                todos.add(
                    Todo(
                        id = o.getLong("id"),
                        text = o.getString("text"),
                        done = o.getBoolean("done"),
                        deletedAt = o.optLong("deletedAt", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            // নষ্ট ডেটা থাকলে খালি তালিকা দিয়ে শুরু হবে
        }
    }

    private fun persist() {
        val arr = JSONArray()
        todos.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("text", it.text)
                    .put("done", it.done)
                    .put("deletedAt", it.deletedAt)
            )
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun add(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        todos.add(0, Todo(System.currentTimeMillis(), t))
        persist()
    }

    fun toggle(id: Long) {
        val i = todos.indexOfFirst { it.id == id }
        if (i >= 0) {
            todos[i] = todos[i].copy(done = !todos[i].done)
            persist()
        }
    }

    // বিন চালু থাকলে বিনে যাবে, বন্ধ থাকলে সঙ্গে সঙ্গে চিরতরে মুছবে
    fun moveToBin(id: Long) {
        val i = todos.indexOfFirst { it.id == id }
        if (i < 0) return
        if (binEnabled) {
            todos[i] = todos[i].copy(deletedAt = System.currentTimeMillis())
        } else {
            todos.removeAt(i)
        }
        persist()
    }

    fun restore(ids: List<Long>) {
        ids.forEach { id ->
            val i = todos.indexOfFirst { it.id == id }
            if (i >= 0) todos[i] = todos[i].copy(deletedAt = 0L)
        }
        persist()
    }

    fun deleteForever(ids: List<Long>) {
        todos.removeAll { it.id in ids && it.deletedAt != 0L }
        persist()
    }

    fun emptyBin() {
        todos.removeAll { it.deletedAt != 0L }
        persist()
    }

    fun purgeExpired() {
        if (retentionDays <= 0) return
        val limit = retentionDays * DAY_MS
        val now = System.currentTimeMillis()
        val removed = todos.removeAll { it.deletedAt != 0L && now - it.deletedAt >= limit }
        if (removed) persist()
    }

    fun updateRetention(days: Int) {
        retentionDays = days
        prefs.edit().putInt(KEY_RETENTION, days).apply()
        purgeExpired()
    }

    fun updateBinEnabled(enabled: Boolean) {
        binEnabled = enabled
        prefs.edit().putBoolean(KEY_BIN, enabled).apply()
    }
}

// ---------- ইউআই ----------

enum class Screen { TASKS, BIN, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TodoApp()
                }
            }
        }
    }
}

@Composable
fun TodoApp() {
    val context = LocalContext.current
    val store = remember { Store(context.applicationContext) }
    var screen by rememberSaveable { mutableStateOf(Screen.TASKS) }

    BackHandler(enabled = screen != Screen.TASKS) { screen = Screen.TASKS }

    when (screen) {
        Screen.TASKS -> TasksScreen(
            store = store,
            onOpenBin = { screen = Screen.BIN },
            onOpenSettings = { screen = Screen.SETTINGS }
        )
        Screen.BIN -> BinScreen(store = store, onBack = { screen = Screen.TASKS })
        Screen.SETTINGS -> SettingsScreen(store = store, onBack = { screen = Screen.TASKS })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(store: Store, onOpenBin: () -> Unit, onOpenSettings: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val active = store.todos.filter { it.deletedAt == 0L }
    val binCount = store.todos.count { it.deletedAt != 0L }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title)) },
                actions = {
                    IconButton(onClick = onOpenBin) {
                        BadgedBox(badge = {
                            if (binCount > 0) Badge { Text(binCount.toString()) }
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.recycle_bin)
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.new_task)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = {
                    store.add(input)
                    input = ""
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_task))
                }
            }

            Spacer(Modifier.height(16.dp))

            if (active.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.empty))
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(active, key = { it.id }) { todo ->
                        TodoRow(
                            todo = todo,
                            onToggle = { store.toggle(todo.id) },
                            onDelete = { store.moveToBin(todo.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TodoRow(todo: Todo, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = todo.done, onCheckedChange = { onToggle() })
            Text(
                text = todo.text,
                modifier = Modifier.weight(1f),
                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                style = MaterialTheme.typography.bodyLarge
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_task))
            }
        }
    }
}

@Composable
fun retentionLabel(days: Int): String =
    if (days <= 0) stringResource(R.string.never) else stringResource(R.string.days_fmt, days)

@Composable
fun expiryText(retentionDays: Int, deletedAt: Long): String {
    if (retentionDays <= 0) return stringResource(R.string.no_auto_delete)
    val remaining = deletedAt + retentionDays * DAY_MS - System.currentTimeMillis()
    if (remaining < DAY_MS) return stringResource(R.string.expires_soon)
    val days = ((remaining + DAY_MS - 1) / DAY_MS).toInt()
    return stringResource(R.string.expires_in_days, days)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BinScreen(store: Store, onBack: () -> Unit) {
    LaunchedEffect(Unit) { store.purgeExpired() }

    val bin = store.todos.filter { it.deletedAt != 0L }.sortedByDescending { it.deletedAt }
    val selected = remember { mutableStateListOf<Long>() }
    val sel = selected.filter { id -> bin.any { it.id == id } }
    var confirmSelected by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recycle_bin)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text(
                text = if (store.retentionDays <= 0)
                    stringResource(R.string.bin_note_never)
                else
                    stringResource(R.string.bin_note_days, store.retentionDays),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))

            if (bin.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.bin_empty))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (sel.size == bin.size) {
                            selected.clear()
                        } else {
                            selected.clear()
                            selected.addAll(bin.map { it.id })
                        }
                    }) {
                        Text(
                            if (sel.size == bin.size) stringResource(R.string.clear_selection)
                            else stringResource(R.string.select_all)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (sel.isNotEmpty()) {
                        Text(stringResource(R.string.selected_count, sel.size))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            store.restore(sel)
                            selected.clear()
                        },
                        enabled = sel.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.restore)) }

                    Button(
                        onClick = { confirmSelected = true },
                        enabled = sel.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.delete_forever)) }
                }

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(bin, key = { it.id }) { todo ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = todo.id in selected,
                                    onCheckedChange = { checked ->
                                        if (checked) selected.add(todo.id) else selected.remove(todo.id)
                                    }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(todo.text, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        expiryText(store.retentionDays, todo.deletedAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = {
                                    store.restore(listOf(todo.id))
                                    selected.remove(todo.id)
                                }) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.restore)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmAll = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.empty_bin)) }
            }
        }
    }

    if (confirmSelected) {
        AlertDialog(
            onDismissRequest = { confirmSelected = false },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = { Text(stringResource(R.string.confirm_selected, sel.size)) },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteForever(sel)
                    selected.clear()
                    confirmSelected = false
                }) { Text(stringResource(R.string.yes_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSelected = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = { Text(stringResource(R.string.confirm_all, bin.size)) },
            confirmButton = {
                TextButton(onClick = {
                    store.emptyBin()
                    selected.clear()
                    confirmAll = false
                }) { Text(stringResource(R.string.yes_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAll = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: Store, onBack: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    val parsed = custom.toIntOrNull()
    val valid = parsed != null && parsed in 1..365

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_use_bin),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.settings_use_bin_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = store.binEnabled,
                    onCheckedChange = { store.updateBinEnabled(it) }
                )
            }

            if (store.binEnabled) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text(
                    stringResource(R.string.settings_auto_delete),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.current_setting, retentionLabel(store.retentionDays)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVarian
