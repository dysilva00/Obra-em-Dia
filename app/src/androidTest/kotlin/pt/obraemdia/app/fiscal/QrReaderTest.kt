package pt.obraemdia.app.fiscal

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pt.obraemdia.domain.fiscalqr.AtQrResult
import pt.obraemdia.domain.money.Money
import java.io.File

@RunWith(AndroidJUnit4::class)
class QrReaderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "qr-test").apply { mkdirs() }

    companion object {
        /** Fatura-recibo de exemplo (valores fictícios), no formato da AT. */
        const val SAMPLE =
            "A:123456789*B:501964843*C:PT*D:FR*E:N*F:20260310*G:FR ATSIRE01FR/12*H:JJ8KH3RR-12*" +
                "I1:PT*I7:1500.00*I8:345.00*N:345.00*O:1845.00*P:375.00*Q:Ab3x*R:0"

        /** QR como os programas certificados o geram (ECC M), em bitmap preto e branco. */
        fun qrBitmap(text: String, size: Int): Bitmap {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
            return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
                for (x in 0 until size) for (y in 0 until size) b.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }

        /** PDF A4 com texto e o QR de 30 mm no canto inferior direito da página [qrPage]. */
        fun invoicePdf(file: File, text: String, pages: Int = 1, qrPage: Int = 0) {
            val doc = PdfDocument()
            val mm = 72f / 25.4f
            repeat(pages) { i ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
                val c = page.canvas
                c.drawText("FATURA-RECIBO de exemplo · página ${i + 1}", 48f, 80f, Paint().apply { textSize = 14f })
                if (i == qrPage) {
                    val side = 30 * mm
                    c.drawBitmap(qrBitmap(text, 400), null, RectF(595 - 48 - side, 842 - 48 - side, 595 - 48f, 842 - 48f), null)
                }
                doc.finishPage(page)
            }
            file.outputStream().use(doc::writeTo)
            doc.close()
        }
    }

    @Test
    fun le_o_total_do_qr_num_pdf_de_uma_pagina() = runTest {
        val f = File(dir, "uma.pdf").also { invoicePdf(it, SAMPLE) }
        val r = QrReader.fromPdf(f)
        assertTrue(r.toString(), r is AtQrResult.Ok)
        val d = (r as AtQrResult.Ok).document
        assertEquals(Money(184500), d.gross)
        assertEquals(Money(150000), d.net)
        assertEquals(Money(37500), d.withholding)
        assertEquals("FR", d.docType)
    }

    @Test
    fun qr_na_ultima_pagina() = runTest {
        val f = File(dir, "tres.pdf").also { invoicePdf(it, SAMPLE, pages = 3, qrPage = 2) }
        assertTrue(QrReader.fromPdf(f) is AtQrResult.Ok)
    }

    @Test
    fun pdf_sem_qr_da_mensagem_util() = runTest {
        val f = File(dir, "sem.pdf").also { invoicePdf(it, SAMPLE, pages = 1, qrPage = -1) }
        val r = QrReader.fromPdf(f)
        assertTrue(r is AtQrResult.Invalid && r.message.contains("introduzir"))
    }

    @Test
    fun qr_que_nao_e_da_at() = runTest {
        val f = File(dir, "url.pdf").also { invoicePdf(it, "https://exemplo.pt/pagamento") }
        val r = QrReader.fromPdf(f)
        assertTrue(r is AtQrResult.Invalid && r.message.contains("não é de uma fatura"))
    }
}
