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

private const val DAY_MS = 24L * 60 * 60 * 1000

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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                listOf(1, 3, 7, 15, 30, 0).forEach { d ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { store.updateRetention(d) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = store.retentionDays == d, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(retentionLabel(d))
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.custom_days)) },
                        singleLine = true,
                        isError = custom.isNotEmpty() && !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            parsed?.let {
                                store.updateRetention(it)
                                custom = ""
                            }
                        },
                        enabled = valid,
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text(stringResource(R.string.apply)) }
                }
            }
        }
    }
}
