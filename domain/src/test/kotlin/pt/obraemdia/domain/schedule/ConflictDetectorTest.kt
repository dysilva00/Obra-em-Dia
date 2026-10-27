package pt.obraemdia.domain.schedule

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConflictDetectorTest {

    private val day = LocalDate.of(2026, 10, 28)
    private val hours = WorkHours()

    private fun booking(id: String, workers: Set<String>, slot: TimeSlot, site: String? = "site-$id", firmness: BookingFirmness = BookingFirmness.FIRM) =
        Booking(id, "job-$id", site, "Obra $id", workers, slot, firmness)

    @Test
    fun `obras simultaneas com pessoas diferentes sao permitidas`() {
        val a = booking("a", setOf("p1"), hours.slotFor(day, DayPart.FULL_DAY))
        val b = booking("b", setOf("p2"), hours.slotFor(day, DayPart.FULL_DAY))

        assertTrue(ConflictDetector().conflictsFor(b, listOf(a), emptyList()).isEmpty())
    }

    @Test
    fun `reserva para ambos conflita com indisponibilidade de qualquer um`() {
        val both = booking("a", setOf("p1", "p2"), hours.slotFor(day, DayPart.MORNING))
        val off = Unavailability("u1", "p2", TimeSlot.wholeDay(day), "Consulta")

        val conflicts = ConflictDetector().conflictsFor(both, emptyList(), listOf(off))

        assertEquals(listOf<Conflict>(Conflict.WithUnavailability("p2", off)), conflicts)
    }

    @Test
    fun `reservas contiguas nao conflituam sem margem`() {
        val morning = booking("a", setOf("p1"), hours.slotFor(day, DayPart.MORNING))
        val next = booking("b", setOf("p1"), TimeSlot.local(day, LocalTime.of(12, 0), LocalTime.of(13, 0)))

        assertTrue(ConflictDetector().conflictsFor(next, listOf(morning), emptyList()).isEmpty())
    }

    @Test
    fun `margem de deslocacao cria conflito entre locais diferentes`() {
        val morning = booking("a", setOf("p1"), hours.slotFor(day, DayPart.MORNING))
        val next = booking("b", setOf("p1"), TimeSlot.local(day, LocalTime.of(12, 0), LocalTime.of(13, 0)))
        val sameSite = next.copy(siteId = morning.siteId)
        val detector = ConflictDetector(travelMargin = Duration.ofMinutes(30))

        assertEquals(1, detector.conflictsFor(next, listOf(morning), emptyList()).size)
        assertTrue(detector.conflictsFor(sameSite, listOf(morning), emptyList()).isEmpty())
    }

    @Test
    fun `pre-reserva gera aviso distinto de ocupacao firme`() {
        val tentative = booking("a", setOf("p1"), hours.slotFor(day, DayPart.FULL_DAY), firmness = BookingFirmness.TENTATIVE)
        val candidate = booking("b", setOf("p1"), hours.slotFor(day, DayPart.AFTERNOON))

        val conflict = ConflictDetector().conflictsFor(candidate, listOf(tentative), emptyList()).single()

        assertTrue((conflict as Conflict.WithBooking).tentative)
    }

    @Test
    fun `mudanca da hora em Lisboa`() {
        // 2026-03-29: às 01:00 passa a 02:00 (dia de 23 h); 2026-10-25: às 02:00 volta a 01:00 (dia de 25 h).
        assertEquals(Duration.ofHours(23), TimeSlot.wholeDay(LocalDate.of(2026, 3, 29)).duration)
        assertEquals(Duration.ofHours(25), TimeSlot.wholeDay(LocalDate.of(2026, 10, 25)).duration)
        assertEquals(
            Duration.ofHours(3),
            TimeSlot.local(LocalDate.of(2026, 3, 29), LocalTime.MIDNIGHT, LocalTime.of(4, 0)).duration,
        )
        // O dia de trabalho não é afetado: 08:00–17:00 continua a ter 9 h.
        assertEquals(Duration.ofHours(9), hours.slotFor(LocalDate.of(2026, 10, 25), DayPart.FULL_DAY).duration)
    }
}
