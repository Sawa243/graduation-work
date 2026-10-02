package ru.fieldengineer.app.ui.card

import ru.fieldengineer.app.ui.SecureScreen
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fieldengineer.app.domain.RequestStatus
import ru.fieldengineer.app.domain.SlaCalculator
import ru.fieldengineer.app.ui.color
import ru.fieldengineer.app.ui.hoursMinutes
import ru.fieldengineer.app.ui.label
import ru.fieldengineer.app.ui.moscow
import ru.fieldengineer.app.ui.requests.StatusBadge

/** Э3 «Карточка заявки»: реквизиты, контакт, адрес, сроки, кнопки статусов, «Что сделано». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestCardScreen(
    vm: RequestCardViewModel, onBack: () -> Unit, onChecklist: () -> Unit,
    onScanner: () -> Unit, onPhotos: () -> Unit, onAct: () -> Unit,
) {
    SecureScreen()   // контакт заказчика (ПДн) — снимки экрана запрещены
    val state by vm.state.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.card?.request?.number ?: "Заявка") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Назад") } },
            )
        },
    ) { padding ->
        val card = state.card
        if (card == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val r = card.request
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(r.status)
                Spacer(Modifier.width(10.dp))
                state.sla?.let {
                    Text(it.label(), color = it.state.color(), fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (input.errors.isNotEmpty()) ErrorsCard(input.errors, vm::dismissErrors)

            Section("Сроки · ${card.slaCode} «${card.slaName}»") {
                Line("Зарегистрирована", r.registeredAt.moscow())
                Line("Прибыть до", r.reactionDue.moscow())
                r.arrivedAt?.let { Line("Прибыл", it.moscow() + if (it <= r.reactionDue) " · в срок" else " · с опозданием") }
                Line("Устранить до", r.resolutionDue.moscow())
                r.completedAt?.let { Line("Работы выполнены", it.moscow()) }
                Text(
                    if (card.mode == SlaCalculator.MODE_24X7) "Отсчёт круглосуточно"
                    else "Отсчёт в рабочее время: пн–пт 9:00–18:00 по производственному календарю",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                )
            }

            Section("Работы") {
                Text("${card.workTypeCode} · ${card.workTypeName}", style = MaterialTheme.typography.bodyLarge)
                Text("Норматив ${card.normMin} мин", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(4.dp))
                Text(r.description, style = MaterialTheme.typography.bodyMedium)
                card.eqInvCode?.let {
                    Spacer(Modifier.height(4.dp))
                    Line("Оборудование", "${card.eqVendor} ${card.eqModel}")
                    Line("Метка / зав. №", "$it / ${card.eqSerialNo ?: "—"}")
                }
                r.scannedCode?.let { Line("Отсканировано", it) }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onChecklist, modifier = Modifier.fillMaxWidth()) {
                    Text("Чек-лист: отмечено ${state.checklistDone} из ${state.checklistTotal}")
                }
                if (state.canEditWork) {
                    OutlinedButton(onClick = onScanner, modifier = Modifier.fillMaxWidth()) {
                        Text("Сканировать оборудование")
                    }
                }
                if (state.canEditWork || state.workPhotos > 0) {
                    OutlinedButton(onClick = onPhotos, modifier = Modifier.fillMaxWidth()) {
                        Text("Фото работ (${state.workPhotos})")
                    }
                }
            }

            Section("Заказчик и объект") {
                Text(card.customerName, style = MaterialTheme.typography.bodyLarge)
                Text("Договор ${card.contractNumber} · " +
                    if (card.eSignAllowed) "акт подписывается на экране" else "акт — на бумаге",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(4.dp))
                Text("${card.siteAddress} (объект ${card.siteCode})", style = MaterialTheme.typography.bodyMedium)
                card.contactName?.let { Text("Контакт: $it", style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    card.contactPhone?.let { phone ->
                        OutlinedButton(onClick = { context.open(Intent(Intent.ACTION_DIAL, "tel:$phone".toUri())) }) {
                            Text("Позвонить")
                        }
                    }
                    OutlinedButton(onClick = {
                        context.open(Intent(Intent.ACTION_VIEW, ("geo:0,0?q=" + Uri.encode(card.siteAddress)).toUri()))
                    }) { Text("Маршрут") }
                }
            }

            Section("Время по заявке") {
                Line("Дорога", state.roadTime.hoursMinutes())
                Line("Работа на объекте", state.workTime.hoursMinutes())
                if (!r.timeTrusted) {
                    Text("Часть отметок сделана по часам телефона (нет связи с сервером)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            Section("Что сделано") {
                val text = input.resultDraft ?: r.resultText.orEmpty()
                if (state.canEditWork) {
                    OutlinedTextField(
                        value = text, onValueChange = vm::onResultDraft,
                        placeholder = { Text("Опишите выполненные работы") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3,
                    )
                    Text("Сохраняется автоматически", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                } else {
                    Text(text.ifEmpty { "—" }, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (r.status == RequestStatus.WORK_DONE || r.status == RequestStatus.CLOSED) {
                Button(onClick = onAct, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (r.status == RequestStatus.WORK_DONE) "Оформить акт" else "Акт выполненных работ")
                }
            }
            state.targets.forEach { to ->
                val modifier = Modifier.fillMaxWidth().height(52.dp)
                // «Приостановить» — редкое действие, поэтому кнопка второстепенная
                if (to == RequestStatus.SUSPENDED) {
                    OutlinedButton(onClick = { vm.onTarget(to) }, enabled = !input.busy, modifier = modifier) {
                        Text(actionTitle(to))
                    }
                } else {
                    Button(onClick = { vm.onTarget(to) }, enabled = !input.busy, modifier = modifier) {
                        Text(actionTitle(to))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    input.confirmTarget?.let { to ->
        AlertDialog(
            onDismissRequest = vm::dismissConfirm,
            title = { Text(actionTitle(to) + "?") },
            text = {
                Text(if (to == RequestStatus.WORK_DONE)
                    "Отсчёт времени по заявке остановится, чек-лист и «Что сделано» больше нельзя будет изменить."
                else "Заявка будет приостановлена до повторного выезда (например, ждём запчасти).")
            },
            confirmButton = { TextButton(onClick = { vm.changeStatus(to) }) { Text("Да") } },
            dismissButton = { TextButton(onClick = vm::dismissConfirm) { Text("Отмена") } },
        )
    }
}

private fun actionTitle(to: Int): String = when (to) {
    RequestStatus.ON_THE_WAY -> "Выехал на объект"
    RequestStatus.ON_SITE -> "Прибыл на объект"
    RequestStatus.WORK_DONE -> "Работы выполнены"
    RequestStatus.SUSPENDED -> "Приостановить"
    else -> RequestStatus.title(to)
}

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "Нет приложения для этого действия", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun Line(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(0.45f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.55f))
    }
}

@Composable
private fun ErrorsCard(errors: List<String>, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Статус не изменён", fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer)
            errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.onErrorContainer) }
            TextButton(onClick = onDismiss) { Text("Понятно") }
        }
    }
}
