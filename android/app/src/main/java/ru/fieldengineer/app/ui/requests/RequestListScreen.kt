package ru.fieldengineer.app.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fieldengineer.app.domain.RequestStatus
import ru.fieldengineer.app.domain.SlaStage
import ru.fieldengineer.app.ui.color
import ru.fieldengineer.app.ui.label
import ru.fieldengineer.app.ui.moscow

/** Э2 «Мои заявки»: список с индикатором срока SLA, сортировкой по сроку и фильтром; → Э3, Э9, Э10. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestListScreen(
    vm: RequestListViewModel,
    onOpen: (Int) -> Unit,
    onStats: () -> Unit,
    onSync: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Мои заявки")
                        if (state.engineerName.isNotEmpty()) {
                            Text(state.engineerName, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                },
                actions = {
                    TextButton(onClick = onStats) { Text("Показатели") }
                    // «Выйти» — на экране Э10: там видно, есть ли неотправленные записи
                    TextButton(onClick = onSync) { Text("Синхронизация") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(
                    selected = state.filter == RequestFilter.ACTIVE,
                    onClick = { vm.setFilter(RequestFilter.ACTIVE) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Активные (${state.activeCount})") }
                SegmentedButton(
                    selected = state.filter == RequestFilter.FINISHED,
                    onClick = { vm.setFilter(RequestFilter.FINISHED) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Закрытые (${state.finishedCount})") }
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Заявок нет", color = MaterialTheme.colorScheme.secondary)
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.items, key = { it.row.id }) { RequestCard(it, onClick = { onOpen(it.row.id) }) }
                }
            }
        }
    }
}

@Composable
private fun RequestCard(item: RequestListItem, onClick: () -> Unit) {
    val row = item.row
    val sla = item.sla
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // цветная полоса слева — признак срока: зелёный, жёлтый, красный
            Box(Modifier.width(6.dp).fillMaxHeight().background(sla?.state?.color() ?: Color.Transparent))
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.number, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    // изменения заявки или её записей (отметки, фото, время, акт) ещё не на сервере
                    if (row.hasUnsent) {
                        Text("  не отправлена", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.weight(1f))
                    StatusBadge(row.status)
                }
                Spacer(Modifier.height(6.dp))
                Text(row.customerName, style = MaterialTheme.typography.bodyLarge)
                Text(row.siteAddress, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(row.workTypeName, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(dueLine(item), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (sla != null && !RequestStatus.isFinished(row.status)) {
                    Text(sla.label(), style = MaterialTheme.typography.bodyMedium,
                        color = sla.state.color(), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Строка срока: какой срок сейчас действует, либо чем закончилась заявка. */
private fun dueLine(item: RequestListItem): String {
    val row = item.row
    return when {
        row.status == RequestStatus.CLOSED -> "${row.slaCode} · закрыта ${row.closedAt?.moscow().orEmpty()}" +
            (item.sla?.let { " · " + it.label() } ?: "")
        RequestStatus.isFinished(row.status) -> "${row.slaCode} · ${RequestStatus.title(row.status).lowercase()}"
        item.sla?.stage == SlaStage.REACTION -> "${row.slaCode} · прибыть до ${row.reactionDue.moscow()}"
        row.completedAt != null -> "${row.slaCode} · работы выполнены ${row.completedAt.moscow()}"
        else -> "${row.slaCode} · устранить до ${row.resolutionDue.moscow()}"
    }
}

@Composable
fun StatusBadge(status: Int) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
        Text(RequestStatus.title(status), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}
