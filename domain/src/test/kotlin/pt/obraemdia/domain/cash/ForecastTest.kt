package pt.obraemdia.domain.cash

import org.junit.jupiter.api.Test
import pt.obraemdia.domain.billing.Installment
import pt.obraemdia.domain.money.Money
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForecastTest {

    private val today = LocalDate.of(2026, 10, 27)

    @Test
    fun `caixa prevista soma entradas e subtrai saidas no horizonte`() {
        val items = listOf(
            ForecastItem("r1", "Obra A", Money.euros(1000), today.plusDays(10)),
            ForecastItem("p1", "Fornecedor", Money.euros(-300), today.plusDays(20)),
            ForecastItem("r2", "Obra B", Money.euros(500), today.plusDays(45)),
        )
        val r30 = Forecast.project(today, 30, Money.euros(200), items, includeProposals = false)
        assertEquals(Money.euros(1000), r30.inflow)
        assertEquals(Money.euros(300), r30.outflow)
        assertEquals(Money.euros(900), r30.endBalance)
        val r60 = Forecast.project(today, 60, Money.euros(200), items, includeProposals = false)
        assertEquals(Money.euros(1400), r60.endBalance)
        assertEquals(listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 12)), r60.months.map { it.month })
    }

    @Test
    fun `propostas so entram no cenario com propostas`() {
        val items = listOf(ForecastItem("x", "Proposta", Money.euros(800), today.plusDays(5), Scenario.PROPOSAL))
        assertEquals(Money.ZERO, Forecast.project(today, 30, Money.ZERO, items, includeProposals = false).inflow)
        assertEquals(Money.euros(800), Forecast.project(today, 30, Money.ZERO, items, includeProposals = true).inflow)
    }

    @Test
    fun `vencidos contam hoje e adiamento simulado nao altera dados`() {
        val items = listOf(ForecastItem("r", "Atrasado", Money.euros(100), today.minusDays(5)))
        val r = Forecast.project(today, 30, Money.ZERO, items, false)
        assertTrue(r.included.single().overdue)
        assertEquals(today, r.included.single().date)

        val moved = Forecast.project(today, 30, Money.ZERO, items, false, postponed = mapOf("r" to today.plusDays(40)))
        assertEquals(Money.ZERO, moved.inflow)
        assertEquals(today.minusDays(5), items.single().date)
    }

    @Test
    fun `sem data ou valor desconhecido torna a previsao incompleta e nao conta como zero conhecido`() {
        val items = listOf(ForecastItem("u", "Sem data", Money.euros(400), null))
        val r = Forecast.project(today, 90, Money.ZERO, items, false, unknowns = listOf("Obra X sem preço"))
        assertTrue(r.isIncomplete)
        assertEquals(1, r.undated.size)
        assertEquals(Money.ZERO, r.inflow)
    }

    @Test
    fun `ponto mais baixo do saldo`() {
        val items = listOf(
            ForecastItem("p", "Pagamento", Money.euros(-500), today.plusDays(3)),
            ForecastItem("r", "Recebimento", Money.euros(800), today.plusDays(10)),
        )
        val r = Forecast.project(today, 30, Money.euros(200), items, false)
        assertEquals(today.plusDays(3) to Money.euros(-300), r.lowest)
    }

    @Test
    fun `adiantamento recebido nao reaparece nas entradas previstas`() {
        val inst = listOf(
            Installment("i1", today.minusDays(10), Money.euros(1000)), // adiantamento, já pago
            Installment("i2", today.plusDays(15), Money.euros(1000)),
            Installment("i3", today.plusDays(40), Money.euros(1000)),
        )
        val items = Forecast.receivables("j", "Obra", balance = Money.euros(2000), received = Money.euros(1000), inst, null, Scenario.CONFIRMED)
        assertEquals(listOf(today.plusDays(15), today.plusDays(40)), items.map { it.date })
        assertEquals(Money.euros(2000), items.fold(Money.ZERO) { a, i -> a + i.amount })
    }

    @Test
    fun `saldo sem prestacoes usa hipotese explicita do fim da obra`() {
        val items = Forecast.receivables("j", "Obra", Money.euros(1500), Money.ZERO, emptyList(), today.plusDays(7), Scenario.CONFIRMED)
        assertEquals(today.plusDays(7), items.single().date)
        assertTrue(items.single().assumption!!.contains("fim da obra"))

        val none = Forecast.receivables("j", "Obra", Money.euros(1500), Money.ZERO, emptyList(), null, Scenario.CONFIRMED)
        assertEquals(null, none.single().date)
    }

    @Test
    fun `prestacoes nunca preveem mais do que o saldo em divida`() {
        val inst = listOf(Installment("i1", today.plusDays(5), Money.euros(3000)))
        // Valor devido baixou (ex.: desconto): saldo 1000.
        val items = Forecast.receivables("j", "Obra", Money.euros(1000), Money.ZERO, inst, null, Scenario.CONFIRMED)
        assertEquals(Money.euros(1000), items.single().amount)
        assertFalse(Forecast.receivables("j", "Obra", Money.ZERO, Money.ZERO, inst, null, Scenario.CONFIRMED).isNotEmpty())
    }
}
