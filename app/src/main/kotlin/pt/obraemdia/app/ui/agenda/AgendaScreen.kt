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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.HelpOutline
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
import pt.obraemdia.app.demo.DemoData
import pt.obraemdia.app.ui.components.Tag
import pt.obraemdia.app.ui.components.WarningBox
import pt.obraemdia.app.ui.longLabel
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.Booking
import pt.obraemdia.domain.schedule.BookingFirmness
import pt.obraemdia.domain.schedule.Conflict
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun AgendaScreen(onOpenJob: (String) -> Unit, onNotReady: (String) -> Unit) {
    val d = DemoData
    var workerFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = d.bookings
        .filter { workerFilter == null || workerFilter in it.workerIds }
        .sortedBy { it.slot.start }
        .groupBy { it.slot.start.atZone(AGENDA_ZONE).toLocalDate() }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = workerFilter == null, onClick = { workerFilter = null }, label = { Text("Os dois") })
                d.workers.forEach { w ->
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
        visible.forEach { (date, bookings) ->
            item(key = date.toString()) {
                Text(date.longLabel(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            }
            items(bookings, key = { it.id }) { b -> BookingCard(b, d.conflicts[b.id].orEmpty(), onOpenJob) }
        }
    }
}

@Composable
private fun BookingCard(booking: Booking, conflicts: List<Conflict>, onOpenJob: (String) -> Unit) {
    val d = DemoData
    val start = booking.slot.start.atZone(AGENDA_ZONE).toLocalTime().format(timeFormat)
    val end = booking.slot.end.atZone(AGENDA_ZONE).toLocalTime().format(timeFormat)
    val tentative = booking.firmness == BookingFirmness.TENTATIVE

    OutlinedCard(onClick = { onOpenJob(booking.jobId) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$start – $end", style = MaterialTheme.typography.labelLarge)
                if (tentative) {
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
                booking.workerIds.sorted().joinToString(" e ") { d.workerName(it) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            conflicts.forEach { c ->
                val who = d.workerName(c.workerId)
                val text = when (c) {
                    is Conflict.WithBooking -> {
                        val s = c.other.slot.start.atZone(AGENDA_ZONE).toLocalTime().format(timeFormat)
                        val e = c.other.slot.end.atZone(AGENDA_ZONE).toLocalTime().format(timeFormat)
                        val kind = if (c.tentative) "tem uma pré-reserva" else "já está"
                        "$who $kind em ${c.other.label}, das $s às $e."
                    }
                    is Conflict.WithUnavailability -> "$who está indisponível: ${c.other.reason}."
                }
                WarningBox(text)
            }
        }
    }
}
