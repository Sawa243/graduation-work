package ru.fieldengineer.app.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fieldengineer.app.data.sync.RejectedRecord
import ru.fieldengineer.app.data.sync.SyncPhase
import ru.fieldengineer.app.data.sync.SyncRules
import ru.fieldengineer.app.data.sync.SyncStatus
import ru.fieldengineer.app.ui.ColorInTime
import ru.fieldengineer.app.ui.ColorOverdue
import ru.fieldengineer.app.ui.moscow
import java.time.Instant

/** Э10 «Синхронизация и настройки». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(vm: SyncViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val confirmLogout by vm.confirmLogout.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Синхронизация") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Block("Обмен с сервером") {
                Value("Последняя синхронизация", s.lastSync?.moscow() ?: "ещё не было")
                StatusLine(s.status)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = vm::syncNow, enabled = s.status.phase != SyncPhase.RUNNING,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Синхронизировать сейчас") }
            }

            Block("Ждут отправки") {
                Value("Заявки", s.queue.request.toString())
                Value("Отметки чек-листа", s.queue.checklistResult.toString())
                Value("Фото", s.queue.photo.toString())
                Value("Интервалы работы", s.queue.workLog.toString())
                Value("Акты", s.queue.act.toString())
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Value("Всего", s.queue.total.toString())
            }

            Block("Отклонено сервером") {
                if (s.journal.isEmpty()) {
                    Text("Нет отклонённых записей", color = MaterialTheme.colorScheme.secondary)
                } else {
                    s.journal.forEachIndexed { i, record ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        RejectedLine(record)
                        when {
                            record.replaced -> Hint("Запись заменена данными сервера. Повторите действие в карточке заявки.")
                            // заявка после отказа уже в версии сервера — шаг повторяют кнопкой в карточке
                            !SyncRules.canResend(record) -> Hint("Повторите действие в карточке заявки.")
                            else -> TextButton(onClick = { vm.resend(record) }) { Text("Отправить повторно") }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = vm::onLogoutClick,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Выйти") }
            Text(
                "При выходе из телефона удаляются заявки, фото, акты и токены входа. " +
                    "Без связи с сервером войти снова не получится.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = vm::dismissLogout,
            title = { Text("Выйти без отправки?") },
            text = {
                Text("Не отправлено на сервер записей: ${s.queue.total}. После выхода они будут " +
                    "удалены с телефона без возможности восстановления. Сначала синхронизируйте данные.")
            },
            confirmButton = { TextButton(onClick = vm::logout) { Text("Выйти и удалить") } },
            dismissButton = { TextButton(onClick = vm::dismissLogout) { Text("Отмена") } },
        )
    }
}

@Composable
private fun StatusLine(status: SyncStatus) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Состояние", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (status.phase == SyncPhase.RUNNING) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
        }
        val (text, color) = when (status.phase) {
            SyncPhase.IDLE -> "—" to Color.Unspecified
            SyncPhase.RUNNING -> "идёт обмен" to Color.Unspecified
            SyncPhase.OK -> "выполнено" to ColorInTime
            SyncPhase.NETWORK_ERROR -> "нет связи" to ColorOverdue
            SyncPhase.ERROR -> "ошибка" to ColorOverdue
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = color)
    }
    if (status.message != null && status.phase != SyncPhase.OK) {
        Text(status.message, style = MaterialTheme.typography.bodyMedium, color = ColorOverdue)
    }
}

@Composable
private fun RejectedLine(record: RejectedRecord) {
    Text(
        (if (record.deleted) "Удаление фото" else SyncRules.tableTitle(record.table)) +
            (record.requestNumber?.let { " · заявка $it" } ?: ""),
        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
    )
    Text(record.reason, style = MaterialTheme.typography.bodyMedium)
    Text(
        Instant.ofEpochMilli(record.atMs).moscow() + " · id " + record.recordId.take(8),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Value(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}
