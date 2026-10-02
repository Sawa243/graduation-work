package ru.fieldengineer.app.ui.checklist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fieldengineer.app.data.db.ChecklistRow
import ru.fieldengineer.app.ui.ColorAtRisk
import ru.fieldengineer.app.ui.ColorInTime

/** Э4 «Чек-лист»: пункты вида работ с отметками, комментарием и пометкой «нужно фото». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistScreen(vm: ChecklistViewModel, onBack: () -> Unit, onPhotos: (Int) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val editing by vm.editingComment.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Чек-лист")
                        Text("${state.number} · ${state.workType}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Назад") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                val done = state.items.count { it.done == true }
                val required = state.items.count { it.required }
                val requiredDone = state.items.count { it.required && it.done == true }
                Text("Отмечено $done из ${state.items.size}, обязательных $requiredDone из $required",
                    style = MaterialTheme.typography.bodyMedium)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!state.editable && !state.loading) {
                    Text("Отмечать пункты можно только в статусе «На объекте»",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            items(state.items, key = { it.itemId }) { item ->
                ChecklistItemCard(item, state.editable, onToggle = { vm.toggle(item) },
                    onComment = { vm.editingComment.value = item }, onPhotos = { onPhotos(item.itemId) })
            }
        }
    }

    editing?.let { item -> CommentDialog(item, onSave = { vm.saveComment(item, it) },
        onDismiss = { vm.editingComment.value = null }) }
}

@Composable
private fun ChecklistItemCard(
    item: ChecklistRow, editable: Boolean, onToggle: () -> Unit, onComment: () -> Unit, onPhotos: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        // отметка ставится нажатием на всю строку пункта, а не только на квадратик
        Row(
            Modifier
                .toggleable(value = item.done == true, enabled = editable, role = Role.Checkbox,
                    onValueChange = { onToggle() })
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = item.done == true, onCheckedChange = null, enabled = editable,
                modifier = Modifier.padding(12.dp))
            Column(Modifier.padding(top = 12.dp).weight(1f)) {
                Text("${item.pos}. ${item.text}", style = MaterialTheme.typography.bodyLarge)
                val marks = buildList {
                    if (item.required) add("обязательный")
                    if (item.needPhoto) add("нужно фото (${item.photoCount})")
                }
                if (marks.isNotEmpty()) {
                    Text(marks.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (item.needPhoto && item.photoCount == 0) ColorAtRisk else ColorInTime)
                }
                item.comment?.let {
                    Text("Замечание: $it", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary)
                }
                Row {
                    if (editable) {
                        TextButton(onClick = onComment, contentPadding = PaddingValues(0.dp)) {
                            Text(if (item.comment == null) "Замечание" else "Изменить замечание")
                        }
                    }
                    if (item.needPhoto && (editable || item.photoCount > 0)) {
                        TextButton(onClick = onPhotos, contentPadding = PaddingValues(start = 16.dp)) {
                            Text("Фото (${item.photoCount})")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentDialog(item: ChecklistRow, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(item.comment.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Замечание к пункту ${item.pos}") },
        text = {
            OutlinedTextField(value = text, onValueChange = { if (it.length <= 300) text = it },
                modifier = Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text("До 300 знаков") })
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
