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
import pt.obraemdia.app.data.db.ExpenseCategory
import pt.obraemdia.app.data.db.PaymentMethod
import pt.obraemdia.app.data.db.TimeEntryKind
import pt.obraemdia.app.data.db.WorkerEntity
import pt.obraemdia.app.data.repo.ClosureConfirmations
import pt.obraemdia.app.data.repo.ClosureOutcome
import pt.obraemdia.app.data.repo.Corrections
import pt.obraemdia.app.data.repo.ExpenseLine
import pt.obraemdia.app.data.repo.JobClosedException
import pt.obraemdia.app.data.repo.NewExpense
import pt.obraemdia.app.data.repo.NewJob
import pt.obraemdia.app.data.repo.NewPayment
import pt.obraemdia.app.data.repo.NewTimeEntries
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.data.repo.SaveOutcome
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.DocumentType
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.billing.VatTreatment
import pt.obraemdia.domain.job.ClosureIssue
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.job.StatusChange
import pt.obraemdia.domain.money.Money
import java.time.LocalDate
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class CorrectionsTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ObraRepository
    private lateinit var fix: Corrections
    private val day = LocalDate.of(2026, 10, 27)
    private val allConfirmed = ClosureConfirmations(true, true, true, "w1", "ok")

    @Before
    fun setUp() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = ObraRepository(db)
        fix = Corrections(db, repo, System::currentTimeMillis)
        db.workerDao().upsert(WorkerEntity("w1", "Pessoa 1", 2500, 1500, 0, 0, 0))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun job(price: Long = 1000) =
        repo.createJob(NewJob("Obra", null, null, BillingMode.FIXED_PRICE, MaterialsPolicy.INCLUDED, Money.euros(price), 0, Priority.NORMAL, null, null))

    private suspend fun pay(jobId: String, euros: Long) =
        repo.recordPayment(NewPayment(newId(), jobId, Money.euros(euros), day, PaymentMethod.CASH, null))

    private suspend fun hours(jobId: String, minutes: Int = 480) =
        repo.recordTime(NewTimeEntries(newId(), jobId, listOf("w1"), day, minutes, TimeEntryKind.WORK, true, null))

    private suspend fun expense(jobId: String, euros: Long, paid: Boolean) = repo.recordExpense(
        NewExpense(
            newId(), "Loja", null, DocumentType.INVOICE, null, null, day, null, ExpenseCategory.MATERIAL, Money.euros(euros), null,
            VatTreatment.NOT_RECOVERABLE, listOf(ExpenseLine(jobId, Money.euros(euros))), paid, day, PaymentMethod.CASH, null,
        ),
    )

    @Test
    fun estorno_de_recebimento_repoe_o_saldo_e_so_se_faz_uma_vez() = runTest {
        val a = job()
        pay(a, 400)
        val m = repo.snapshot.first().movements.single()

        fix.reverseMovement(m.id, "Valor errado", day)

        val snap = repo.snapshot.first()
        assertEquals(Money.euros(1000), snap.summary(a)!!.balance!!.balance)
        assertEquals(Money.ZERO, snap.monthReceived(YearMonth.from(day)))
        assertEquals(2, snap.movements.size) // o original continua no histórico
        try {
            fix.reverseMovement(m.id, "De novo", day)
            fail("Não pode estornar duas vezes")
        } catch (_: IllegalArgumentException) {
        }
        val reversal = snap.movements.first { it.isReversal }
        try {
            fix.reverseMovement(reversal.id, "Estorno do estorno", day)
            fail("Não pode estornar um estorno")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun corrigir_horas_mantem_taxas_historicas_e_anular_tira_das_contas() = runTest {
        val a = job()
        hours(a)
        val e = repo.snapshot.first().timeEntries.single()
        db.workerDao().upsert(WorkerEntity("w1", "Pessoa 1", 9900, 9900, 0, 0, 1))

        fix.updateTimeEntry(e.id, day.minusDays(1), 300, TimeEntryKind.WORK, false, "corrigido")
        val updated = repo.snapshot.first().timeEntries.single()
        assertEquals(300, updated.minutes)
        assertFalse(updated.billable)
        assertEquals(2500L, updated.saleRateCents)

        fix.deleteTimeEntry(e.id, "Registo duplicado")
        assertTrue(repo.snapshot.first().timeEntries.isEmpty())
        assertEquals(0, repo.snapshot.first().summary(a)!!.minutes)
    }

    @Test
    fun anular_documento_retira_custo_e_estorna_pagamento() = runTest {
        val a = job()
        expense(a, 200, paid = true)
        val doc = repo.snapshot.first().purchases.single()

        assertEquals(1, fix.voidPurchase(doc.id, "Lançado na obra errada", day))

        val snap = repo.snapshot.first()
        assertTrue(snap.purchases.isEmpty())
        assertEquals(Money.ZERO, snap.summary(a)!!.costs)
        assertEquals(Money.ZERO, snap.monthPaid(YearMonth.from(day)))
    }

    @Test
    fun obra_concluida_por_pagar_nao_pode_ser_encerrada() = runTest {
        val a = job()
        repo.changeStatus(a, StatusChange(JobStatus.COMPLETED))
        pay(a, 600)
        val outcome = fix.close(a, allConfirmed)
        assertTrue(outcome is ClosureOutcome.Blocked)
        assertNull(db.closureDao().latest(a))
    }

    @Test
    fun encerrar_bloqueia_alteracoes_e_reabrir_cria_nova_revisao() = runTest {
        val a = job()
        hours(a)
        repo.changeStatus(a, StatusChange(JobStatus.COMPLETED))
        pay(a, 1000)
        expense(a, 150, paid = false) // fornecedor por pagar: aviso, não bloqueio

        val first = fix.close(a, allConfirmed)
        assertEquals(ClosureOutcome.Closed(1), first)

        // Alterações financeiras bloqueadas.
        for (attempt in listOf<suspend () -> Unit>({ hours(a) }, { pay(a, 1) }, { expense(a, 10, paid = true) })) {
            try {
                attempt()
                fail("Devia estar bloqueado")
            } catch (_: JobClosedException) {
            }
        }
        // A dívida ao fornecedor mantém-se e pode ser paga depois do fecho.
        val doc = repo.snapshot.first().purchases.single()
        assertEquals(Money.euros(150), repo.snapshot.first().toPaySuppliers)
        assertEquals(SaveOutcome.Saved, repo.paySupplier(newId(), doc.id, Money.euros(150), day, PaymentMethod.CASH))

        fix.reopen(a, "Cliente pediu um extra")
        assertEquals(SaveOutcome.Saved, hours(a, 60))

        val second = fix.close(a, allConfirmed)
        assertEquals(ClosureOutcome.Closed(2), second)
        val all = repo.snapshot.first().closures
        assertEquals(listOf(1, 2), all.map { it.revision })
        assertEquals("Cliente pediu um extra", all.first { it.revision == 1 }.reopenReason)
        assertTrue(fix.decodeBalance(all.first { it.revision == 2 })!!.minutes == 540)
    }

    @Test
    fun aviso_de_fornecedor_por_pagar_na_revisao() = runTest {
        val a = job()
        repo.changeStatus(a, StatusChange(JobStatus.COMPLETED))
        pay(a, 1000)
        expense(a, 150, paid = false)
        val review = repo.transaction { fix.review(a, allConfirmed) }
        assertTrue(review.issues.none { it is ClosureIssue.Blocker })
        assertTrue(review.issues.any { it is ClosureIssue.Warning && it.message.contains("fornecedores") })
    }
}
