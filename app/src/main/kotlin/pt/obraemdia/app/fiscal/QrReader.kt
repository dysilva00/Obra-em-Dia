package pt.obraemdia.app.fiscal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import pt.obraemdia.domain.fiscalqr.AtQrParser
import pt.obraemdia.domain.fiscalqr.AtQrResult
import java.io.File

/**
 * Leitura de códigos QR no próprio telemóvel (ML Kit com modelo incluído; sem internet).
 * Nos PDFs, o QR da AT pode estar na primeira ou na última página: renderizam-se essas páginas
 * em alta resolução e procura-se o código.
 */
object QrReader {

    private val scanner by lazy {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }

    /** Textos de todos os QR encontrados na imagem. */
    suspend fun decode(bitmap: Bitmap): List<String> =
        scanner.process(InputImage.fromBitmap(bitmap, 0)).await().mapNotNull { it.rawValue }

    /** Primeiro QR da AT válido entre os textos lidos; senão o primeiro erro útil. */
    fun pickAtQr(texts: List<String>): AtQrResult {
        val results = texts.map(AtQrParser::parse)
        return results.firstOrNull { it is AtQrResult.Ok }
            ?: results.firstOrNull()
            ?: AtQrResult.Invalid("Não foi encontrado nenhum código QR no documento.")
    }

    /** Procura o QR da AT num PDF (primeira e última páginas). */
    suspend fun fromPdf(context: Context, uri: Uri): AtQrResult = withContext(Dispatchers.IO) {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext AtQrResult.Invalid("Não foi possível abrir o PDF.")
        fromPdf(fd)
    }

    suspend fun fromPdf(file: File): AtQrResult =
        withContext(Dispatchers.IO) { fromPdf(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }

    private suspend fun fromPdf(fd: ParcelFileDescriptor): AtQrResult {
        val texts = mutableListOf<String>()
        try {
            PdfRenderer(fd).use { pdf ->
                if (pdf.pageCount == 0) return AtQrResult.Invalid("O PDF não tem páginas.")
                val pages = listOf(0, pdf.pageCount - 1).distinct()
                for (index in pages) {
                    val bitmap = pdf.openPage(index).use { page ->
                        // ~200 ppp: o QR (mínimo 30 mm) fica com módulos de vários píxeis.
                        val scale = 200f / 72f
                        Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                    try {
                        texts += decode(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                    if (texts.any { AtQrParser.parse(it) is AtQrResult.Ok }) break
                }
            }
        } catch (e: SecurityException) {
            return AtQrResult.Invalid("O PDF está protegido por palavra-passe.")
        } catch (e: java.io.IOException) {
            return AtQrResult.Invalid("O ficheiro não é um PDF válido.")
        } finally {
            runCatching { fd.close() }
        }
        return pickAtQr(texts).let {
            if (it is AtQrResult.Invalid && texts.isEmpty()) {
                AtQrResult.Invalid("Não foi encontrado o código QR da AT neste PDF. Pode introduzir os valores à mão.")
            } else {
                it
            }
        }
    }
}
