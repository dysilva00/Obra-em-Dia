package pt.obraemdia.domain.fiscalqr

import org.junit.jupiter.api.Test
import pt.obraemdia.domain.money.Money
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exemplos retirados das Especificações Técnicas do Código QR da AT, versão 1.1. */
class AtQrParserTest {

    private fun ok(text: String) = (AtQrParser.parse(text) as AtQrResult.Ok).document

    @Test
    fun `exemplo 1 - fatura com tres espacos fiscais`() {
        val d = ok(
            "A:123456789*B:999999990*C:PT*D:FT*E:N*F:20191231*G:FT AB2019/0035*H:CSDF7T5H-0035*" +
                "I1:PT*I2:12000.00*I3:15000.00*I4:900.00*I5:50000.00*I6:6500.00*I7:80000.00*I8:18400.00*" +
                "J1:PT-AC*J2:10000.00*J3:25000.56*J4:1000.02*J5:75000.00*J6:6750.00*J7:100000.00*J8:18000.00*" +
                "K1:PT-MA*K2:5000.00*K3:12500.00*K4:625.00*K5:25000.00*K6:3000.00*K7:40000.00*K8:8800.00*" +
                "L:100.00*M:25.00*N:64000.02*O:513600.58*P:100.00*Q:kLp0*R:9999*S:TB;PT00000000000000000000000;513500.58",
        )
        assertEquals("123456789", d.issuerNif)
        assertTrue(d.isFinalConsumer)
        assertEquals("FT", d.docType)
        assertEquals(LocalDate.of(2019, 12, 31), d.date)
        assertEquals("FT AB2019/0035", d.docNumber)
        assertEquals("CSDF7T5H-0035", d.atcud)
        assertEquals(Money(51360058), d.gross)
        assertEquals(Money(6400002), d.totalTaxes)
        assertEquals(Money(51360058 - 6400002), d.net)
        assertEquals(Money(10000), d.withholding)
        // Soma das bases (I, J, K) e do IVA de cada espaço fiscal.
        assertEquals(Money(44950056), d.taxableBase)
        assertEquals(Money(6397502), d.vat)
    }

    @Test
    fun `exemplo 2 - fatura simplificada`() {
        val d = ok("A:123456789*B:999999990*C:PT*D:FS*E:N*F:20190812*G:FS CDVF/12345*H:CDF7T5HD-12345*I1:PT*I7:0.65*I8:0.15*N:0.15*O:0.80*Q:YhGV*R:9999*S:NU;0.80")
        assertEquals(Money(80), d.gross)
        assertEquals(Money(65), d.net)
        assertTrue(d.isInvoice)
    }

    @Test
    fun `exemplo 3 - pro-forma nao e fatura`() {
        val d = ok("A:500000000*B:123456789*C:PT*D:PF*E:N*F:20190123*G:PF G2019CB/145789*H:HB6FT7RV-145789*I1:PT*I2:12345.34*N:15072.44*O:125165.12*Q:r/fY*R:9999")
        assertEquals("PF", d.docType)
        assertTrue(!d.isInvoice)
    }

    @Test
    fun `exemplo 6 - nota de debito`() {
        val d = ok("A:123456789*B:500000000*C:PT*D:ND*E:N*F:20190216*G:M1F KLG/6145*H:RQD8L6DG-6145*I1:PT-MA*I6:26.50*N:26.50*O:26.50*Q:h1rB*R:9999")
        assertTrue(d.isDebitNote)
        assertEquals(Money.ZERO, d.net)
    }

    @Test
    fun `exemplo 7 - regime da margem usa L`() {
        val d = ok("A:500000000*B:123456789*C:PT*D:FT*E:N*F:20191124*G:NF 19A/789145*H:JL9DS4TT-789145*I1:PT-AC*I7:50.00*I8:9.00*L:1000.00*N:9.00*O:1059.00*Q:d8/K*R:9999")
        assertEquals(Money(100000), d.notSubject)
        assertEquals(Money(105000), d.net)
    }

    @Test
    fun `documento anulado e atcud zero`() {
        val d = ok("A:123456789*B:999999990*C:PT*D:FR*E:A*F:20260115*G:FR A/1*H:0*I1:PT*I7:100.00*I8:23.00*N:23.00*O:123.00*Q:abcd*R:0")
        assertTrue(d.isCancelled)
        assertNull(d.atcud)
    }

    @Test
    fun `entradas invalidas dao mensagem util`() {
        assertIs<AtQrResult.Invalid>(AtQrParser.parse("https://exemplo.pt"))
        assertIs<AtQrResult.Invalid>(AtQrParser.parse("A:123456789*B:999999990"))
        assertIs<AtQrResult.Invalid>(AtQrParser.parse("A:1*B:2*C:PT*D:FT*E:N*F:20261301*G:X*H:0*N:0.00*O:1.00"))
        assertIs<AtQrResult.Invalid>(AtQrParser.parse("A:1*B:2*C:PT*D:FT*E:N*F:20260101*G:X*H:0*N:0.00*O:1,00"))
    }
}
