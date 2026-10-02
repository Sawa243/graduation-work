package ru.fieldengineer.app.ui.signature

import ru.fieldengineer.app.ui.SecureScreen
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Э7 «Подпись заказчика»: ФИО и должность, поле для подписи, «Очистить», «Подписать» → PIN инженера. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignatureScreen(vm: SignatureViewModel, onBack: () -> Unit, onSigned: () -> Unit) {
    SecureScreen()   // ФИО и подпись заказчика — снимки экрана запрещены
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onSigned() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Подпись заказчика") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(state.signerName, vm::onName, label = { Text("ФИО представителя заказчика") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.signerPosition, vm::onPosition, label = { Text("Должность") },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Подпись", style = MaterialTheme.typography.titleSmall)
            SignaturePad(state.strokes, vm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::clear) { Text("Очистить") }
            }
            Text("Подписывая, представитель заказчика подтверждает объём и качество работ по акту. " +
                "Инженер подтверждает подпись своим PIN-кодом.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

            state.errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
            Button(onClick = vm::sign, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (state.busy) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp) else Text("Подписать")
            }
        }
    }

    if (state.pinStep != PinStep.NONE) PinDialog(state.pinStep, state.pinError, vm::onPin, vm::dismissPin)
}

/** Поле для подписи: линии рисуются вслед за пальцем. */
@Composable
private fun SignaturePad(strokes: List<List<Offset>>, vm: SignatureViewModel) {
    Box(
        Modifier.fillMaxWidth().height(220.dp).background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .onSizeChanged(vm::onCanvasSize)
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = vm::startStroke) { change, _ -> vm.extendStroke(change.position) }
            },
    ) {
        if (strokes.isEmpty()) {
            Text("Распишитесь здесь", color = Color.LightGray, modifier = Modifier.align(Alignment.Center))
        }
        Canvas(Modifier.fillMaxSize()) {
            strokes.forEach { stroke ->
                val path = Path()
                stroke.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                drawPath(path, Color.Black, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun PinDialog(step: PinStep, error: String?, onPin: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember(step) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PIN инженера") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onPin(pin) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
