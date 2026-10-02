package ru.fieldengineer.app.ui.act

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.fieldengineer.app.data.db.ActEntity
import ru.fieldengineer.app.data.files.ActPdfWriter
import ru.fieldengineer.app.data.files.FileStore
import ru.fieldengineer.app.data.repo.RequestRepository
import ru.fieldengineer.app.domain.ActBuilder
import ru.fieldengineer.app.domain.RequestStatus
import java.io.File

data class ActUiState(
    val number: String = "",
    val status: Int = 0,
    val eSignAllowed: Boolean = true,
    val act: ActEntity? = null,
    val paperPhotos: Int = 0,
    val pdf: File? = null,
    val pages: List<Bitmap> = emptyList(),
    val missing: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val busy: Boolean = true,
    val closing: Boolean = false,
    val closed: Boolean = false,
)

/**
 * Э8 «Акт»: предпросмотр, «Подписать на экране» (если договор допускает простую ЭП) или
 * «Сфотографировать бумажный акт», «Поделиться», «Закрыть заявку».
 */
class ActViewModel(
    private val requestId: Int,
    private val engineerId: Int?,
    private val repository: RequestRepository,
    private val files: FileStore,
    private val writer: ActPdfWriter,
) : ViewModel() {

    private val _state = MutableStateFlow(ActUiState())
    val state: StateFlow<ActUiState> = _state

    init {
        viewModelScope.launch {
            combine(repository.observeCard(requestId), repository.observeAct(requestId), repository.observePhotos(requestId)) {
                card, act, photos -> Triple(card, act, photos.count { it.kind == RequestRepository.PHOTO_PAPER_ACT })
            }.distinctUntilChanged().collect { (card, act, paper) ->
                if (card == null) return@collect
                _state.update {
                    it.copy(number = ActBuilder.actNumber(card.request.number), status = card.request.status,
                        eSignAllowed = card.eSignAllowed, act = act, paperPhotos = paper)
                }
                refreshPreview(act, card.eSignAllowed)
            }
        }
    }

    /** Подписанный акт показываем как есть; до подписи — черновик с пометкой «не подписан». */
    private suspend fun refreshPreview(act: ActEntity?, eSign: Boolean) {
        val id = engineerId ?: return
        val (file, missing) = if (act != null) files.act(act.pdfFile) to emptyList()
            else makeDraft(repository, writer, files, requestId, id,
                if (eSign) ActBuilder.SIGN_ON_SCREEN else ActBuilder.SIGN_ON_PAPER)
        val pages = file?.takeIf(File::exists)?.let { renderPdf(it, PREVIEW_WIDTH) }.orEmpty()
        _state.update { it.copy(pdf = file, pages = pages, missing = missing, busy = false) }
    }

    /** Закрыть заявку. Для бумажного акта сначала оформляется act по фото подписанного экземпляра. */
    fun close() {
        val id = engineerId ?: return
        val s = _state.value
        if (s.closing) return   // повторное нажатие «Закрыть заявку»
        _state.update { it.copy(closing = true, errors = emptyList()) }
        viewModelScope.launch {
            var errors = emptyList<String>()
            if (s.act == null && !s.eSignAllowed) {
                errors = if (s.paperPhotos == 0) listOf("Сфотографируйте подписанный бумажный акт")
                    else makeAct(repository, writer, files, requestId, id, ActBuilder.SIGN_ON_PAPER)
            }
            if (errors.isEmpty()) errors = repository.closeRequest(requestId, id)
            _state.update { it.copy(closing = false, errors = errors, closed = errors.isEmpty()) }
        }
    }

    val canSignOnScreen: Boolean
        get() = with(_state.value) { act == null && eSignAllowed && status == RequestStatus.WORK_DONE && missing.isEmpty() }

    val canClose: Boolean
        get() = with(_state.value) {
            status == RequestStatus.WORK_DONE && (act != null || (!eSignAllowed && paperPhotos > 0 && missing.isEmpty()))
        }

    private companion object {
        const val PREVIEW_WIDTH = 900
    }
}
