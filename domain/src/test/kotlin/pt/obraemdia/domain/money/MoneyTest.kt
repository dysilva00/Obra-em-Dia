package pt.obraemdia.domain.money

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyTest {

    @Test
    fun `formata em pt-PT`() {
        assertEquals("1 234,56 €", Money(123456).format())
        assertEquals("-0,05 €", Money(-5).format())
        assertEquals("3 000,00 €", Money.euros(3000).format())
    }

    @Test
    fun `interpreta texto introduzido`() {
        assertEquals(Money(1250), Money.parse("12,5"))
        assertEquals(Money(123456), Money.parse("1 234,56 €"))
        assertEquals(Money(1250), Money.parse("12.50"))
        assertEquals(Money(1), Money.parse("0,005"))
        assertNull(Money.parse("abc"))
    }

    @Test
    fun `taxa horaria arredonda por linha`() {
        assertEquals(Money.euros(120), HourlyRate.euros(15).amountFor(480))
        // 25 €/h × 20 min = 8,333… → 8,33
        assertEquals(Money(833), HourlyRate.euros(25).amountFor(20))
        // 0,10 €/h × 15 min = 0,025 → 0,03 (HALF_UP)
        assertEquals(Money(3), HourlyRate(10).amountFor(15))
    }

    @Test
    fun `reparticao 123 em 80 e 43 soma 123 e nunca 246`() {
        val doc = DocumentAmounts(net = Money(10000), vat = Money(2300), gross = Money(12300))
        val result = Allocation.splitDocument(doc, listOf(Money.euros(80), Money.euros(43)))

        assertTrue(result.isComplete)
        assertEquals(doc.gross, result.lines.map { it.gross }.sum())
        assertEquals(doc.net, result.lines.map { it.net }.sum())
        assertEquals(doc.vat, result.lines.map { it.vat }.sum())
    }

    @Test
    fun `parcela por classificar fica visivel`() {
        val doc = DocumentAmounts(Money(10000), Money(2300), Money(12300))
        val result = Allocation.splitDocument(doc, listOf(Money.euros(80)))

        assertEquals(Money.euros(43), result.unallocated)
        assertTrue(!result.isComplete)
    }

    @Test
    fun `parcelas acima do total sao rejeitadas`() {
        val doc = DocumentAmounts(Money(10000), Money(2300), Money(12300))
        assertThrows<IllegalArgumentException> {
            Allocation.splitDocument(doc, listOf(Money.euros(100), Money.euros(43)))
        }
    }

    @Test
    fun `nota de credito reparte valores negativos`() {
        val doc = DocumentAmounts(Money(-1000), Money(-230), Money(-1230))
        val result = Allocation.splitDocument(doc, listOf(Money(-615), Money(-615)))

        assertTrue(result.isComplete)
        assertEquals(doc.net, result.lines.map { it.net }.sum())
    }

    @Test
    fun `cetimos residuais tem regra deterministica`() {
        val parts = Allocation.proportional(Money(100), listOf(1, 1, 1))
        assertEquals(listOf(Money(34), Money(33), Money(33)), parts)
        assertEquals(Money(100), parts.sum())
    }
}
