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
import pt.obraemdia.app.data.db.ExpenseCategory
import pt.obraemdia.app.data.db.PaymentMethod
import pt.obraemdia.app.data.db.WorkerEntity
import pt.obraemdia.app.data.repo.ExpenseLine
import pt.obraemdia.app.data.repo.ExpenseOutcome
import pt.obraemdia.app.data.repo.NewExpense
import pt.obraemdia.app.data.repo.NewJob
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.data.repo.SaveOutcome
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.DocumentType
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.billing.VatTreatment
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.money.Money
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ExpensesTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ObraRepository
    private val day = LocalDate.of(2026, 10, 27)

    @Before
    fun setUp() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = ObraRepository(db)
        db.workerDao().upsert(WorkerEntity("w1", "Pessoa 1", 2500, 1500, 0, 0, 0))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun job(title: String) =
        repo.createJob(NewJob(title, null, null, BillingMode.FIXED_PRICE, MaterialsPolicy.INCLUDED, Money.euros(3000), 0, Priority.NORMAL, null, null))

    private fun expense(
        gross: Money,
        lines: List<ExpenseLine>,
        paid: Boolean = true,
        type: DocumentType = DocumentType.INVOICE,
        nif: String? = null,
        number: String? = null,
        vat: VatTreatment = VatTreatment.RECOVERABLE,
        op: String = newId(),
    ) = NewExpense(
        operationId = op, supplierName = "Fornecedor", supplierNif = nif, docType = type, docNumber = number, atcud = null,
        docDate = day, dueDate = null, category = ExpenseCategory.MATERIAL, gross = gross, vatRateBp = 2300, vatTreatment = vat,
        lines = lines, paidNow = paid, paymentDate = day, paymentMethod = PaymentMethod.CASH, paidByWorkerId = "w1",
    )

    @Test
    fun documento_de_123_repartido_em_80_e_43_soma_123_e_nunca_246() = runTest {
        val a = job("A")
        val b = job("B")
        repo.recordExpense(expense(Money.euros(123), listOf(ExpenseLine(a, Money.euros(80)), ExpenseLine(b, Money.euros(43)))))

        val snap = repo.snapshot.first()
        // IVA recuperável: custo = base. 123 € com 23 % → base 100 €.
        val totalCosts = snap.summary(a)!!.costs + snap.summary(b)!!.costs
        assertEquals(Money.euros(100), totalCosts)
        assertEquals(1, snap.purchases.size)
        // Um único pagamento de 123 €, não dois.
        assertEquals(listOf(Money.euros(-123)), snap.movements.map { Money(it.signedCents) })
    }

    @Test
    fun guardar_duas_vezes_a_mesma_compra_nao_duplica() = runTest {
        val a = job("A")
        val op = newId()
        val first = expense(Money.euros(50), listOf(ExpenseLine(a, Money.euros(50))), nif = "501964843", number = "FT 1/23", op = op)
        assertTrue(repo.recordExpense(first) is ExpenseOutcome.Saved)
        // Duplo toque (mesma operação) e nova leitura do mesmo documento (outra operação).
        assertTrue(repo.recordExpense(first) is ExpenseOutcome.Duplicate)
        assertTrue(repo.recordExpense(first.copy(operationId = newId())) is ExpenseOutcome.Duplicate)

        val snap = repo.snapshot.first()
        assertEquals(1, snap.purchases.size)
        assertEquals(1, snap.movements.size)
    }

    @Test
    fun compra_a_credito_afeta_custos_mas_nao_caixa_ate_ser_paga() = runTest {
        val a = job("A")
        repo.recordExpense(expense(Money.euros(310), listOf(ExpenseLine(a, Money.euros(310))), paid = false, vat = VatTreatment.NOT_RECOVERABLE))

        var snap = repo.snapshot.first()
        assertEquals(Money.euros(310), snap.summary(a)!!.costs)
        assertTrue(snap.movements.isEmpty())
        assertEquals(Money.euros(310), snap.toPaySuppliers)

        val docId = snap.purchases.single().id
        assertEquals(SaveOutcome.Saved, repo.paySupplier(newId(), docId, Money.euros(100), day, PaymentMethod.TRANSFER))
        try {
            repo.paySupplier(newId(), docId, Money.euros(300), day, PaymentMethod.TRANSFER)
            fail("Pagar mais do que o devido devia falhar")
        } catch (_: IllegalArgumentException) {
        }
        snap = repo.snapshot.first()
        assertEquals(Money.euros(210), snap.toPaySuppliers)
        assertEquals(Money.euros(310), snap.summary(a)!!.costs)
    }

    @Test
    fun nota_de_credito_reduz_o_custo_sem_criar_divida_nem_devolucao() = runTest {
        val a = job("A")
        repo.recordExpense(expense(Money.euros(123), listOf(ExpenseLine(a, Money.euros(123)))))
        repo.recordExpense(
            expense(Money(2460), listOf(ExpenseLine(a, Money(2460))), paid = true, type = DocumentType.CREDIT_NOTE),
        )

        val snap = repo.snapshot.first()
        // 100 € − 20 € de base.
        assertEquals(Money.euros(80), snap.summary(a)!!.costs)
        assertEquals(Money.ZERO, snap.toPaySuppliers)
        // A nota de crédito não cria movimento de caixa (não se presume devolução).
        assertEquals(1, snap.movements.size)
    }

    @Test
    fun guia_nao_cria_despesa() = runTest {
        val a = job("A")
        try {
            repo.recordExpense(expense(Money.euros(10), listOf(ExpenseLine(a, Money.euros(10))), type = DocumentType.DELIVERY_NOTE))
            fail("Uma guia não deve criar despesa")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(repo.snapshot.first().purchases.isEmpty())
    }

    @Test
    fun parcelas_acima_do_total_nao_gravam_nada() = runTest {
        val a = job("A")
        try {
            repo.recordExpense(expense(Money.euros(100), listOf(ExpenseLine(a, Money.euros(80)), ExpenseLine(null, Money.euros(30)))))
            fail("Devia falhar")
        } catch (_: IllegalArgumentException) {
        }
        val snap = repo.snapshot.first()
        assertTrue(snap.purchases.isEmpty())
        assertTrue(snap.movements.isEmpty())
    }

    @Test
    fun compra_lida_do_qr_usa_base_e_iva_exatos_e_nao_duplica_com_outro_numero_de_operacao() = runTest {
        val a = job("A")
        val d = (pt.obraemdia.domain.fiscalqr.AtQrParser.parse(pt.obraemdia.app.fiscal.QrReaderTest.SAMPLE) as pt.obraemdia.domain.fiscalqr.AtQrResult.Ok).document
        val exact = pt.obraemdia.domain.money.DocumentAmounts(d.gross - d.totalTaxes, d.totalTaxes, d.gross)
        val input = expense(d.gross, listOf(ExpenseLine(a, d.gross)), nif = d.issuerNif, number = d.docNumber)
            .copy(atcud = d.atcud, rawQr = d.raw, exactAmounts = exact, vatRateBp = 600)
        assertTrue(repo.recordExpense(input) is ExpenseOutcome.Saved)
        val doc = repo.snapshot.first().purchases.single()
        assertEquals(150000L, doc.netCents) // do QR, não dos 6 % indicados
        assertEquals(34500L, doc.vatCents)
        assertTrue(repo.recordExpense(input.copy(operationId = newId(), docNumber = "outro")) is ExpenseOutcome.Duplicate)
    }

    @Test
    fun parte_por_classificar_fica_visivel() = runTest {
        val a = job("A")
        repo.recordExpense(expense(Money.euros(100), listOf(ExpenseLine(a, Money.euros(60)))))
        assertEquals(Money.euros(40), repo.snapshot.first().purchaseStatus.single().unallocated)
    }
}
