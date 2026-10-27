package pt.obraemdia.domain.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Montante em euros guardado como cêntimos inteiros.
 * Nunca usar Double para dinheiro: os cálculos intermédios passam por BigDecimal.
 */
@JvmInline
value class Money(val cents: Long) : Comparable<Money> {

    operator fun plus(other: Money) = Money(Math.addExact(cents, other.cents))
    operator fun minus(other: Money) = Money(Math.subtractExact(cents, other.cents))
    operator fun unaryMinus() = Money(Math.negateExact(cents))
    override fun compareTo(other: Money) = cents.compareTo(other.cents)

    val isZero get() = cents == 0L
    val isPositive get() = cents > 0L
    val isNegative get() = cents < 0L

    fun toBigDecimal(): BigDecimal = BigDecimal.valueOf(cents, 2)

    /** Formato pt-PT: "1 234,56 €" (separador de milhares com espaço não separável). */
    fun format(): String {
        val abs = Math.absExact(cents)
        val euros = (abs / 100).toString().reversed().chunked(3).joinToString(NBSP).reversed()
        val sign = if (cents < 0) "-" else ""
        return "$sign$euros,${"%02d".format(abs % 100)}$NBSP€"
    }

    override fun toString() = format()

    companion object {
        private const val NBSP = " "
        val ZERO = Money(0)

        fun euros(euros: Long) = Money(Math.multiplyExact(euros, 100L))

        /** Converte um valor decimal em euros para cêntimos, arredondando HALF_UP. */
        fun of(euros: BigDecimal) =
            Money(euros.setScale(2, RoundingMode.HALF_UP).unscaledValue().longValueExact())

        /** Aceita "12,5", "1 234,56", "12.50"; devolve null se não for um número válido. */
        fun parse(text: String): Money? {
            var clean = text.trim().removeSuffix("€").trim().replace(NBSP, "").replace(" ", "")
            if (clean.contains(',')) clean = clean.replace(".", "").replace(',', '.')
            return clean.toBigDecimalOrNull()?.let(::of)
        }
    }
}

fun Iterable<Money>.sum(): Money = fold(Money.ZERO, Money::plus)

inline fun <T> Iterable<T>.sumMoney(selector: (T) -> Money): Money =
    fold(Money.ZERO) { acc, item -> acc + selector(item) }
