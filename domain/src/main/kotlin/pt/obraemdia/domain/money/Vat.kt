package pt.obraemdia.domain.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * IVA a repercutir ao cliente. A taxa vem sempre da configuração (pontos-base: 2300 = 23 %);
 * nunca se assume uma taxa. Sem taxa configurada, o total com IVA é desconhecido.
 */
object Vat {

    /** IVA de [net] à taxa [rateBp], arredondado ao cêntimo (HALF_UP). */
    fun amount(net: Money, rateBp: Int): Money {
        require(rateBp >= 0) { "Taxa de IVA inválida" }
        val cents = BigDecimal.valueOf(net.cents)
            .multiply(BigDecimal.valueOf(rateBp.toLong()))
            .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
        return Money(cents.longValueExact())
    }

    fun gross(net: Money, rateBp: Int): Money = net + amount(net, rateBp)

    /** Taxa em texto pt-PT: 2300 → "23 %", 650 → "6,5 %". */
    fun label(rateBp: Int): String =
        BigDecimal.valueOf(rateBp.toLong(), 2).stripTrailingZeros().toPlainString().replace('.', ',') + " %"
}
