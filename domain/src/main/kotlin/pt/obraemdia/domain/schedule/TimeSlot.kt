package pt.obraemdia.domain.schedule

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Fuso único da agenda nesta versão. */
val AGENDA_ZONE: ZoneId = ZoneId.of("Europe/Lisbon")

/** Intervalo semiaberto [start, end): reservas contíguas não se intersetam. */
data class TimeSlot(val start: Instant, val end: Instant) {
    init {
        require(end.isAfter(start)) { "O fim tem de ser posterior ao início" }
    }

    val duration: Duration get() = Duration.between(start, end)

    fun overlaps(other: TimeSlot) = start < other.end && end > other.start

    /** Alarga o intervalo nos dois lados, por exemplo pela margem de deslocação. */
    fun expandedBy(margin: Duration) = TimeSlot(start - margin, end + margin)

    companion object {
        /** Constrói o intervalo a partir de horas locais em Lisboa, respeitando a mudança da hora. */
        fun local(date: LocalDate, from: LocalTime, to: LocalTime, zone: ZoneId = AGENDA_ZONE) =
            TimeSlot(date.atTime(from).atZone(zone).toInstant(), date.atTime(to).atZone(zone).toInstant())

        /** Dia civil completo; num dia de mudança da hora dura 23 ou 25 horas. */
        fun wholeDay(date: LocalDate, zone: ZoneId = AGENDA_ZONE) =
            TimeSlot(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
    }
}

enum class DayPart { FULL_DAY, MORNING, AFTERNOON }

/**
 * Horário de trabalho configurável. Os valores por omissão são provisórios
 * até serem confirmados pelos utilizadores (spec §20).
 */
data class WorkHours(
    val morningStart: LocalTime = LocalTime.of(8, 0),
    val morningEnd: LocalTime = LocalTime.of(12, 0),
    val afternoonStart: LocalTime = LocalTime.of(13, 0),
    val afternoonEnd: LocalTime = LocalTime.of(17, 0),
) {
    init {
        require(morningStart < morningEnd && morningEnd <= afternoonStart && afternoonStart < afternoonEnd) {
            "Horário de trabalho inválido"
        }
    }

    fun slotFor(date: LocalDate, part: DayPart, zone: ZoneId = AGENDA_ZONE): TimeSlot = when (part) {
        DayPart.FULL_DAY -> TimeSlot.local(date, morningStart, afternoonEnd, zone)
        DayPart.MORNING -> TimeSlot.local(date, morningStart, morningEnd, zone)
        DayPart.AFTERNOON -> TimeSlot.local(date, afternoonStart, afternoonEnd, zone)
    }
}
