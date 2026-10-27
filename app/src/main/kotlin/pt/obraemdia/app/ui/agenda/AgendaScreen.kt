package pt.obraemdia.app.ui.agenda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.ui.AgendaItem
import pt.obraemdia.app.ui.AgendaState
import pt.obraemdia.app.ui.components.DayMark
import pt.obraemdia.app.ui.components.MonthCalendar
import pt.obraemdia.app.ui.components.Tag
import pt.obraemdia.app.ui.components.WarningBox
import pt.obraemdia.app.ui.forms.describe
import pt.obraemdia.app.ui.longLabel
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.BookingFirmness
import pt.obraemdia.domain.schedule.Unavailability
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private fun Instant.hhmm() = atZone(AGENDA_ZONE).toLocalTime().format(timeFormat)
private fun Instant.localDate(): LocalDate = atZone(AGENDA_ZONE).toLocalDate()

/** Dias tocados por um intervalo (uma indisponibilidade pode durar vários dias). */
private fun Unavailability.days(): List<LocalDate> {
    val first = slot.start.localDate()
    val last = slot.end.minusMillis(1).localDate()
    return generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.toList()
}

/** Marcas do calendário a partir da agenda; [jobId] limita a uma obra (ficha da obra). */
fun agendaMarks(agenda: AgendaState?, workerFilter: String? = null, jobId: String? = null): Map<LocalDate, DayMark> {
    if (agenda == null) return emptyMap()
    val marks = mutableMapOf<LocalDate, DayMark>()
    agenda.items
        .filter { workerFilter == null || workerFilter in it.booking.workerIds }
        .filter { jobId == null || it.booking.jobId == jobId }
        .forEach { item ->
            val day = item.booking.slot.start.localDate()
            val m = marks[day] ?: DayMark()
            marks[day] = if (item.booking.firmness == BookingFirmness.FIRM) {
                m.copy(firm = m.firm + 1, conflict = m.conflict || item.conflicts.isNotEmpty())
            } else {
                m.copy(tentative = m.tentative + 1, conflict = m.conflict || item.conflicts.isNotEmpty())
            }
        }
    if (jobId == null) {
        agenda.timeOff.filter { workerFilter == null || it.workerId == workerFilter }.forEach { off ->
            off.days().forEach { d -> marks[d] = (marks[d] ?: DayMark()).copy(unavailable = true) }
        }
    }
    return marks
}

@Composable
fun AgendaScreen(
    snapshot: Snapshot,
    agenda: AgendaState?,
    today: LocalDate,
    onOpenJob: (String) -> Unit,
    onSchedule: (LocalDate?) -> Unit,
) {
    var workerFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var calendar by rememberSaveable { mutableStateOf(true) }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(today)) }
    var selected by rememberSaveable { mutableStateOf(today) }

    val items = agenda?.items.orEmpty()
        .filter { workerFilter == null || workerFilter in it.booking.workerIds }
        .sortedBy { it.booking.slot.start }
    val timeOff = agenda?.timeOff.orEmpty().filter { workerFilter == null || it.workerId == workerFilter }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = calendar, onClick = { calendar = true }, label = { Text("Calendário") },
                    leadingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) }, modifier = Modifier.heightIn(min = 48.dp),
                )
                FilterChip(
                    selected = !calendar, onClick = { calendar = false }, label = { Text("Lista") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null) }, modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(workerFilter == null, { workerFilter = null }, { Text("Os dois") }, Modifier.heightIn(min = 48.dp))
                snapshot.workers.forEach { w ->
                    FilterChip(workerFilter == w.id, { workerFilter = w.id }, { Text(w.name) }, Modifier.heightIn(min = 48.dp))
                }
            }
        }

        if (calendar) {
            item {
                MonthCalendar(
                    month = month,
                    onMonthChange = { month = it },
                    marks = agendaMarks(agenda, workerFilter),
                    today = today,
                    selected = selected,
                    onSelect = { selected = it },
                )
            }
            item {
                Text(
                    if (selected == today) "Hoje · ${selected.longLabel()}" else selected.longLabel(),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            dayContent(selected, items, timeOff, snapshot, onOpenJob)
            item {
                OutlinedButton(onClick = { onSchedule(selected) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  Agendar neste dia")
                }
            }
        } else {
            item {
                Button(onClick = { onSchedule(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  Agendar obra")
                }
            }
            val days = (items.map { it.booking.slot.start.localDate() } + timeOff.flatMap { it.days() })
                .filter { it >= today }.distinct().sorted()
            if (days.isEmpty()) {
                item { Text("Sem marcações a partir de hoje.", modifier = Modifier.padding(8.dp)) }
            }
            days.forEach { date ->
                item(key = "day-$date") {
                    Text(
                        if (date == today) "Hoje · ${date.longLabel()}" else date.longLabel(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                dayContent(date, items, timeOff, snapshot, onOpenJob, emptyText = null)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.dayContent(
    date: LocalDate,
    items: List<AgendaItem>,
    timeOff: List<Unavailability>,
    snapshot: Snapshot,
    onOpenJob: (String) -> Unit,
    emptyText: String? = "Nada marcado neste dia.",
) {
    val dayItems = items.filter { it.booking.slot.start.localDate() == date }
    val dayOff = timeOff.filter { date in it.days() }
    dayOff.forEach { off ->
        item(key = "off-${off.id}-$date") {
            Tag(
                "${snapshot.workerName(off.workerId)} indisponível: ${off.reason}",
                MaterialTheme.colorScheme.surfaceContainer,
                MaterialTheme.colorScheme.onSurface,
                Icons.Filled.EventBusy,
            )
        }
    }
    if (dayItems.isEmpty() && dayOff.isEmpty() && emptyText != null) {
        item(key = "empty-$date") { Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    items(dayItems, key = { "${it.booking.id}-$date" }) { BookingCard(it, snapshot, onOpenJob) }
}

@Composable
private fun BookingCard(item: AgendaItem, snapshot: Snapshot, onOpenJob: (String) -> Unit) {
    val booking = item.booking
    OutlinedCard(onClick = { onOpenJob(booking.jobId) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${booking.slot.start.hhmm()} – ${booking.slot.end.hhmm()}", style = MaterialTheme.typography.labelLarge)
                if (booking.firmness == BookingFirmness.TENTATIVE) {
                    Tag(
                        "Pré-reserva",
                        MaterialTheme.colorScheme.surfaceContainer,
                        MaterialTheme.colorScheme.onSurface,
                        Icons.AutoMirrored.Filled.HelpOutline,
                    )
                }
            }
            Text(booking.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                booking.workerIds.sorted().joinToString(" e ") { snapshot.workerName(it) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.conflicts.forEach { WarningBox(it.describe(snapshot)) }
        }
    }
}
