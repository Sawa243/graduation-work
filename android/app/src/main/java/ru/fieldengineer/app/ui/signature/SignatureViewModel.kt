package ru.fieldengineer.app.ui.signature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.fieldengineer.app.data.files.ActPdfWriter
import ru.fieldengineer.app.data.files.FileStore
import ru.fieldengineer.app.data.repo.AuthRepository
import ru.fieldengineer.app.data.repo.PinStore
import ru.fieldengineer.app.data.repo.RequestRepository
import ru.fieldengineer.app.domain.ActBuilder
import ru.fieldengineer.app.ui.act.makeAct
import java.io.File

/** Открыт ли диалог ввода PIN. PIN здесь не задаётся — он задан сразу после входа по паролю. */
enum class PinStep { NONE, ENTER }

data class SignatureUiState(
    val signerName: String = "",
    val signerPosition: String = "",
    val strokes: List<List<Offset>> = emptyList(),
    val pinStep: PinStep = PinStep.NONE,
    val pinError: String? = null,
    val errors: List<String> = emptyList(),
    val busy: Boolean = false,
    val done: Boolean = false,
)

/**
 * Э7 «Подпись заказчика»: представитель расписывается пальцем, инженер подтверждает PIN-кодом,
 * после чего собирается и сохраняется подписанный акт.
 */
class SignatureViewModel(
    private val requestId: Int,
    private val engineerId: Int?,
    private val repository: RequestRepository,
    private val files: FileStore,
    private val pins: PinStore,
    private val writer: ActPdfWriter,
    /** Блокировка сеанса (как на Э0): токены удаляются, неотправленные данные остаются. */
    private val lockOut: suspend (String) -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(SignatureUiState())
    val state: StateFlow<SignatureUiState> = _state

    private var canvasSize = IntSize.Zero

    // длины — как столбцы act.signer_name и act.signer_position (varchar(100)): длиннее сервер не примет
    fun onName(v: String) = _state.update { it.copy(signerName = v.take(MAX_SIGNER), errors = emptyList()) }
    fun onPosition(v: String) = _state.update { it.copy(signerPosition = v.take(MAX_SIGNER), errors = emptyList()) }
    fun onCanvasSize(size: IntSize) { canvasSize = size }

    fun startStroke(p: Offset) = _state.update { it.copy(strokes = it.strokes + listOf(listOf(p)), errors = emptyList()) }
    fun extendStroke(p: Offset) = _state.update { s ->
        if (s.strokes.isEmpty()) s else s.copy(strokes = s.strokes.dropLast(1) + listOf(s.strokes.last() + p))
    }
    fun clear() = _state.update { it.copy(strokes = emptyList()) }

    /** «Подписать»: проверяем поля и открываем подтверждение PIN. */
    fun sign() {
        val s = _state.value
        if (s.busy) return
        val problems = buildList {
            if (s.signerName.isBlank()) add("Укажите ФИО представителя заказчика")
            if (s.signerPosition.isBlank()) add("Укажите его должность")
            if (s.strokes.sumOf { it.size } < MIN_POINTS) add("Нет подписи — распишитесь в поле")
            if (engineerId == null) add("Сеанс завершён — войдите заново")
        }
        if (problems.isNotEmpty()) {
            _state.update { it.copy(errors = problems) }
            return
        }
        val id = engineerId!!
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            // hasPin может обратиться к Android Keystore — не на главном потоке
            val hasPin = withContext(Dispatchers.Default) { pins.hasPin(id) }
            when {
                pins.isLocked(id) -> endSession(PIN_LOCKED)
                // PIN всегда задаётся после входа; нет его (пропал ключ Keystore) — только вход по паролю
                !hasPin -> endSession(NO_PIN)
                else -> _state.update { it.copy(busy = false, pinStep = PinStep.ENTER, pinError = null) }
            }
        }
    }

    fun dismissPin() = _state.update { it.copy(pinStep = PinStep.NONE, pinError = null) }

    fun onPin(pin: String) {
        val id = engineerId ?: return
        if (_state.value.busy) return   // повторное нажатие «Готово», пока идёт проверка
        when (_state.value.pinStep) {
            PinStep.ENTER -> {
                _state.update { it.copy(busy = true) }
                viewModelScope.launch {
                    val ok = withContext(Dispatchers.Default) { pins.verify(id, pin) }
                    when {
                        ok -> finish(id)
                        // 5 ошибок: сеанс блокируется, навигация сама откроет Э1 с пояснением
                        pins.isLocked(id) -> endSession(PIN_LOCKED)
                        else -> _state.update { it.copy(busy = false, pinError = "Неверный PIN") }
                    }
                }
            }
            PinStep.NONE -> Unit
        }
    }

    /** Блокировка сеанса: навигация сама откроет Э1 с пояснением. */
    private suspend fun endSession(notice: String) {
        _state.update { it.copy(busy = false, pinStep = PinStep.NONE, pinError = null) }
        lockOut(notice)
    }

    private suspend fun finish(engineerId: Int) {
        _state.update { it.copy(pinStep = PinStep.NONE, busy = true) }
        val s = _state.value
        val signature = withContext(Dispatchers.IO) { saveSignature(s.strokes) }
        val errors = makeAct(
            repository, writer, files, requestId, engineerId, ActBuilder.SIGN_ON_SCREEN,
            signerName = s.signerName, signerPosition = s.signerPosition, signature = signature,
        )
        // акт не оформлен — изображение подписи (ПДн) не оставляем на диске
        if (errors.isNotEmpty()) withContext(Dispatchers.IO) { signature.delete() }
        _state.update { it.copy(busy = false, errors = errors, done = errors.isEmpty()) }
    }

    /** Подпись — PNG с чёрными линиями на белом фоне, пропорции поля сохраняются. */
    private fun saveSignature(strokes: List<List<Offset>>): File {
        val w = 900
        val h = if (canvasSize.width > 0) w * canvasSize.height / canvasSize.width else 300
        val scale = if (canvasSize.width > 0) w.toFloat() / canvasSize.width else 1f
        val bitmap = createBitmap(w, h)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        strokes.forEach { stroke ->
            val path = Path()
            stroke.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x * scale, p.y * scale) else path.lineTo(p.x * scale, p.y * scale)
            }
            canvas.drawPath(path, paint)
        }
        val file = files.signature("signature_$requestId.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private companion object {
        const val MIN_POINTS = 10
        const val MAX_SIGNER = 100
        const val PIN_LOCKED = "PIN введён неверно ${PinStore.MAX_FAILS} раз при подписи акта. " +
            "Войдите по паролю и задайте новый PIN. ${AuthRepository.KEPT}"
        const val NO_PIN = "PIN в телефоне не найден. Войдите по паролю и задайте новый PIN. ${AuthRepository.KEPT}"
    }
}
