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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.PaymentMethod
import pt.obraemdia.app.data.db.TimeEntryEntity
import pt.obraemdia.app.data.db.TimeEntryKind
import pt.obraemdia.app.data.db.WorkerEntity
import pt.obraemdia.app.data.repo.NewJob
import pt.obraemdia.app.data.repo.NewPayment
import pt.obraemdia.app.data.repo.NewTimeEntries
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.data.repo.SaveOutcome
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.app.demo.DemoSeeder
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.billing.PaymentSituation
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.money.Money
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ObraRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ObraRepository
    private val day = LocalDate.of(2026, 10, 27)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = ObraRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun workers() {
        db.workerDao().upsert(WorkerEntity("w1", "Pessoa 1", 2500, 1500, 0, 0, 0))
        db.workerDao().upsert(WorkerEntity("w2", "Pessoa 2", 2000, 1200, 1, 0, 0))
    }

    private suspend fun job(mode: BillingMode = BillingMode.FIXED_PRICE, price: Money? = Money.euros(3000), vat: Int? = 0) =
        repo.createJob(NewJob("Obra teste", null, null, mode, MaterialsPolicy.INCLUDED, price, vat, Priority.NORMAL, null, null))

    @Test
    fun pagamento_repetido_com_a_mesma_operacao_nao_duplica() = runTest {
        workers()
        val jobId = job()
        val payment = NewPayment(newId(), jobId, Money.euros(1000), day, PaymentMethod.TRANSFER, null)

        assertEquals(SaveOutcome.Saved, repo.recordPayment(payment))
        assertEquals(SaveOutcome.Duplicate, repo.recordPayment(payment))

        val snap = repo.snapshot.first()
        assertEquals(1, snap.movements.size)
        assertEquals(Money.euros(2000), snap.summary(jobId)!!.balance!!.balance)
        assertEquals(PaymentSituation.PARTIAL, snap.summary(jobId)!!.balance!!.situation)
    }

    @Test
    fun os_dois_cria_um_registo_por_pessoa_com_as_taxas_de_cada_um() = runTest {
        workers()
        val jobId = job(BillingMode.HOURLY, price = null)
        val op = newId()
        val input = NewTimeEntries(op, jobId, listOf("w1", "w2"), day, 480, TimeEntryKind.WORK, true, null)

        assertEquals(SaveOutcome.Saved, repo.recordTime(input))
        assertEquals(SaveOutcome.Duplicate, repo.recordTime(input))

        val entries = repo.snapshot.first().timeEntries.sortedBy { it.workerId }
        assertEquals(listOf("w1", "w2"), entries.map { it.workerId })
        assertEquals(listOf(2500L, 2000L), entries.map { it.saleRateCents })
        // 8 h × 25 € + 8 h × 20 €
        assertEquals(Money.euros(360), repo.snapshot.first().summary(jobId)!!.saleNet)
    }

    @Test
    fun alterar_a_taxa_habitual_nao_muda_registos_anteriores() = runTest {
        workers()
        val jobId = job(BillingMode.HOURLY, price = null)
        repo.recordTime(NewTimeEntries(newId(), jobId, listOf("w1"), day, 60, TimeEntryKind.WORK, true, null))

        db.workerDao().upsert(WorkerEntity("w1", "Pessoa 1", 4000, 2000, 0, 0, 1))
        repo.recordTime(NewTimeEntries(newId(), jobId, listOf("w1"), day.plusDays(1), 60, TimeEntryKind.WORK, true, null))

        val rates = repo.snapshot.first().timeEntries.sortedBy { it.date }.map { it.saleRateCents }
        assertEquals(listOf(2500L, 4000L), rates)
    }

    @Test
    fun preco_fixo_nao_cresce_com_horas_gravadas() = runTest {
        workers()
        val jobId = job(BillingMode.FIXED_PRICE, Money.euros(3000))
        repo.recordTime(NewTimeEntries(newId(), jobId, listOf("w1", "w2"), day, 480, TimeEntryKind.WORK, true, null))

        val s = repo.snapshot.first().summary(jobId)!!
        assertEquals(Money.euros(3000), s.saleNet)
        // Custo interno: 8 h × 15 € + 8 h × 12 €
        assertEquals(Money.euros(216), s.result!!.internalLaborCost)
    }

    @Test
    fun sem_preco_ou_iva_o_saldo_fica_desconhecido_e_nao_zero() = runTest {
        workers()
        val noPrice = job(price = null)
        val noVat = job(vat = null)
        val snap = repo.snapshot.first()
        assertNull(snap.summary(noPrice)!!.saleNet)
        assertNull(snap.summary(noVat)!!.balance)
    }

    @Test
    fun falha_a_meio_nao_deixa_registos_parciais() = runTest {
        workers()
        val jobId = job()
        try {
            // A segunda pessoa não existe: a transação tem de ser revertida por inteiro.
            repo.recordTime(NewTimeEntries(newId(), jobId, listOf("w1", "inexistente"), day, 480, TimeEntryKind.WORK, true, null))
            fail("Devia ter falhado")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(repo.snapshot.first().timeEntries.isEmpty())
    }

    @Test
    fun chaves_estrangeiras_estao_ativas() = runTest {
        workers()
        try {
            db.timeEntryDao().insertAll(
                listOf(
                    TimeEntryEntity(
                        newId(), newId(), "obra-inexistente", "w1", day, minutes = 60, kind = TimeEntryKind.WORK,
                        billable = true, saleRateCents = 0, costRateCents = 0, createdAt = 0, updatedAt = 0,
                    ),
                ),
            )
            fail("A chave estrangeira devia ter rejeitado o registo")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true))
        }
    }

    @Test
    fun remover_demonstracao_mantem_pessoas_e_registos_reais() = runTest {
        val seeder = DemoSeeder(db, repo)
        seeder.seedIfFirstRun(day)
        val realJob = job()
        repo.recordPayment(NewPayment(newId(), realJob, Money.euros(10), day, PaymentMethod.CASH, null))
        assertTrue(repo.snapshot.first().hasDemoData)

        seeder.deleteDemoData()

        val snap = repo.snapshot.first()
        assertFalse(snap.hasDemoData)
        assertEquals(2, snap.workers.size)
        assertEquals(listOf(realJob), snap.jobs.map { it.job.id })
        assertEquals(1, snap.movements.size)
    }

    @Test
    fun remover_demonstracao_e_atomico_quando_ha_registos_reais_ligados() = runTest {
        val seeder = DemoSeeder(db, repo)
        seeder.seedIfFirstRun(day)
        val demoJob = repo.snapshot.first().jobs.first { it.job.isDemo }.job.id
        // Um pagamento real numa obra de demonstração impede a remoção (RESTRICT)…
        repo.recordPayment(NewPayment(newId(), demoJob, Money.euros(10), day, PaymentMethod.CASH, null))

        try {
            seeder.deleteDemoData()
            fail("Devia ter falhado")
        } catch (_: Exception) {
        }
        // …e nada é apagado pela metade.
        val snap = repo.snapshot.first()
        assertTrue(snap.hasDemoData)
        assertEquals(5, snap.jobs.size)
        assertEquals(6, snap.movements.size)
    }
}
