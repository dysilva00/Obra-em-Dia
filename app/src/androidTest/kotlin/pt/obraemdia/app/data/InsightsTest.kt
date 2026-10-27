package pt.obraemdia.app.data

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
import pt.obraemdia.app.data.db.ExpenseCategory
import pt.obraemdia.app.data.db.PaymentMethod
import pt.obraemdia.app.data.db.WorkerEntity
import pt.obraemdia.app.data.repo.AgendaFacts
import pt.obraemdia.app.data.repo.Agreement
import pt.obraemdia.app.data.repo.AlertKind
import pt.obraemdia.app.data.repo.ExpenseLine
import pt.obraemdia.app.data.repo.Insights
import pt.obraemdia.app.data.repo.NewExpense
import pt.obraemdia.app.data.repo.NewJob
import pt.obraemdia.app.data.repo.NewPayment
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.DocumentType
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.billing.VatTreatment
import pt.obraemdia.domain.cash.Forecast
import pt.obraemdia.domain.job.HoldReason
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.job.StatusChange
import pt.obraemdia.domain.money.Money
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class InsightsTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ObraRepository
    private lateinit var agreement: Agreement
    private val today = LocalDate.of(2026, 10, 27)
    private val noAgenda = AgendaFacts(emptyList())

    @Before
    fun setUp() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = ObraRepository(db)
        agreement = Agreement(db, repo, System::currentTimeMillis)
        db.workerDao().upsert(WorkerEntity("w1", "Pessoa 1", 2500, 1500, 0, 0, 0))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun job(title: String, price: Long?, vat: Int? = 0) =
        repo.createJob(NewJob(title, null, null, BillingMode.FIXED_PRICE, MaterialsPolicy.INCLUDED, price?.let { Money.euros(it) }, vat, Priority.NORMAL, null, null))

    private suspend fun confirm(jobId: String) = repo.changeStatus(jobId, StatusChange(JobStatus.IN_PROGRESS))

    @Test
    fun adiantamento_nao_reaparece_e_compra_a_credito_entra_como_saida() = runTest {
        val a = job("A", 3000)
        confirm(a)
        agreement.addInstallment(a, today.minusDays(5), Money.euros(1000), "adiantamento")
        agreement.addInstallment(a, today.plusDays(20), Money.euros(2000), "final")
        repo.recordPayment(NewPayment(newId(), a, Money.euros(1000), today.minusDays(5), PaymentMethod.CASH, null))
        repo.recordExpense(
            NewExpense(
                newId(), "Loja", null, DocumentType.INVOICE, null, null, today, today.plusDays(10), ExpenseCategory.MATERIAL,
                Money.euros(400), null, VatTreatment.UNKNOWN, listOf(ExpenseLine(a, Money.euros(400))), false, null, null, null,
            ),
        )

        val snap = repo.snapshot.first()
        val inputs = Insights.forecastInputs(snap, noAgenda, today)
        val r = Forecast.project(today, 30, snap.accumulatedBalance(today), inputs.items, false, inputs.unknowns)
        assertEquals(Money.euros(2000), r.inflow)
        assertEquals(Money.euros(400), r.outflow)
        assertEquals(Money.euros(1000 + 2000 - 400), r.endBalance)
    }

    @Test
    fun propostas_separadas_e_obra_sem_preco_torna_previsao_incompleta() = runTest {
        val proposal = job("Proposta", 500) // fica "Por confirmar"
        val noPrice = job("Sem preço", null)
        confirm(noPrice)
        agreement.addInstallment(proposal, today.plusDays(10), Money.euros(500), null)

        val snap = repo.snapshot.first()
        val inputs = Insights.forecastInputs(snap, noAgenda, today)
        val confirmed = Forecast.project(today, 30, Money.ZERO, inputs.items, false, inputs.unknowns)
        val withProposals = Forecast.project(today, 30, Money.ZERO, inputs.items, true, inputs.unknowns)
        assertEquals(Money.ZERO, confirmed.inflow)
        assertEquals(Money.euros(500), withProposals.inflow)
        assertTrue(confirmed.isIncomplete)
        assertTrue(confirmed.unknowns.any { it.contains("Sem preço") })
    }

    @Test
    fun alertas_de_vencidos_e_obra_em_espera_a_rever() = runTest {
        val a = job("A", 1000)
        confirm(a)
        agreement.addInstallment(a, today.minusDays(3), Money.euros(600), null)
        repo.recordPayment(NewPayment(newId(), a, Money.euros(100), today.minusDays(3), PaymentMethod.CASH, null))
        repo.recordExpense(
            NewExpense(
                newId(), "Armazém", null, DocumentType.INVOICE, null, null, today.minusDays(40), today.minusDays(10), ExpenseCategory.MATERIAL,
                Money.euros(200), null, VatTreatment.UNKNOWN, listOf(ExpenseLine(a, Money.euros(200))), false, null, null, null,
            ),
        )
        val b = job("B", 500)
        confirm(b)
        repo.changeStatus(b, StatusChange(JobStatus.ON_HOLD, HoldReason.WEATHER, null, today))

        val alerts = Insights.alerts(repo.snapshot.first(), noAgenda, today)
        val kinds = alerts.map { it.kind }
        assertTrue(kinds.contains(AlertKind.OVERDUE_RECEIVABLE))
        assertTrue(alerts.first { it.kind == AlertKind.OVERDUE_RECEIVABLE }.text.contains("500,00"))
        assertTrue(kinds.contains(AlertKind.OVERDUE_PAYABLE))
        assertTrue(kinds.contains(AlertKind.HOLD_REVIEW))
    }
}
