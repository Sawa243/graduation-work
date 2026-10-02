package ru.fieldengineer.app.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Файлы приложения во внутреннем хранилище (не в галерее, другим приложениям недоступны):
 * фото работ, изображения подписей, акты PDF. Наружу файл выдаётся только через FileProvider
 * и только на время действия (камере — чтобы сохранить снимок, почте — чтобы отправить акт).
 */
class FileStore(private val context: Context) {
    private fun dir(name: String) = File(context.filesDir, name).apply { mkdirs() }

    val photos: File get() = dir("photos")
    val signatures: File get() = dir("signatures")
    val acts: File get() = dir("acts")

    fun newPhotoFile(): File = File(photos, "${UUID.randomUUID()}.jpg")
    fun photo(name: String): File = File(photos, name)
    fun signature(name: String): File = File(signatures, name)
    fun act(name: String): File = File(acts, name)

    /** Удалить файлы заявки, которую сняли с инженера (фото, подпись, акт). */
    fun deleteFiles(photoNames: List<String>, signatureNames: List<String>, actNames: List<String>) {
        photoNames.forEach { photo(it).delete() }
        signatureNames.forEach { signature(it).delete() }
        actNames.forEach { act(it).delete() }
    }

    /** Удалить все файлы приложения — при выходе (в них персональные данные заказчиков). */
    fun clearAll() {
        listOf("photos", "signatures", "acts").forEach { File(context.filesDir, it).deleteRecursively() }
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** Контрольная сумма SHA-256 файла (64 шестнадцатеричных знака) — для фото и актов. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Уменьшенная копия снимка для миниатюры: большой JPEG целиком в память не грузим. */
    fun thumbnail(file: File, maxSide: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
