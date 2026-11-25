package com.witnessrecord.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.witnessrecord.app.R
import com.witnessrecord.app.model.CaseItem
import com.witnessrecord.app.model.DetailTemplate
import com.witnessrecord.app.model.EvidenceCase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One PDF for a whole case: every item in order, with its hash, its proof, and
 * the details and notes added afterwards.
 *
 * Like [EvidenceReport], it presents what is recorded and adds nothing. The
 * line between the two kinds of content is kept visible: what was captured and
 * hashed, and what the person wrote later.
 */
object CaseReport {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f

    /** The PDF plus every file that an independent check would run against, all in the cache for sharing. */
    fun build(context: Context, evidenceCase: EvidenceCase, items: List<CaseItem>): List<File> {
        val ordered = items.sortedBy { it.createdAt }
        val directory = File(context.cacheDir, "case-export").apply { deleteRecursively(); mkdirs() }

        val attachments = mutableListOf<File>()
        for (item in ordered) {
            val short = item.id.take(8)
            val sources: List<File> = if (item.isVideo && item.sessionID != null) {
                listOf(SessionArchive.manifestFile(item.sessionID), SessionArchive.timestampFile(item.sessionID))
            } else {
                listOfNotNull(
                    CaseLibrary.mediaFile(item), CaseLibrary.recordFile(item.id), CaseLibrary.timestampFile(item.id),
                )
            }
            for (source in sources.filter { it.exists() }) {
                val copy = File(directory, "$short-${source.name}")
                runCatching { source.copyTo(copy, overwrite = true) }.onSuccess { attachments.add(copy) }
            }
        }
        return listOf(pdf(context, directory, evidenceCase, ordered)) + attachments
    }

    private fun pdf(context: Context, directory: File, evidenceCase: EvidenceCase, items: List<CaseItem>): File {
        val safeTitle = evidenceCase.title.filterNot { it in "/\\:?*\"<>|" }.ifEmpty { "case" }
        val file = File(directory, "Witness case - $safeTitle.pdf")
        val document = PdfDocument()
        val writer = Writer(document)
        try {
            writer.newPage()
            writer.text(evidenceCase.title, 20f, bold = true)
            writer.text(
                context.getString(R.string.report_case_header, items.size, stamp(System.currentTimeMillis())),
                10f, color = Color.DKGRAY,
            )
            writer.gap(8f)
            writer.text(context.getString(R.string.report_case_disclaimer), 9f, color = Color.GRAY)
            writer.text(context.getString(R.string.report_case_verify), 9f, bold = true, color = Color.DKGRAY)

            items.forEachIndexed { index, item ->
                writer.gap(14f)
                writer.rule()
                writer.text("${index + 1}. ${kindName(context, item)} - ${stamp(item.createdAt)}", 13f, bold = true)
                item.title?.takeIf { it.isNotEmpty() }?.let { writer.field(context.getString(R.string.report_field_name), it) }
                item.endedAt?.takeIf { item.kind != CaseItem.KIND_PHOTO }?.let {
                    writer.field(context.getString(R.string.report_field_ended), stamp(it))
                }
                if (item.latitude != null && item.longitude != null) {
                    writer.field(
                        context.getString(R.string.report_field_location),
                        "%.5f, %.5f".format(Locale.US, item.latitude, item.longitude),
                    )
                }
                if (item.isVideo && item.sessionID != null) {
                    writer.field(context.getString(R.string.report_field_recording), item.sessionID)
                    SessionArchive.sessions().firstOrNull { it.sessionID == item.sessionID }?.let { manifest ->
                        writer.field(context.getString(R.string.report_field_segments), manifest.chunkCount.toString())
                        manifest.mergedVideos.orEmpty().forEach { writer.field(it.fileName, it.sha256 ?: "-", mono = true) }
                    }
                } else {
                    writer.field("SHA-256", item.sha256 ?: "-", mono = true)
                }
                writer.field(
                    context.getString(R.string.report_field_proof),
                    context.getString(if (item.timestamped) R.string.report_proof_yes else R.string.report_proof_no),
                )
                if (item.isImported) {
                    writer.text(context.getString(R.string.report_imported_note), 9f, italic = true, color = Color.DKGRAY)
                }
                val template = DetailTemplate.fromWire(item.template)
                val answered = template?.prompts.orEmpty().filter { !item.details[it.id].isNullOrEmpty() }
                if (answered.isNotEmpty()) {
                    writer.gap(4f)
                    writer.text(context.getString(R.string.report_details_after), 10f, bold = true)
                    answered.forEach { writer.field(context.getString(it.labelRes), item.details[it.id].orEmpty()) }
                }
                if (item.notes.isNotEmpty()) {
                    writer.gap(4f)
                    writer.text(context.getString(R.string.report_notes_after), 10f, bold = true)
                    item.notes.forEach { writer.field(stamp(it.createdAt), it.text) }
                }
            }
            writer.finish()
            file.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
        return file
    }

    private fun kindName(context: Context, item: CaseItem): String = context.getString(
        when (item.kind) {
            CaseItem.KIND_VIDEO -> R.string.item_recording
            CaseItem.KIND_PHOTO -> R.string.item_photo
            CaseItem.KIND_AUDIO -> R.string.item_audio
            else -> R.string.item_imported_file
        }
    )

    private fun stamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(millis))

    /** A plain top-to-bottom text layout over [PdfDocument] pages, wrapping and paginating as it goes. */
    private class Writer(private val document: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var pageNumber = 0
        private var y = MARGIN
        private val width = PAGE_WIDTH - 2 * MARGIN

        fun newPage() {
            finish()
            pageNumber += 1
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            y = MARGIN
        }

        fun finish() {
            page?.let { document.finishPage(it) }
            page = null
        }

        fun gap(h: Float) { y += h }

        private fun ensure(h: Float) { if (y + h > PAGE_HEIGHT - MARGIN) newPage() }

        fun rule() {
            ensure(8f)
            val paint = Paint().apply { color = Color.LTGRAY }
            page?.canvas?.drawRect(MARGIN, y, MARGIN + width, y + 0.5f, paint)
            y += 8f
        }

        fun text(s: String, size: Float, bold: Boolean = false, italic: Boolean = false, color: Int = Color.BLACK) {
            val layout = layout(s, size, width, bold, italic, false, color)
            ensure(layout.height + 3f)
            draw(layout, MARGIN, y)
            y += layout.height + 3f
        }

        fun field(label: String, value: String, mono: Boolean = false) {
            val labelLayout = layout(label, 9.5f, 124f, bold = true, italic = false, mono = false, color = Color.DKGRAY)
            val valueLayout = layout(value, if (mono) 8f else 9.5f, width - 130f, false, false, mono, Color.BLACK)
            val h = maxOf(labelLayout.height, valueLayout.height).toFloat()
            ensure(h + 3f)
            draw(labelLayout, MARGIN, y)
            draw(valueLayout, MARGIN + 130f, y)
            y += h + 3f
        }

        private fun draw(layout: StaticLayout, x: Float, top: Float) {
            val canvas = page?.canvas ?: return
            canvas.save()
            canvas.translate(x, top)
            layout.draw(canvas)
            canvas.restore()
        }

        private fun layout(
            s: String, size: Float, w: Float, bold: Boolean, italic: Boolean, mono: Boolean, color: Int,
        ): StaticLayout {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = Typeface.create(
                    if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF,
                    when {
                        bold && italic -> Typeface.BOLD_ITALIC
                        bold -> Typeface.BOLD
                        italic -> Typeface.ITALIC
                        else -> Typeface.NORMAL
                    },
                )
            }
            return StaticLayout.Builder.obtain(s, 0, s.length, paint, w.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        }
    }
}
