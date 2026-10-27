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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.ChargeKind
import pt.obraemdia.app.data.db.PaymentMethod
import pt.obraemdia.app.data.db.TimeEntryKind
import pt.obraemdia.app.data.db.WorkerEntity
import pt.obraemdia.app.data.repo.Agreement
import pt.obraemdia.app.data.repo.AgreementChange
import pt.obraemdia.app.data.repo.ClosureConfirmations
import pt.obraemdia.app.data.repo.ClosureOutcome
import pt.obraemdia.app.data.repo.Corrections
import pt.obraemdia.app.data.repo.JobClosedException
import pt.obraemdia.app.data.repo.JobDetails
import pt.obraemdia.app.data.repo.NewCharge
import pt.obraemdia.app.data.repo.NewJob
import pt.obraemdia.app.data.repo.NewPayment
import pt.obraemdia.app.data.repo.NewTimeEntries
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.job.StatusChange
import pt.obraemdia.domain.money.Money
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AgreementTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ObraRepository
    private lateinit var agreement: Agreement
    private val day = LocalDate.of(2026, 10, 27)

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

    private suspend fun job(materials: MaterialsPolicy = MaterialsPolicy.INCLUDED, mode: BillingMode = BillingMode.FIXED_PRICE) =
        repo.createJob(NewJob("Obra", "Ana", "Rua A", mode, materials, Money.euros(3000).takeIf { mode == BillingMode.FIXED_PRICE }, 2300, Priority.NORMAL, null, null))

    private fun charge(jobId: String, kind: ChargeKind, euros: Long, included: Boolean = false) =
        NewCharge(newId(), jobId, kind, "Descrição", Money.euros(euros), day, "SMS", included)

    private suspend fun sale(jobId: String) = repo.snapshot.first().summary(jobId)!!.saleNet

    @Test
    fun extras_aprovados_somam_ao_preco_fixo_e_os_ja_incluidos_nao() = runTest {
        val a = job()
        agreement.addCharge(charge(a, ChargeKind.EXTRA, 200))
        agreement.addCharge(charge(a, ChargeKind.EXTRA, 50, included = true))
        assertEquals(Money.euros(3200), sale(a))
        // Total devido com IVA a 23 %.
        assertEquals(Money.euros(3936), repo.snapshot.first().summary(a)!!.dueGross)
    }

    @Test
    fun materiais_so_sao_cobrados_quando_a_politica_o_diz() = runTest {
        val included = job(MaterialsPolicy.INCLUDED)
        val separate = job(MaterialsPolicy.BILLED_SEPARATELY)
        agreement.addCharge(charge(included, ChargeKind.MATERIAL, 100))
        agreement.addCharge(charge(separate, ChargeKind.MATERIAL, 100))
        assertEquals(Money.euros(3000), sale(included))
        assertEquals(Money.euros(3100), sale(separate))
    }

    @Test
    fun a_hora_soma_horas_e_extras() = runTest {
        val a = job(mode = BillingMode.HOURLY)
        repo.recordTime(NewTimeEntries(newId(), a, listOf("w1"), day, 480, TimeEntryKind.WORK, true, null))
        agreement.addCharge(charge(a, ChargeKind.EXTRA, 30))
        assertEquals(Money.euros(230), sale(a)) // 8 h × 25 € + 30 €
    }

    @Test
    fun alterar_preco_guarda_o_acordo_anterior() = runTest {
        val a = job()
        agreement.changeAgreement(a, AgreementChange(BillingMode.FIXED_PRICE, Money.euros(3500), 2300, day, "Aumento de área"))
        agreement.changeAgreement(a, AgreementChange(BillingMode.FIXED_PRICE, Money.euros(3500), 600, day, "Taxa reduzida"))

        val revisions = repo.snapshot.first().priceRevisions.sortedBy { it.revision }
        assertEquals(listOf(1, 2), revisions.map { it.revision })
        assertEquals(300000L, revisions[0].oldFixedPriceNetCents)
        assertEquals(350000L, revisions[0].newFixedPriceNetCents)
        assertEquals(2300, revisions[1].oldVatRateBp)
        assertEquals(Money.euros(3500), sale(a))
        try {
            agreement.changeAgreement(a, AgreementChange(BillingMode.FIXED_PRICE, Money.euros(3500), 600, day, "Igual"))
            fail("Sem alterações devia falhar")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun anular_extra_tira_do_valor_e_mantem_no_historico() = runTest {
        val a = job()
        agreement.addCharge(charge(a, ChargeKind.EXTRA, 200))
        val c = repo.snapshot.first().charges.single()
        agreement.cancelCharge(c.id, "Cliente desistiu")
        val snap = repo.snapshot.first()
        assertEquals(Money.euros(3000), snap.summary(a)!!.saleNet)
        assertEquals("Cliente desistiu", snap.charges.single().deleteReason)
    }

    @Test
    fun obra_encerrada_bloqueia_acordo_e_extras_mas_permite_corrigir_o_titulo() = runTest {
        val a = repo.createJob(NewJob("Obra", null, null, BillingMode.FIXED_PRICE, MaterialsPolicy.INCLUDED, Money.euros(100), 0, Priority.NORMAL, null, null))
        repo.changeStatus(a, StatusChange(JobStatus.COMPLETED))
        repo.recordPayment(NewPayment(newId(), a, Money.euros(100), day, PaymentMethod.CASH, null))
        val closed = Corrections(db, repo, System::currentTimeMillis).close(a, ClosureConfirmations(true, true, true, null, null))
        assertTrue(closed is ClosureOutcome.Closed)

        for (attempt in listOf<suspend () -> Unit>(
            { agreement.addCharge(charge(a, ChargeKind.EXTRA, 10)) },
            { agreement.changeAgreement(a, AgreementChange(BillingMode.FIXED_PRICE, Money.euros(200), 0, day, "x")) },
        )) {
            try {
                attempt()
                fail("Devia estar bloqueado")
            } catch (_: JobClosedException) {
            }
        }
        agreement.updateDetails(a, JobDetails("Novo título", null, null, null, null, null, Priority.NORMAL, null, MaterialsPolicy.INCLUDED))
        assertEquals("Novo título", db.jobDao().getById(a)!!.title)
    }

    @Test
    fun editar_cliente_e_morada() = runTest {
        val a = job()
        val original = repo.snapshot.first().summary(a)!!.row
        agreement.updateDetails(a, JobDetails("Obra", null, original.job.clientId, null, "912345678", "Rua B, 1", Priority.HIGH, null, MaterialsPolicy.INCLUDED))
        var row = repo.snapshot.first().summary(a)!!.row
        assertEquals("912345678", row.clientPhone)
        assertEquals("Rua B, 1", row.siteAddress)
        assertEquals(original.job.siteId, row.job.siteId) // o mesmo local, com a morada corrigida

        agreement.updateDetails(a, JobDetails("Obra", null, null, "Bruno", "961111111", "Rua B, 1", Priority.HIGH, null, MaterialsPolicy.INCLUDED))
        row = repo.snapshot.first().summary(a)!!.row
        assertEquals("Bruno", row.clientName)
        assertEquals("961111111", row.clientPhone)
    }
}
