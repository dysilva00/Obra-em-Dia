package pt.obraemdia.app.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.OutputStream

/**
 * Desenha um [Report] num PDF A4 com o PdfDocument do Android (sem bibliotecas externas).
 * Quebra linhas longas, muda de página quando necessário e escreve o aviso legal em todas as páginas.
 */
object PdfWriter {

    private const val PAGE_W = 595 // A4 em pontos
    private const val PAGE_H = 842
    private const val MARGIN = 48f
    private const val FOOTER = 36f
    private const val COL_W = 92f // colunas numéricas das tabelas

    private val petrol = Color.rgb(0x0B, 0x5D, 0x5A)

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private val titleP = paint(18f, bold = true, color = petrol)
    private val subtitleP = paint(10f, color = Color.DKGRAY)
    private val headingP = paint(12f, bold = true, color = petrol)
    private val bodyP = paint(10f)
    private val boldP = paint(10f, bold = true)
    private val smallP = paint(8.5f, color = Color.DKGRAY)
    private val ruleP = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.7f }

    fun write(report: Report, out: OutputStream) {
        val doc = PdfDocument()
        try {
            Layout(doc, report).run()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    fun write(report: Report, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { write(report, it) }
    }

    /** Estado de paginação: página atual e posição vertical. */
    private class Layout(private val doc: PdfDocument, private val report: Report) {
        private var pageNo = 0
        private lateinit var page: PdfDocument.Page
        private var y = 0f
        private val width = PAGE_W - 2 * MARGIN
        private val bottom = PAGE_H - MARGIN - FOOTER

        fun run() {
            newPage()
            line(report.title, titleP, 24f)
            line(report.subtitle, subtitleP, 16f)
            y += 6f
            report.blocks.forEach { block ->
                when (block) {
                    is Block.Heading -> {
                        ensure(40f) // título de secção não fica sozinho no fim da página
                        y += 10f
                        line(block.text, headingP, 18f)
                        // Abaixo das letras descendentes (ç, p, parênteses).
                        page.canvas.drawLine(MARGIN, y + 1f, MARGIN + width, y + 1f, ruleP)
                        y += 6f
                    }
                    is Block.Line -> labelValue(block.label, block.value, if (block.bold) boldP else bodyP)
                    is Block.Row -> row(block.cells, if (block.header) boldP else bodyP)
                    is Block.Text -> wrapped(block.text, if (block.small) smallP else bodyP)
                    Block.Gap -> y += 8f
                }
            }
            doc.finishPage(page)
        }

        private fun newPage() {
            if (pageNo > 0) doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            y = MARGIN + 6f
            // Rodapé: aviso de que não é documento fiscal, em todas as páginas.
            val c = page.canvas
            val fy = PAGE_H - MARGIN + 6f
            c.drawLine(MARGIN, fy - 14f, MARGIN + width, fy - 14f, ruleP)
            c.drawText(NOT_AN_INVOICE, MARGIN, fy, smallP)
            val label = "Obra em Dia · página $pageNo"
            c.drawText(label, MARGIN + width - smallP.measureText(label), fy, smallP)
        }

        private fun ensure(height: Float) {
            if (y + height > bottom) newPage()
        }

        private fun line(text: String, p: Paint, height: Float) {
            ensure(height)
            y += height - 4f
            page.canvas.drawText(text, MARGIN, y, p)
            y += 4f
        }

        private fun wrap(text: String, p: Paint, maxWidth: Float): List<String> {
            val out = mutableListOf<String>()
            text.split('\n').forEach { para ->
                var rest = para
                while (rest.isNotEmpty()) {
                    var n = p.breakText(rest, true, maxWidth, null)
                    if (n < rest.length) {
                        val space = rest.lastIndexOf(' ', n)
                        if (space > 0) n = space
                    }
                    out += rest.substring(0, n).trimEnd()
                    rest = rest.substring(n).trimStart()
                }
                if (para.isEmpty()) out += ""
            }
            return out
        }

        private fun wrapped(text: String, p: Paint) {
            wrap(text, p, width).forEach { line(it, p, p.textSize + 5f) }
        }

        /** Rótulo à esquerda (quebra se for longo) e valor alinhado à direita na primeira linha. */
        private fun labelValue(label: String, value: String, p: Paint) {
            val valueW = p.measureText(value)
            val lines = wrap(label, p, width - valueW - 12f)
            val h = p.textSize + 6f
            ensure(h * lines.size)
            lines.forEachIndexed { i, l ->
                y += h - 4f
                page.canvas.drawText(l, MARGIN, y, p)
                if (i == 0) page.canvas.drawText(value, MARGIN + width - valueW, y, p)
                y += 4f
            }
        }

        private fun row(cells: List<String>, p: Paint) {
            val numeric = cells.drop(1)
            val firstW = width - numeric.size * COL_W
            val lines = wrap(cells.first(), p, firstW - 8f)
            val h = p.textSize + 6f
            ensure(h * lines.size)
            lines.forEachIndexed { i, l ->
                y += h - 4f
                page.canvas.drawText(l, MARGIN, y, p)
                if (i == 0) {
                    numeric.forEachIndexed { c, text ->
                        val right = MARGIN + firstW + (c + 1) * COL_W
                        page.canvas.drawText(text, right - p.measureText(text), y, p)
                    }
                }
                y += 4f
            }
        }
    }
}
