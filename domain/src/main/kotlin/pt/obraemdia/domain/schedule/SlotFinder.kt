package pt.obraemdia.domain.schedule

import java.time.DayOfWeek
import java.time.LocalDate

/** Procura alternativas sem conflito para uma reserva. Nunca move reservas existentes. */
class SlotFinder(
    private val detector: ConflictDetector,
    private val workDays: Set<DayOfWeek> = DayOfWeek.entries.toSet() - setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
) {

    /**
     * Primeiros [count] dias de trabalho, a partir de [from], em que [workerIds] estão todos livres
     * no período dado por [slotFor]. Pré-reservas contam como ocupação (geram aviso).
     */
    fun firstFreeDays(
        workerIds: Set<String>,
        slotFor: (LocalDate) -> TimeSlot,
        from: LocalDate,
        existing: List<Booking>,
        unavailabilities: List<Unavailability>,
        count: Int = 3,
        horizonDays: Long = 90,
        siteId: String? = null,
    ): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }
            .takeWhile { it <= from.plusDays(horizonDays) }
            .filter { it.dayOfWeek in workDays }
            .filter { day -> isFree(workerIds, slotFor(day), existing, unavailabilities, siteId) }
            .take(count)
            .toList()

    /** Pessoas de [candidates] livres em [slot] (para "atribuir outra pessoa disponível"). */
    fun freeWorkers(
        candidates: Collection<String>,
        slot: TimeSlot,
        existing: List<Booking>,
        unavailabilities: List<Unavailability>,
        siteId: String? = null,
    ): List<String> = candidates.filter { isFree(setOf(it), slot, existing, unavailabilities, siteId) }

    private fun isFree(
        workerIds: Set<String>,
        slot: TimeSlot,
        existing: List<Booking>,
        unavailabilities: List<Unavailability>,
        siteId: String?,
    ): Boolean {
        val probe = Booking(PROBE_ID, PROBE_ID, siteId, "", workerIds, slot)
        return detector.conflictsFor(probe, existing, unavailabilities).isEmpty()
    }

    private companion object {
        const val PROBE_ID = "__probe__"
    }
}
