package pt.obraemdia.domain.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Taxa horária em cêntimos por hora. É guardada em cada registo de horas no momento
 * em que é criado, para que alterar a taxa habitual não mude o histórico.
 */
@JvmInline
value class HourlyRate(val centsPerHour: Long) {
    init {
        require(centsPerHour >= 0) { "A taxa horária não pode ser negativa" }
    }

    /** Valor de uma linha: minutos × taxa / 60, arredondado ao cêntimo (HALF_UP) por linha. */
    fun amountFor(minutes: Int): Money {
        require(minutes >= 0) { "A duração não pode ser negativa" }
        val cents = BigDecimal.valueOf(centsPerHour)
            .multiply(BigDecimal.valueOf(minutes.toLong()))
            .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP)
        return Money(cents.longValueExact())
    }

    companion object {
        fun euros(euros: Long) = HourlyRate(euros * 100)
    }
}
