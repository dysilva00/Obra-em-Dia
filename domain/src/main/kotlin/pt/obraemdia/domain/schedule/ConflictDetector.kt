package pt.obraemdia.domain.schedule

import java.time.Duration

enum class BookingFirmness { FIRM, TENTATIVE }

/** Visita/reserva de uma obra, que ocupa a agenda de cada pessoa atribuída. */
data class Booking(
    val id: String,
    val jobId: String,
    val siteId: String?,
    val label: String,
    val workerIds: Set<String>,
    val slot: TimeSlot,
    val firmness: BookingFirmness = BookingFirmness.FIRM,
)

/** Indisponibilidade de uma pessoa: férias, descanso, consulta, etc. */
data class Unavailability(val id: String, val workerId: String, val slot: TimeSlot, val reason: String)

sealed interface Conflict {
    val workerId: String

    /** Sobreposição com outra reserva da mesma pessoa. [tentative] distingue pré-reserva de ocupação firme. */
    data class WithBooking(override val workerId: String, val other: Booking) : Conflict {
        val tentative get() = other.firmness == BookingFirmness.TENTATIVE
    }

    data class WithUnavailability(override val workerId: String, val other: Unavailability) : Conflict
}

/**
 * Deteta conflitos de uma reserva candidata. Só as mesmas pessoas conflituam:
 * obras simultâneas com pessoas diferentes são permitidas.
 * A margem de deslocação só se aplica entre locais diferentes.
 */
class ConflictDetector(private val travelMargin: Duration = Duration.ZERO) {

    fun conflictsFor(
        candidate: Booking,
        existing: List<Booking>,
        unavailabilities: List<Unavailability>,
    ): List<Conflict> {
        val bookingConflicts = existing.asSequence()
            .filter { it.id != candidate.id }
            .flatMap { other ->
                val margin = if (sameSite(candidate, other)) Duration.ZERO else travelMargin
                val hits = candidate.slot.expandedBy(margin).overlaps(other.slot)
                (candidate.workerIds intersect other.workerIds)
                    .filter { hits }
                    .map { Conflict.WithBooking(it, other) }
            }
        val unavailabilityConflicts = unavailabilities.asSequence()
            .filter { it.workerId in candidate.workerIds && candidate.slot.overlaps(it.slot) }
            .map { Conflict.WithUnavailability(it.workerId, it) }
        return (bookingConflicts + unavailabilityConflicts).toList()
    }

    private fun sameSite(a: Booking, b: Booking) = a.siteId != null && a.siteId == b.siteId
}
