package ru.fieldengineer.app.ui.photo

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.fieldengineer.app.data.db.PhotoEntity
import ru.fieldengineer.app.ui.SecureScreen
import ru.fieldengineer.app.ui.moscow

/** Э6 «Фотографии»: миниатюры, «Снять», удаление. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoScreen(vm: PhotoViewModel, onBack: () -> Unit) {
    SecureScreen()   // фото бумажного акта (ФИО, подпись заказчика) и объектов — снимки экрана запрещены
    val state by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<PhotoEntity?>(null) }
    val context = LocalContext.current
    // снимает системная камера; файл заранее создан во внутреннем хранилище приложения
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), vm::onShot)
    val shoot = {
        try {
            camera.launch(vm.prepareShot())
        } catch (e: ActivityNotFoundException) {
            vm.onCameraError("На устройстве нет приложения камеры")
        } catch (e: SecurityException) {
            vm.onCameraError("Нет разрешения на камеру")
        }
    }
    // разрешение на камеру объявлено в манифесте (для сканера), поэтому и системной камере оно нужно
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) shoot() else vm.onCameraError("Без разрешения на камеру фото не снять")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title)
                        if (state.subtitle.isNotEmpty()) {
                            Text(state.subtitle, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                        }
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Назад") } },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.canEdit) {
                        Button(onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED) shoot() else permission.launch(Manifest.permission.CAMERA)
                        },
                            modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Снять") }
                    } else {
                        Text("Добавлять и удалять фото сейчас нельзя", color = MaterialTheme.colorScheme.secondary)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.photos.isEmpty()) Text("Фото пока нет", color = MaterialTheme.colorScheme.secondary)
                }
            }
            items(state.photos, key = { it.id }) { photo ->
                PhotoCard(photo, vm, state.itemTitles[photo.checklistItemId], state.canEdit, onDelete = { toDelete = photo })
            }
        }
    }

    toDelete?.let { photo ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить фото?") },
            confirmButton = { TextButton(onClick = { vm.delete(photo); toDelete = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun PhotoCard(photo: PhotoEntity, vm: PhotoViewModel, itemTitle: String?, canEdit: Boolean, onDelete: () -> Unit) {
    // миниатюра грузится в фоне, чтобы большой JPEG не тормозил прокрутку
    val bitmap by produceState<ImageBitmap?>(null, photo.id) {
        value = withContext(Dispatchers.IO) { vm.thumbnail(photo)?.asImageBitmap() }
    }
    Card {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it, contentDescription = "Фото", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: Text("…")
        }
        Column(Modifier.padding(8.dp)) {
            Text(photo.takenAt.moscow(), style = MaterialTheme.typography.labelMedium)
            itemTitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            if (canEdit) TextButton(onClick = onDelete, contentPadding = PaddingValues(0.dp)) { Text("Удалить") }
        }
    }
}
