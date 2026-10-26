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
