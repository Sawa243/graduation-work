package ru.fieldengineer.app.ui.act

import ru.fieldengineer.app.ui.SecureScreen
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fieldengineer.app.data.files.FileStore
import ru.fieldengineer.app.domain.ActBuilder
import ru.fieldengineer.app.domain.RequestStatus
import ru.fieldengineer.app.ui.ColorInTime
import ru.fieldengineer.app.ui.moscow

/** Э8 «Акт»: предпросмотр PDF, подпись на экране или фото бумажного акта, «Поделиться», «Закрыть заявку». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActScreen(
    vm: ActViewModel, files: FileStore,
    onBack: () -> Unit, onSign: () -> Unit, onPaperPhoto: () -> Unit, onClosed: () -> Unit,
) {
    SecureScreen()   // акт с ФИО и подписью — снимки экрана запрещены
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.closed) { if (state.closed) onClosed() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Акт № ${state.number}")
                        Text(RequestStatus.title(state.status), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val act = state.act
            if (act != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(if (act.signMode == ActBuilder.SIGN_ON_SCREEN) "Подписан на экране" else "Подписан на бумаге",
                            color = ColorInTime, fontWeight = FontWeight.SemiBold)
                        act.signerName?.let { Text("Заказчик: ${act.signerPosition.orEmpty()} $it") }
                        act.signedAt?.let { Text("Время подписи: ${it.moscow()}") }
                        Text("Трудозатраты: ${act.workMin} мин")
                        Text("SHA-256: ${act.sha256.take(16)}…", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                }
            } else if (!state.eSignAllowed) {
                Text("По договору нет соглашения о простой электронной подписи: акт печатается, " +
                    "подписывается на бумаге, а его фото прикладывается к заявке.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }

            if (state.missing.isNotEmpty() && act == null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Не заполнены реквизиты акта", fontWeight = FontWeight.SemiBold)
                        state.missing.forEach { Text("• $it") }
                    }
                }
            }
            state.errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }

            if (vm.canSignOnScreen) {
                Button(onClick = onSign, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Подписать на экране") }
            }
            if (act == null && !state.eSignAllowed && state.status == RequestStatus.WORK_DONE) {
                OutlinedButton(onClick = onPaperPhoto, modifier = Modifier.fillMaxWidth()) {
                    Text("Сфотографировать бумажный акт (${state.paperPhotos})")
                }
            }
            if (vm.canClose) {
                Button(onClick = vm::close, enabled = !state.closing, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Закрыть заявку")
                }
            }
            state.pdf?.let { pdf ->
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, files.uriFor(pdf))
                        putExtra(Intent.EXTRA_SUBJECT, "Акт выполненных работ № ${state.number}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Отправить акт"))
                }, modifier = Modifier.fillMaxWidth()) { Text(if (act == null) "Поделиться черновиком" else "Поделиться") }
            }

            if (state.busy && state.pages.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            state.pages.forEach { page ->
                Image(page.asImageBitmap(), contentDescription = "Страница акта", contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}
