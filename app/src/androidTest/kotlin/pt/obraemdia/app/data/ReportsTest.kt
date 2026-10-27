package pt.obraemdia.app.data

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.demo.DemoSeeder
import pt.obraemdia.app.export.Block
import pt.obraemdia.app.export.PdfWriter
import pt.obraemdia.app.export.Report
import pt.obraemdia.app.export.Reports
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class ReportsTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ObraRepository
    private val today = LocalDate.of(2026, 10, 27)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val outDir = File(context.filesDir, "test-exports")

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = ObraRepository(db)
        DemoSeeder(db, repo).seedIfFirstRun(today)
    }

    @After
    fun tearDown() = db.close()

    private fun Report.text() = blocks.joinToString("\n") {
        when (it) {
            is Block.Heading -> it.text
            is Block.Line -> "${it.label} ${it.value}"
            is Block.Row -> it.cells.joinToString(" ")
            is Block.Text -> it.text
            Block.Gap -> ""
        }
    }

    /** Escreve o PDF e confirma que o Android o consegue abrir e que tem páginas. */
    private fun writeAndOpen(report: Report): Int {
        val file = File(outDir, report.fileName)
        PdfWriter.write(report, file)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { return it.pageCount }
        }
    }

    @Test
    fun gera_os_tres_pdfs_validos() = runTest {
        val snap = repo.snapshot.first()
        val bath = snap.jobs.first { it.job.title.startsWith("Casa de banho") }.job.id
        assertTrue(writeAndOpen(Reports.jobBalance(snap, bath, today)) >= 1)
        assertTrue(writeAndOpen(Reports.clientSummary(snap, bath, today)) >= 1)
        assertTrue(writeAndOpen(Reports.monthly(snap, YearMonth.from(today), today)) >= 1)
    }

    @Test
    fun resumo_para_o_cliente_nao_expoe_dados_internos_nem_outras_obras() = runTest {
        val snap = repo.snapshot.first()
        val bath = snap.jobs.first { it.job.title.startsWith("Casa de banho") }.job.id
        val text = Reports.clientSummary(snap, bath, today).text()
        listOf("Custo interno", "Resultado", "Margem", "Materiais e despesas", "Loja de materiais", "Pintura de muro", "Telhado", "Pessoa 1")
            .forEach { assertFalse("O resumo não pode conter \"$it\"", text.contains(it, ignoreCase = true)) }
        assertTrue(text.contains("Total com IVA"))
    }

    @Test
    fun balanco_interno_inclui_resultado_e_custos() = runTest {
        val snap = repo.snapshot.first()
        val bath = snap.jobs.first { it.job.title.startsWith("Casa de banho") }.job.id
        val text = Reports.jobBalance(snap, bath, today).text()
        assertTrue(text.contains("Resultado"))
        assertTrue(text.contains("Custo interno"))
        assertTrue(text.contains("Loja de materiais"))
    }

    @Test
    fun relatorio_longo_muda_de_pagina() = runTest {
        val long = Report("longo.pdf", "Teste", "Paginação", List(200) { Block.Row(listOf("Linha $it com texto suficientemente comprido para testar", "1,00 €")) })
        assertTrue(writeAndOpen(long) > 1)
    }
}
