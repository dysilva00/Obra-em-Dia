package pt.obraemdia.app.data.repo

import pt.obraemdia.app.data.db.TimeEntryEntity
import pt.obraemdia.app.ui.formatMinutes
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Verificações das horas de uma pessoa num dia: sobreposição com registos que têm início e fim,
 * e total do dia acima de [MAX_DAY_MINUTES]. Não bloqueiam: pedem confirmação, porque podem
 * resultar de um registo antigo errado que se corrige depois.
 */
object HourChecks {
    const val MAX_DAY_MINUTES = 16 * 60
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    fun instant(date: LocalDate, time: LocalTime): Long = date.atTime(time).atZone(AGENDA_ZONE).toInstant().toEpochMilli()

    /** Minutos de trabalho entre [start] e [end] descontando a pausa; null se o intervalo não for válido. */
    fun minutesBetween(start: LocalTime, end: LocalTime, breakMinutes: Int): Int? {
        if (!end.isAfter(start) || breakMinutes < 0) return null
        val total = java.time.Duration.between(start, end).toMinutes().toInt() - breakMinutes
        return total.takeIf { it > 0 }
    }

    /**
     * [existing]: registos do mesmo dia (de qualquer obra). Os da mesma [operationId] são ignorados,
     * para que repetir uma gravação não se acuse a si própria.
     */
    fun warnings(
        existing: List<TimeEntryEntity>,
        operationId: String,
        workerIds: List<String>,
        date: LocalDate,
        minutes: Int,
        start: LocalTime?,
        end: LocalTime?,
        workerName: (String) -> String,
    ): List<String> = buildList {
        val startMs = start?.let { instant(date, it) }
        val endMs = end?.let { instant(date, it) }
        workerIds.forEach { w ->
            val sameDay = existing.filter { it.workerId == w && it.date == date && it.deletedAt == null && it.operationId != operationId }
            if (startMs != null && endMs != null) {
                sameDay.filter { it.startMs != null && it.endMs != null && it.startMs < endMs && startMs < it.endMs }.forEach {
                    add("${workerName(w)} já tem horas das ${it.startMs!!.hhmm()} às ${it.endMs!!.hhmm()} neste dia")
                }
            }
            val total = sameDay.sumOf { it.minutes } + minutes
            if (sameDay.isNotEmpty() && total > MAX_DAY_MINUTES) {
                add("${workerName(w)} ficaria com ${formatMinutes(total)} registadas neste dia")
            }
        }
    }

    private fun Long.hhmm() = Instant.ofEpochMilli(this).atZone(AGENDA_ZONE).format(hhmm)
}
