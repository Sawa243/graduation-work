package ru.fieldengineer.app.data.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withTranslation
import ru.fieldengineer.app.domain.ActBuilder
import ru.fieldengineer.app.domain.ActContent
import ru.fieldengineer.app.domain.MOSCOW
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Рисует акт выполненных работ в PDF (формат A4, 595 × 842 пт) средствами Android —
 * android.graphics.pdf.PdfDocument, без сторонних библиотек. Возвращает SHA-256 файла.
 */
class ActPdfWriter(private val files: FileStore) {

    /**
     * [signedAt] и [signature] — для подписанного на экране акта; для черновика оба null,
     * и на акте печатается пометка «Черновик — не подписан».
     */
    fun write(content: ActContent, out: File, signature: File?, signedAt: Instant?, createdAt: Instant): String {
        val doc = PdfDocument()
        val pager = Pager(doc)
        try {
            pager.text("${content.title} № ${content.number}", bold = true, size = 16f, center = true)
            pager.text("от ${DATE.format(content.date)}", size = 11f, center = true, after = 14f)

            content.fields.forEach { pager.field(it.name, it.value) }
            pager.space(8f)

            pager.text("Выполненные работы", bold = true)
            pager.text(content.resultText)
            if (content.doneItems.isNotEmpty()) {
                pager.space(4f)
                pager.text("Выполнено по чек-листу", bold = true)
                content.doneItems.forEachIndexed { i, item -> pager.text("${i + 1}. $item", indent = 10f) }
            }
            pager.space(4f)
            pager.field("Трудозатраты на объекте", "${content.workMin} мин")
            pager.space(6f)
            pager.text("Работы выполнены в указанном объёме. Заказчик претензий по объёму и качеству " +
                "выполненных работ не имеет.", size = 10f, after = 16f)

            pager.signatures(content, signature, signedAt)

            pager.space(12f)
            pager.text("Сформировано в приложении «Выездной инженер» ${DATE_TIME.format(createdAt.atZone(MOSCOW))} МСК",
                size = 8f, color = Color.DKGRAY)
            pager.finish()
            out.parentFile?.mkdirs()
            out.outputStream().use(doc::writeTo)
        } finally {
            doc.close()
        }
        return files.sha256(out)
    }

    /** Раскладка текста сверху вниз с переходом на новую страницу. */
    private class Pager(private val doc: PdfDocument) {
        private var pageNo = 0
        private var page: PdfDocument.Page = newPage()
        private var y = MARGIN

        private val canvas: Canvas get() = page.canvas

        private fun newPage(): PdfDocument.Page {
            pageNo += 1
            return doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        }

        private fun ensure(height: Float) {
            if (y + height > PAGE_H - MARGIN) {
                doc.finishPage(page)
                page = newPage()
                y = MARGIN
            }
        }

        fun space(h: Float) {
            y += h
        }

        fun text(
            value: String, bold: Boolean = false, size: Float = 11f, center: Boolean = false,
            indent: Float = 0f, after: Float = 4f, color: Int = Color.BLACK,
        ) {
            val layout = layout(value, paint(size, bold, color), (CONTENT_W - indent).toInt(), center)
            ensure(layout.height.toFloat())
            canvas.withTranslation(MARGIN + indent, y) { layout.draw(this) }
            y += layout.height + after
        }

        /** Реквизит: название слева жирным, значение справа с переносом. */
        fun field(name: String, value: String) {
            val left = layout(name, paint(10f, true), LABEL_W.toInt(), false)
            val right = layout(value, paint(11f, false), (CONTENT_W - LABEL_W - 8f).toInt(), false)
            val h = maxOf(left.height, right.height).toFloat()
            ensure(h)
            canvas.withTranslation(MARGIN, y + 1f) { left.draw(this) }
            canvas.withTranslation(MARGIN + LABEL_W + 8f, y) { right.draw(this) }
            y += h + 4f
        }

        fun signatures(content: ActContent, signature: File?, signedAt: Instant?) {
            ensure(170f)
            text("Исполнитель", bold = true, after = 2f)
            text(content.engineerLine)
            text(
                if (signedAt != null) "Подписано простой электронной подписью (вход по паролю и PIN-код) " +
                    "${DATE_TIME.format(signedAt.atZone(MOSCOW))} МСК"
                else if (content.signMode == ActBuilder.SIGN_ON_PAPER) "Подпись — на бумажном экземпляре"
                else "Черновик — не подписан",
                size = 9f, color = Color.DKGRAY, after = 10f,
            )
            text("Представитель заказчика", bold = true, after = 2f)
            if (content.signMode == ActBuilder.SIGN_ON_PAPER) {
                text("Подписи сторон — на бумажном экземпляре акта, его фотография приложена к заявке", size = 10f)
                return
            }
            text(content.signerLine ?: "____________________")
            val box = RectF(MARGIN, y, MARGIN + 220f, y + 80f)
            val bitmap = signature?.takeIf(File::exists)?.let { BitmapFactory.decodeFile(it.path) }
            if (bitmap != null) {
                drawFit(bitmap, box)
            } else {
                canvas.drawLine(box.left, box.bottom - 10f, box.right, box.bottom - 10f, paint(10f, false))
            }
            y = box.bottom + 4f
        }

        private fun drawFit(bitmap: Bitmap, box: RectF) {
            val scale = minOf(box.width() / bitmap.width, box.height() / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            canvas.drawBitmap(bitmap, null, RectF(box.left, box.top, box.left + w, box.top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        }

        fun finish() = doc.finishPage(page)

        private fun paint(size: Float, bold: Boolean, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        private fun layout(text: String, paint: TextPaint, width: Int, center: Boolean): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(1f, 1.1f)
                .build()
    }

    private companion object {
        const val PAGE_W = 595
        const val PAGE_H = 842
        const val MARGIN = 50f
        const val CONTENT_W = PAGE_W - 2 * MARGIN
        const val LABEL_W = 130f
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    }
}
