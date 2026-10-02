package ru.fieldengineer.app.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.Executors
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import ru.fieldengineer.app.domain.CodeKind
import ru.fieldengineer.app.domain.ScanMatch
import ru.fieldengineer.app.ui.ColorAtRisk
import ru.fieldengineer.app.ui.ColorInTime

/** Э5 «Сканер»: камера с рамкой, найденный код и сверка с оборудованием объекта. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(vm: ScannerViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    // разрешение на камеру спрашиваем в момент, когда инженер открыл сканер
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askPermission.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Сканер оборудования") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // clipToBounds: картинка камеры не вылезает за своё окно на остальной экран
            Box(Modifier.fillMaxWidth().height(300.dp).clipToBounds(), contentAlignment = Alignment.Center) {
                if (granted && state.match == null) {
                    CameraPreview(onCode = { if (!vm.paused) vm.onCode(it) })
                    Box(Modifier.size(220.dp).border(3.dp, Color.White, MaterialTheme.shapes.medium))
                } else if (!granted) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Нет доступа к камере — введите код вручную", color = MaterialTheme.colorScheme.secondary)
                        TextButton(onClick = { askPermission.launch(Manifest.permission.CAMERA) }) { Text("Разрешить камеру") }
                    }
                }
                state.match?.let { ResultCard(it) }
            }

            if (state.match == null) {
                Text("Наведите камеру на QR-метку BK-EQ-… или штрихкод заводского номера. " +
                    "Если метка повреждена — введите код вручную.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = state.manualCode, onValueChange = vm::onManualChange,
                        label = { Text("Код вручную") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { vm.onCode(state.manualCode) }) { Text("Проверить") }
                }
            } else {
                Button(onClick = vm::save, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Сохранить в заявке")
                }
                OutlinedButton(onClick = vm::rescan, modifier = Modifier.fillMaxWidth()) { Text("Сканировать ещё раз") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ResultCard(match: ScanMatch) {
    val (title, text, color) = when (match) {
        is ScanMatch.Found -> Triple(
            if (match.isRequestEquipment) "Оборудование заявки" else "Другое оборудование объекта",
            "${match.equipment.title}\nметка ${match.equipment.invCode}, зав. № ${match.equipment.serialNo ?: "—"}",
            if (match.isRequestEquipment) ColorInTime else ColorAtRisk,
        )
        is ScanMatch.NotFound -> Triple(
            "На объекте не найдено",
            "Код сохранится в заявке, диспетчер добавит оборудование в справочник",
            ColorAtRisk,
        )
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(16.dp)) {
            Text(if (match.code.kind == CodeKind.COMPANY_LABEL) "Метка компании" else "Заводской номер",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Text(match.code.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(title, color = color, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Превью камеры (CameraX) и распознавание кодов (ML Kit со встроенной моделью). */
@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        // TextureView вместо SurfaceView: превью попадает в снимки экрана (нужны для 2.4)
        PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_CODE_128, Barcode.FORMAT_EAN_13)
                .build(),
        )
    }

    // анализ кадров — в отдельном потоке, чтобы не тормозить интерфейс
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        cameraProvider = provider
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)   // старые кадры пропускаем
            .build()
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            val media = proxy.image
            if (media == null) {
                proxy.close()
                return@setAnalyzer
            }
            scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener(ContextCompat.getMainExecutor(context)) { codes ->
                    codes.firstNotNullOfOrNull { it.rawValue }?.let(onCode)
                }
                .addOnCompleteListener { proxy.close() }
        }
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }
    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            scanner.close()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
