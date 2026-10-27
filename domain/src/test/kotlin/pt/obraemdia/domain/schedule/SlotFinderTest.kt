package pt.obraemdia.domain.schedule

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class SlotFinderTest {

    private val hours = WorkHours()
    private val monday = LocalDate.of(2026, 11, 2)
    private val finder = SlotFinder(ConflictDetector())
    private val fullDay: (LocalDate) -> TimeSlot = { hours.slotFor(it, DayPart.FULL_DAY) }

    private fun booking(id: String, workers: Set<String>, day: LocalDate, part: DayPart = DayPart.FULL_DAY) =
        Booking(id, "job-$id", "site-$id", "Obra $id", workers, hours.slotFor(day, part))

    @Test
    fun `salta dias ocupados e fins de semana`() {
        val existing = listOf(
            booking("a", setOf("p1"), monday),
            booking("b", setOf("p2"), monday.plusDays(1)), // terça: só a Pessoa 2 está ocupada
        )
        // Ambos: segunda (p1 ocupado) e terça (p2 ocupado) ficam de fora.
        val days = finder.firstFreeDays(setOf("p1", "p2"), fullDay, monday, existing, emptyList(), count = 4)
        assertEquals(listOf(monday.plusDays(2), monday.plusDays(3), monday.plusDays(4), monday.plusDays(7)), days)
    }

    @Test
    fun `indisponibilidade de uma pessoa bloqueia o dia para ambos`() {
        val off = TimeSlot.wholeDay(monday)
        val days = finder.firstFreeDays(setOf("p1", "p2"), fullDay, monday, emptyList(), listOf(Unavailability("u", "p2", off, "Férias")), count = 1)
        assertEquals(listOf(monday.plusDays(1)), days)
    }

    @Test
    fun `manha ocupada deixa a tarde livre`() {
        val existing = listOf(booking("a", setOf("p1"), monday, DayPart.MORNING))
        val afternoon: (LocalDate) -> TimeSlot = { hours.slotFor(it, DayPart.AFTERNOON) }
        assertEquals(listOf(monday), finder.firstFreeDays(setOf("p1"), afternoon, monday, existing, emptyList(), count = 1))
    }

    @Test
    fun `sugere outra pessoa livre`() {
        val existing = listOf(booking("a", setOf("p1"), monday))
        assertEquals(listOf("p2"), finder.freeWorkers(listOf("p1", "p2"), fullDay(monday), existing, emptyList()))
    }
}
