package pt.obraemdia.app.data.repo

import pt.obraemdia.app.data.db.ScheduleRow
import pt.obraemdia.app.data.db.TimeOffEntity
import pt.obraemdia.domain.schedule.Booking
import pt.obraemdia.domain.schedule.TimeSlot
import pt.obraemdia.domain.schedule.Unavailability
import java.time.Instant

/** Junta as linhas (uma por pessoa) em reservas do domínio. */
fun List<ScheduleRow>.toBookings(): List<Booking> =
    groupBy { it.entry.id }.map { (_, group) ->
        val e = group.first()
        Booking(
            id = e.entry.id,
            jobId = e.entry.jobId,
            siteId = e.siteId,
            label = e.jobTitle,
            workerIds = group.map { it.workerId }.toSet(),
            slot = TimeSlot(Instant.ofEpochMilli(e.entry.startMs), Instant.ofEpochMilli(e.entry.endMs)),
            firmness = e.entry.firmness,
        )
    }

fun List<TimeOffEntity>.toUnavailabilities(): List<Unavailability> =
    map { Unavailability(it.id, it.workerId, TimeSlot(Instant.ofEpochMilli(it.startMs), Instant.ofEpochMilli(it.endMs)), it.reason) }
