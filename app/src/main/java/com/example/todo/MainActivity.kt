package com.example.todo

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

data class Todo(val id: Long, val text: String, val done: Boolean = false)

private const val PREFS = "todo_prefs"
private const val KEY = "items"

private fun loadTodos(context: Context): List<Todo> {
    val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY, null) ?: return emptyList()
    return try {
        val arr = JSONArray(json)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Todo(o.getLong("id"), o.getString("text"), o.getBoolean("done"))
        }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun saveTodos(context: Context, todos: List<Todo>) {
    val arr = JSONArray()
    todos.forEach {
        arr.put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done))
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY, arr.toString()).apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TodoScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen() {
    val context = LocalContext.current
    val todos = remember { mutableStateListOf<Todo>().apply { addAll(loadTodos(context)) } }
    var input by remember { mutableStateOf("") }

    fun persist() = saveTodos(context, todos)

    fun addTodo() {
        val text = input.trim()
        if (text.isNotEmpty()) {
            todos.add(0, Todo(System.currentTimeMillis(), text))
            input = ""
            persist()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.title)) }) }
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
                FilledIconButton(onClick = { addTodo() }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_task))
                }
            }

            Spacer(Modifier.height(16.dp))

            if (todos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.empty))
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(todos, key = { it.id }) { todo ->
                        TodoRow(
                            todo = todo,
                            onToggle = {
                                val i = todos.indexOfFirst { it.id == todo.id }
                                if (i >= 0) {
                                    todos[i] = todo.copy(done = !todo.done)
                                    persist()
                                }
                            },
                            onDelete = {
                                todos.removeAll { it.id == todo.id }
                                persist()
                            }
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
