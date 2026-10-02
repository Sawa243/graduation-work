package ru.fieldengineer.app.ui.act

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.fieldengineer.app.data.db.ActEntity
import ru.fieldengineer.app.data.files.ActPdfWriter
import ru.fieldengineer.app.data.files.FileStore
import ru.fieldengineer.app.data.repo.RequestRepository
import ru.fieldengineer.app.domain.ActBuilder
import java.io.File
import java.util.UUID

/**
 * Оформить акт: собрать данные, проверить реквизиты (ActBuilder), нарисовать PDF (ActPdfWriter),
 * посчитать SHA-256 и записать act. Возвращает список проблем (пустой — акт сохранён).
 * Вызывается с экрана подписи (на экране) и с экрана акта (бумажный акт).
 */
suspend fun makeAct(
    repository: RequestRepository, writer: ActPdfWriter, files: FileStore,
    requestId: Int, engineerId: Int, signMode: Int,
    signerName: String? = null, signerPosition: String? = null, signature: File? = null,
): List<String> {
    if (repository.actExists(requestId)) return listOf("Акт по заявке уже оформлен")
    val now = repository.now()
    val signedAt = if (signMode == ActBuilder.SIGN_ON_SCREEN) now.time else null
    val input = repository.actInput(requestId, engineerId, signMode)?.copy(
        signerName = signerName?.trim(), signerPosition = signerPosition?.trim(),
        hasSignature = signature?.exists() == true, signedAt = signedAt,
    ) ?: return listOf("Заявка не найдена")
    val missing = ActBuilder.missingRequisites(input)
    if (missing.isNotEmpty()) return missing.map { "Не заполнен реквизит акта: $it" }

    val content = ActBuilder.build(input)
    // пишем во временный файл: готовый акт на диске появляется, только когда запись act сохранена
    val pdf = files.act("${content.number}.pdf")
    val tmp = files.act("${content.number}.pdf.tmp")
    val sha = withContext(Dispatchers.IO) { writer.write(content, tmp, signature, signedAt, now.time) }
    val error = repository.saveAct(ActEntity(
        id = UUID.randomUUID().toString(), requestId = requestId, number = content.number,
        actDate = content.date, engineerId = engineerId, signMode = signMode,
        signerName = input.signerName, signerPosition = input.signerPosition,
        signatureFile = signature?.name, pdfFile = pdf.name, signedAt = signedAt,
        sha256 = sha, workMin = content.workMin, updatedAt = now.time,
    ))
    withContext(Dispatchers.IO) { if (error == null) tmp.renameTo(pdf) else tmp.delete() }
    return listOfNotNull(error)
}

/** Черновик акта для предпросмотра до подписи; возвращает файл и недостающие реквизиты. */
suspend fun makeDraft(
    repository: RequestRepository, writer: ActPdfWriter, files: FileStore,
    requestId: Int, engineerId: Int, signMode: Int,
): Pair<File?, List<String>> {
    // для черновика подпись заказчика не проверяем — её ещё нет
    val input = repository.actInput(requestId, engineerId, ActBuilder.SIGN_ON_PAPER)
        ?: return null to listOf("Заявка не найдена")
    val missing = ActBuilder.missingRequisites(input)
    val file = files.act("draft_$requestId.pdf")
    withContext(Dispatchers.IO) {
        writer.write(ActBuilder.build(input.copy(signMode = signMode)), file, null, null, repository.now().time)
    }
    return file to missing
}

/** Страницы PDF картинками для предпросмотра (PdfRenderer — штатный рендер Android). */
suspend fun renderPdf(file: File, width: Int): List<Bitmap> = withContext(Dispatchers.IO) {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            (0 until renderer.pageCount).map { i ->
                renderer.openPage(i).use { page ->
                    val height = width * page.height / page.width
                    createBitmap(width, height).also { bmp ->
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }
}
