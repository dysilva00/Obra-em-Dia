package pt.obraemdia.app.data

import android.net.Uri
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.InvoiceSource
import pt.obraemdia.app.data.files.AttachmentStore
import pt.obraemdia.app.data.repo.InvoiceOutcome
import pt.obraemdia.app.data.repo.InvoiceTotals
import pt.obraemdia.app.data.repo.IssuedInvoiceInput
import pt.obraemdia.app.data.repo.IssuedInvoices
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.app.fiscal.QrReader
import pt.obraemdia.app.fiscal.QrReaderTest
import pt.obraemdia.domain.fiscalqr.AtQrParser
import pt.obraemdia.domain.fiscalqr.AtQrResult
import pt.obraemdia.domain.money.Money
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class IssuedInvoicesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var invoices: IssuedInvoices
    private lateinit var store: AttachmentStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        store = AttachmentStore(context)
        invoices = IssuedInvoices(db, ObraRepository(db), store, System::currentTimeMillis)
        File(context.filesDir, AttachmentStore.DIR).deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, AttachmentStore.DIR).deleteRecursively()
    }

    private fun manual(number: String, net: Long, vat: Long = 0, type: String = "FR", nif: String = "123456789", date: LocalDate = LocalDate.of(2026, 3, 1)) =
        IssuedInvoiceInput(
            newId(), nif, null, null, type, number, null, date, Money.euros(net), Money.euros(vat), Money.euros(net + vat),
            Money.ZERO, null, InvoiceSource.MANUAL, null, null, null,
        )

    @Test
    fun pdf_lido_e_gravado_com_anexo_e_totais_por_nif_e_ano() = runTest {
        val pdf = File(context.cacheDir, "fr.pdf").also { QrReaderTest.invoicePdf(it, QrReaderTest.SAMPLE) }
        val read = QrReader.fromPdf(pdf) as AtQrResult.Ok
        val input = IssuedInvoiceInput.fromQr(newId(), read.document, InvoiceSource.PDF, Uri.fromFile(pdf))

        assertEquals(InvoiceOutcome.Saved, invoices.record(input))
        invoices.record(manual("FR A/2", 500, nif = "501964843"))
        invoices.record(manual("FR A/3", 200, date = LocalDate.of(2025, 12, 31)))

        val all = invoices.all.first()
        val saved = all.first { it.docNumber == "FR ATSIRE01FR/12" }
        assertEquals(184500L, saved.grossCents)
        assertEquals(150000L, saved.netCents)
        assertEquals(37500L, saved.withholdingCents)
        assertTrue(store.file(db.mediaDao().attachment(saved.attachmentId!!)!!.relativePath).exists())

        val totals = InvoiceTotals.byYearAndIssuer(all, 2026)
        assertEquals(listOf("123456789" to Money(150000), "501964843" to Money(50000)), totals.map { it.issuerNif to it.net })
        assertEquals(Money(20000), InvoiceTotals.byYearAndIssuer(all, 2025).single().net)
    }

    @Test
    fun ler_o_mesmo_documento_duas_vezes_nao_duplica() = runTest {
        val d = (AtQrParser.parse(QrReaderTest.SAMPLE) as AtQrResult.Ok).document
        assertEquals(InvoiceOutcome.Saved, invoices.record(IssuedInvoiceInput.fromQr(newId(), d, InvoiceSource.QR_CAMERA, null)))
        assertTrue(invoices.record(IssuedInvoiceInput.fromQr(newId(), d, InvoiceSource.PDF, null)) is InvoiceOutcome.Duplicate)
        assertEquals(1, invoices.all.first().size)
    }

    @Test
    fun nota_de_credito_subtrai_e_anulado_sai_do_total() = runTest {
        invoices.record(manual("FT A/1", 1000, vat = 230, type = "FT"))
        invoices.record(manual("NC A/1", 200, vat = 46, type = "NC"))
        invoices.record(manual("FT A/2", 300, type = "FT"))
        val ft2 = invoices.all.first().first { it.docNumber == "FT A/2" }
        invoices.cancel(ft2.id, "Emitida por engano")

        val t = InvoiceTotals.byYearAndIssuer(invoices.all.first(), 2026).single()
        assertEquals(Money.euros(800), t.net)
        assertEquals(Money.euros(984), t.gross)
    }

    @Test
    fun recusa_proformas_nif_invalido_e_totais_incoerentes() = runTest {
        assertTrue(invoices.record(manual("PF 1", 10, type = "PF")) is InvoiceOutcome.Rejected)
        assertTrue(invoices.record(manual("FR 1", 10, nif = "123456780")) is InvoiceOutcome.Rejected)
        val bad = manual("FR 2", 10).copy(gross = Money.euros(99))
        assertTrue(invoices.record(bad) is InvoiceOutcome.Rejected)
        assertTrue(invoices.all.first().isEmpty())
    }
}
