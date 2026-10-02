package ru.fieldengineer.app.ui.photo

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.fieldengineer.app.data.db.PhotoEntity
import ru.fieldengineer.app.data.files.FileStore
import ru.fieldengineer.app.data.repo.RequestRepository
import java.io.File

data class PhotoUiState(
    val title: String = "Фотографии",
    val subtitle: String = "",
    val photos: List<PhotoEntity> = emptyList(),
    val itemTitles: Map<Int, String> = emptyMap(),
    val canEdit: Boolean = false,
)

/**
 * Э6 «Фотографии». [itemId] — фото к пункту чек-листа (null — все фото работ по заявке),
 * [kind] — 1 фото работ, 2 фото подписанного бумажного акта.
 */
class PhotoViewModel(
    private val requestId: Int,
    private val itemId: Int?,
    private val kind: Int,
    private val repository: RequestRepository,
    private val files: FileStore,
    private val saved: SavedStateHandle,
) : ViewModel() {

    val error = MutableStateFlow<String?>(null)

    // куда камера пишет снимок: хранится в SavedStateHandle, потому что пока открыта системная
    // камера, Android может выгрузить наш процесс — после возврата путь должен сохраниться
    private var pending: File?
        get() = saved.get<String>(KEY_PENDING)?.let(::File)
        set(value) { saved[KEY_PENDING] = value?.path }

    val state: StateFlow<PhotoUiState> = combine(
        repository.observeCard(requestId),
        repository.observePhotos(requestId),
        repository.observeChecklist(requestId),
    ) { card, photos, checklist ->
        val items = checklist.associate { it.itemId to "${it.pos}. ${it.text}" }
        PhotoUiState(
            title = if (kind == RequestRepository.PHOTO_PAPER_ACT) "Бумажный акт" else "Фотографии",
            subtitle = listOfNotNull(card?.request?.number, itemId?.let { items[it] }).joinToString(" · "),
            photos = photos.filter { it.kind == kind && (itemId == null || it.checklistItemId == itemId) },
            itemTitles = items,
            canEdit = card != null && repository.canEditPhotos(card.request.status, kind),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhotoUiState())

    /** Миниатюра до 400 пикселей по большей стороне. */
    fun thumbnail(photo: PhotoEntity): Bitmap? = files.thumbnail(files.photo(photo.fileName), 400)

    /** Куда системной камере сохранить снимок. */
    fun prepareShot(): Uri {
        val file = files.newPhotoFile()
        pending = file
        return files.uriFor(file)
    }

    /** Камера вернулась: снимок есть — считаем SHA-256 и записываем, нет — удаляем пустой файл. */
    fun onShot(success: Boolean) {
        val file = pending ?: return
        pending = null
        viewModelScope.launch {
            if (!success || !file.exists() || file.length() == 0L) {
                file.delete()
                return@launch
            }
            val sha = withContext(Dispatchers.IO) { files.sha256(file) }
            repository.addPhoto(requestId, itemId, kind, file, sha)?.let {
                error.value = it
                file.delete()
            }
        }
    }

    /** Фото сразу пропадает с экрана; запись и файл стираются, когда сервер примет удаление. */
    fun delete(photo: PhotoEntity) {
        viewModelScope.launch { repository.deletePhoto(photo.id) }
    }

    /** Камеру не удалось открыть — убираем пустой файл и показываем причину. */
    fun onCameraError(message: String) {
        pending?.delete()
        pending = null
        error.value = message
    }

    private companion object {
        const val KEY_PENDING = "pending_photo"
    }
}
