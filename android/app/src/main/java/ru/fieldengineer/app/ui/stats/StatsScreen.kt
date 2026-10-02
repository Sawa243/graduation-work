package ru.fieldengineer.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fieldengineer.app.ui.hoursMinutes

/** Э9 «Мои показатели»: трудозатраты за день и месяц, закрытые заявки, доля в срок. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: StatsViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мои показатели") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Block("Сегодня") {
                Value("Работа на объектах", s.todayWork.hoursMinutes())
                Value("Дорога", s.todayRoad.hoursMinutes())
            }
            Block("За ${s.monthTitle}") {
                Value("Работа на объектах", s.monthWork.hoursMinutes())
                Value("Дорога", s.monthRoad.hoursMinutes())
                Value("Закрыто заявок", s.closedInMonth.toString())
                Value("Устранено в срок", s.resolvedInTimePercent?.let { "$it %" } ?: "—")
                Value("Прибытие в срок", s.reactedInTimePercent?.let { "$it %" } ?: "—")
            }
            Block("Сейчас") {
                Value("Заявок в работе", s.active.toString())
            }
            Text(
                "Трудозатраты считаются по отметкам статусов: «В пути» — дорога, «На объекте» — работа. " +
                    "Доля в срок — среди заявок, закрытых в этом месяце.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
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
