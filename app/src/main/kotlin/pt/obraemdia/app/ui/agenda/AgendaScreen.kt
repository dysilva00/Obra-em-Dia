package pt.obraemdia.app.ui.agenda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import pt.obraemdia.app.ui.components.Tag
import pt.obraemdia.app.ui.components.WarningBox
import pt.obraemdia.app.ui.longLabel
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.BookingFirmness
import pt.obraemdia.domain.schedule.Conflict
import pt.obraemdia.domain.schedule.TimeSlot
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private fun Instant.hhmm() = atZone(AGENDA_ZONE).toLocalTime().format(timeFormat)

@Composable
fun AgendaScreen(
    snapshot: Snapshot,
    agenda: AgendaState?,
    today: LocalDate,
    onOpenJob: (String) -> Unit,
    onNotReady: (String) -> Unit,
) {
    var workerFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val items = agenda?.items.orEmpty()
        .filter { workerFilter == null || workerFilter in it.booking.workerIds }
        .filter { it.booking.slot.end > TimeSlot.wholeDay(today).start }
        .sortedBy { it.booking.slot.start }
    val byDay = items.groupBy { it.booking.slot.start.atZone(AGENDA_ZONE).toLocalDate() }
    val timeOff = agenda?.timeOff.orEmpty().filter { workerFilter == null || it.workerId == workerFilter }
    val days = (byDay.keys + timeOff.map { it.slot.start.atZone(AGENDA_ZONE).toLocalDate() })
        .filter { it >= today }.distinct().sorted()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = workerFilter == null, onClick = { workerFilter = null }, label = { Text("Os dois") })
                snapshot.workers.forEach { w ->
                    FilterChip(selected = workerFilter == w.id, onClick = { workerFilter = w.id }, label = { Text(w.name) })
                }
            }
        }
        item {
            Button(onClick = { onNotReady("Agendar obra") }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  Agendar obra")
            }
        }
        if (days.isEmpty()) {
            item { Text("Sem marcações nas próximas semanas.", modifier = Modifier.padding(8.dp)) }
        }
        days.forEach { date ->
            item(key = "day-$date") {
                Text(
                    if (date == today) "Hoje · ${date.longLabel()}" else date.longLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            timeOff.filter { it.slot.start.atZone(AGENDA_ZONE).toLocalDate() == date }.forEach { off ->
                item(key = "off-${off.id}") {
                    Tag(
                        "${snapshot.workerName(off.workerId)} indisponível: ${off.reason}",
                        MaterialTheme.colorScheme.surfaceContainer,
                        MaterialTheme.colorScheme.onSurface,
                        Icons.Filled.EventBusy,
                    )
                }
            }
            items(byDay[date].orEmpty(), key = { it.booking.id }) { BookingCard(it, snapshot, onOpenJob) }
        }
    }
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
            item.conflicts.forEach { c ->
                val who = snapshot.workerName(c.workerId)
                val text = when (c) {
                    is Conflict.WithBooking -> {
                        val kind = if (c.tentative) "tem uma pré-reserva" else "já está"
                        "$who $kind em ${c.other.label}, das ${c.other.slot.start.hhmm()} às ${c.other.slot.end.hhmm()}."
                    }
                    is Conflict.WithUnavailability -> "$who está indisponível: ${c.other.reason}."
                }
                WarningBox(text)
            }
        }
    }
}
