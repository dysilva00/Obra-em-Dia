package pt.obraemdia.domain.billing

import org.junit.jupiter.api.Test
import pt.obraemdia.domain.money.HourlyRate
import pt.obraemdia.domain.money.Money
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BillingTest {

    private fun entry(worker: String, hours: Int, sale: Long, cost: Long, billable: Boolean = true) =
        TimeEntryFacts(worker, hours * 60, billable, HourlyRate.euros(sale), HourlyRate.euros(cost))

    @Test
    fun `preco fixo nao cresce com o registo de horas`() {
        val input = SaleInputs(
            mode = BillingMode.FIXED_PRICE,
            materialsPolicy = MaterialsPolicy.INCLUDED,
            approvedFixedPrice = Money.euros(3000),
            timeEntries = listOf(entry("p1", 20, 25, 15), entry("p2", 20, 25, 15)),
        )
        assertEquals(Money.euros(3000), SaleCalculator.saleValue(input))
    }

    @Test
    fun `a hora soma apenas horas faturaveis as taxas historicas`() {
        val input = SaleInputs(
            mode = BillingMode.HOURLY,
            materialsPolicy = MaterialsPolicy.BILLED_SEPARATELY,
            materialsBilledToClient = listOf(Money.euros(100)),
            extrasNotIncluded = listOf(Money.euros(50)),
            timeEntries = listOf(
                entry("p1", 8, 25, 15),
                entry("p2", 8, 20, 12), // taxa diferente por pessoa
                entry("p1", 2, 25, 15, billable = false), // deslocação não faturável
            ),
        )
        // 8×25 + 8×20 + 100 + 50
        assertEquals(Money.euros(510), SaleCalculator.saleValue(input))
    }

    @Test
    fun `materiais incluidos nao sao cobrados novamente`() {
        val input = SaleInputs(
            mode = BillingMode.FIXED_PRICE,
            materialsPolicy = MaterialsPolicy.INCLUDED,
            approvedFixedPrice = Money.euros(1000),
            materialsBilledToClient = listOf(Money.euros(200)),
        )
        assertEquals(Money.euros(1000), SaleCalculator.saleValue(input))
    }

    @Test
    fun `exemplo da especificacao`() {
        val hours = List(2) { entry("p$it", 20, 0, 15) } // 40 horas-pessoa a 15 €
        val result = JobResult.of(Money.euros(3000), Money.euros(700), hours)

        assertEquals(Money.euros(600), result.internalLaborCost)
        assertEquals(Money.euros(1700), result.result)
        assertEquals(BigDecimal("56.7"), result.marginPercent)

        val balance = ClientBalance(Money.euros(3000), receipts = listOf(Money.euros(1000)))
        assertEquals(Money.euros(2000), balance.balance)
        assertEquals(PaymentSituation.PARTIAL, balance.situation)
    }

    @Test
    fun `margem so com venda positiva`() {
        assertNull(JobResult(Money.ZERO, Money.euros(10), Money.ZERO).marginPercent)
    }

    @Test
    fun `adiantamento reduz o saldo sem aumentar o preco`() {
        val balance = ClientBalance(Money.euros(3000), receipts = listOf(Money.euros(500)))
        assertEquals(Money.euros(2500), balance.balance)
        assertEquals(Money.euros(3000), balance.totalDueGross)
    }

    @Test
    fun `pagamento em excesso fica visivel e negativo`() {
        val balance = ClientBalance(Money.euros(1000), receipts = listOf(Money.euros(1000), Money.euros(50)))
        assertEquals(Money.euros(-50), balance.balance)
        assertEquals(PaymentSituation.TO_REGULARIZE, balance.situation)

        val refunded = balance.copy(refunds = listOf(Money.euros(50)))
        assertEquals(PaymentSituation.SETTLED, refunded.situation)
    }

    @Test
    fun `prestacao vencida coexiste com pagamento parcial`() {
        val installments = listOf(
            Installment("i1", LocalDate.of(2026, 10, 1), Money.euros(500)),
            Installment("i2", LocalDate.of(2026, 10, 15), Money.euros(500)),
            Installment("i3", LocalDate.of(2026, 11, 15), Money.euros(500)),
        )
        val balance = ClientBalance(Money.euros(1500), receipts = listOf(Money.euros(700)))

        val overdue = balance.overdueInstallments(installments, today = LocalDate.of(2026, 10, 27))

        assertEquals(PaymentSituation.PARTIAL, balance.situation)
        assertEquals(listOf("i2" to Money.euros(300)), overdue.map { it.first.id to it.second })
    }
}
