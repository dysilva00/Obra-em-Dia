package pt.obraemdia.domain.billing

import org.junit.jupiter.api.Test
import pt.obraemdia.domain.money.Allocation
import pt.obraemdia.domain.money.DocumentAmounts
import pt.obraemdia.domain.money.Money
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CostsTest {

    private val doc = DocumentAmounts(Money(10000), Money(2300), Money(12300))

    @Test
    fun `iva recuperavel fica fora do custo, nao recuperavel entra`() {
        assertEquals(Money(10000), Costs.managementCost(doc, VatTreatment.RECOVERABLE))
        assertEquals(Money(12300), Costs.managementCost(doc, VatTreatment.NOT_RECOVERABLE))
        assertEquals(Money(12300), Costs.managementCost(doc, VatTreatment.UNKNOWN))
    }

    @Test
    fun `nota de credito reduz o custo`() {
        val credit = Costs.signed(DocumentType.CREDIT_NOTE, DocumentAmounts(Money(1000), Money(230), Money(1230)))
        assertEquals(Money(-1230), credit.gross)
        val total = Costs.managementCost(doc, VatTreatment.NOT_RECOVERABLE) + Costs.managementCost(credit, VatTreatment.NOT_RECOVERABLE)
        assertEquals(Money(11070), total)
    }

    @Test
    fun `guias e proformas nao criam despesa`() {
        assertFalse(DocumentType.DELIVERY_NOTE.createsExpense)
        assertFalse(DocumentType.PRO_FORMA.createsExpense)
        assertFalse(DocumentType.RECEIPT.createsExpense)
        assertTrue(DocumentType.INVOICE.createsExpense)
    }

    @Test
    fun `repartido por duas obras o custo total nao duplica`() {
        val split = Allocation.splitDocument(doc, listOf(Money.euros(80), Money.euros(43)))
        val total = split.lines.sumOf { Costs.managementCost(it, VatTreatment.RECOVERABLE).cents }
        assertEquals(10000L, total)
    }

    @Test
    fun `nif valida digito de controlo`() {
        assertTrue(Nif.isValid("123456789"))
        assertTrue(Nif.isValid("501 964 843"))
        assertFalse(Nif.isValid("123456780"))
        assertFalse(Nif.isValid("12345678"))
        assertFalse(Nif.isValid("12345678a"))
    }
}
