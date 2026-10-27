package pt.obraemdia.app.ui.forms

import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pt.obraemdia.app.data.repo.NewBooking
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.app.ui.AppViewModel
import pt.obraemdia.app.ui.components.DatePickerChip
import pt.obraemdia.app.ui.components.Picker
import pt.obraemdia.app.ui.components.SectionCard
import pt.obraemdia.app.ui.components.WarningBox
import pt.obraemdia.app.ui.longLabel
import pt.obraemdia.app.ui.shortLabel
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.BookingFirmness
import pt.obraemdia.domain.schedule.Conflict
import pt.obraemdia.domain.schedule.DayPart
import pt.obraemdia.domain.schedule.TimeSlot
import pt.obraemdia.domain.schedule.WorkHours
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private enum class Period(val label: String) { FULL_DAY("Dia completo"), MORNING("Manhã"), AFTERNOON("Tarde"), CUSTOM("Horas") }

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

private fun parseTime(text: String): LocalTime? =
    runCatching { LocalTime.parse(text.trim().let { if (it.length == 4 && it[1] == ':') "0$it" else it }, hhmm) }.getOrNull()

fun Conflict.describe(snapshot: Snapshot): String {
    val who = snapshot.workerName(workerId)
    return when (this) {
        is Conflict.WithBooking -> {
            val s = other.slot.start.atZone(AGENDA_ZONE)
            val e = other.slot.end.atZone(AGENDA_ZONE)
            val kind = if (tentative) "tem uma pré-reserva" else "já está"
            "$who $kind em ${other.label} no dia ${s.toLocalDate().shortLabel()}, das ${s.format(hhmm)} às ${e.format(hhmm)}."
        }
        is Conflict.WithUnavailability -> "$who está indisponível (${other.reason}) no dia ${other.slot.start.atZone(AGENDA_ZONE).toLocalDate().shortLabel()}."
    }
}

@Composable
fun BookingScreen(vm: AppViewModel, snapshot: Snapshot, initialJobId: String?, initialDate: LocalDate?, onDone: (String) -> Unit) {
    val operationId = rememberSaveable { newId() }
    val hours = snapshot.workHours
    val jobs = snapshot.summaries.filter { it.job.status !in setOf(JobStatus.CANCELLED, JobStatus.COMPLETED) && !it.job.archived }
    var jobId by rememberSaveable { mutableStateOf(initialJobId) }
    var who by rememberSaveable { mutableStateOf<String?>(null) } // null = os dois
    // Datas guardadas como texto ISO para sobreviver à rotação do ecrã.
    var daysText by rememberSaveable { mutableStateOf((initialDate ?: vm.today.plusDays(1)).toString()) }
    var period by rememberSaveable { mutableStateOf(Period.FULL_DAY) }
    var from by rememberSaveable { mutableStateOf("08:00") }
    var to by rememberSaveable { mutableStateOf("12:00") }
    var firmness by rememberSaveable { mutableStateOf(BookingFirmness.FIRM) }
    var keepOverlap by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var saving by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var serverConflicts by remember { mutableStateOf<List<Conflict>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val days = daysText.split(',').filter { it.isNotBlank() }.map(LocalDate::parse).distinct().sorted()
    fun setDays(list: List<LocalDate>) { daysText = list.distinct().sorted().joinToString(","); serverConflicts = emptyList() }

    val job = jobs.firstOrNull { it.job.id == jobId }
    val workerIds = who?.let(::setOf) ?: snapshot.workers.map { it.id }.toSet()
    val customFrom = parseTime(from)
    val customTo = parseTime(to)
    val customInvalid = period == Period.CUSTOM && (customFrom == null || customTo == null || customTo <= customFrom)
    val slotFor: (LocalDate) -> TimeSlot? = { day ->
        when (period) {
            Period.FULL_DAY -> hours.slotFor(day, DayPart.FULL_DAY)
            Period.MORNING -> hours.slotFor(day, DayPart.MORNING)
            Period.AFTERNOON -> hours.slotFor(day, DayPart.AFTERNOON)
            Period.CUSTOM -> if (customInvalid) null else TimeSlot.local(day, customFrom!!, customTo!!)
        }
    }
    val slots = if (customInvalid) emptyList() else days.mapNotNull(slotFor)
    val preview = job?.let { vm.previewConflicts(it.job.id, it.job.siteId, it.job.title, workerIds, slots) }.orEmpty()
    val conflicts = (preview + serverConflicts).distinct()
    val conflictDays = conflicts.mapNotNull { c ->
        slots.firstOrNull { s ->
            when (c) {
                is Conflict.WithBooking -> s.overlaps(c.other.slot)
                is Conflict.WithUnavailability -> s.overlaps(c.other.slot)
            }
        }?.start?.atZone(AGENDA_ZONE)?.toLocalDate()
    }.distinct()
    val canSave = job != null && slots.isNotEmpty() && !saving && (conflicts.isEmpty() || (keepOverlap && reason.isNotBlank()))

    Column(
        Modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Picker("Obra", jobs, job, { it.job.title }, { jobId = it.job.id; serverConflicts = emptyList() })

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Quem", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(who == null, { who = null; serverConflicts = emptyList() }, { Text("Os dois") }, Modifier.heightIn(min = 48.dp))
                snapshot.workers.forEach { w ->
                    FilterChip(who == w.id, { who = w.id; serverConflicts = emptyList() }, { Text(w.name) }, Modifier.heightIn(min = 48.dp))
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Dias (podem ser descontínuos)", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                days.forEach { day ->
                    InputChip(
                        selected = day in conflictDays,
                        onClick = { setDays(days - day) },
                        label = { Text(day.longLabel()) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remover dia") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                days.lastOrNull()?.let { last ->
                    AssistChip({ setDays(days + last.plusDays(1)) }, { Text("+ dia seguinte") }, Modifier.heightIn(min = 48.dp))
                }
                DatePickerChip("Adicionar dia", days.lastOrNull() ?: vm.today) { setDays(days + it) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Período", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.entries.forEach { p ->
                    FilterChip(period == p, { period = p; serverConflicts = emptyList() }, { Text(p.label) }, Modifier.heightIn(min = 48.dp))
                }
            }
            if (period == Period.CUSTOM) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        from, { from = it }, label = { Text("Das (hh:mm)") }, singleLine = true, isError = customInvalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        to, { to = it }, label = { Text("Às (hh:mm)") }, singleLine = true, isError = customInvalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
            } else {
                val sample = slotFor(days.firstOrNull() ?: vm.today)
                sample?.let {
                    Text(
                        "${it.start.atZone(AGENDA_ZONE).format(hhmm)} – ${it.end.atZone(AGENDA_ZONE).format(hhmm)} (horário provisório)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Confirmação", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(firmness == BookingFirmness.FIRM, { firmness = BookingFirmness.FIRM }, { Text("Confirmada") }, Modifier.heightIn(min = 48.dp))
                FilterChip(firmness == BookingFirmness.TENTATIVE, { firmness = BookingFirmness.TENTATIVE }, { Text("Pré-reserva") }, Modifier.heightIn(min = 48.dp))
            }
        }

        if (conflicts.isNotEmpty() && job != null) {
            SectionCard(icon = Icons.Filled.Warning, title = "Sobreposições", caption = "Nada é movido automaticamente. Escolha uma opção:") {
                conflicts.forEach { WarningBox(it.describe(snapshot)) }

                val firstFree = slotFor(days.first())?.let { _ ->
                    vm.suggestDays(workerIds, job.job.siteId, { d -> slotFor(d)!! }, days.first())
                }.orEmpty().filter { it !in days }
                Text("1. Outro dia", style = MaterialTheme.typography.labelLarge)
                if (firstFree.isEmpty()) {
                    Text("Sem dias livres nas próximas semanas para estas pessoas.", style = MaterialTheme.typography.bodySmall)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        firstFree.forEach { free ->
                            // Substitui o primeiro dia em conflito pelo dia livre escolhido.
                            SuggestionChip(
                                onClick = { setDays(days - (conflictDays.firstOrNull() ?: days.first()) + free) },
                                label = { Text(free.longLabel()) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }

                val others = vm.freeWorkers(snapshot.workers.map { it.id } - workerIds + workerIds, job.job.siteId, slots)
                    .filter { setOf(it) != workerIds }
                Text("2. Outra pessoa", style = MaterialTheme.typography.labelLarge)
                if (others.isEmpty()) {
                    Text("Ninguém está livre em todos estes dias.", style = MaterialTheme.typography.bodySmall)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        others.forEach { w ->
                            SuggestionChip(
                                onClick = { who = w; serverConflicts = emptyList() },
                                label = { Text("Só ${snapshot.workerName(w)}") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }

                Text("3. Manter a sobreposição", style = MaterialTheme.typography.labelLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(keepOverlap, role = Role.Checkbox, onValueChange = { keepOverlap = it }),
                ) {
                    Checkbox(keepOverlap, onCheckedChange = null)
                    Text("  Manter mesmo assim")
                }
                if (keepOverlap) {
                    OutlinedTextField(
                        reason, { reason = it }, label = { Text("Motivo (obrigatório)") },
                        isError = reason.isBlank(), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        OutlinedTextField(note, { note = it }, label = { Text("Nota (opcional)") }, modifier = Modifier.fillMaxWidth())
        error?.let { WarningBox(it) }

        Button(
            onClick = {
                saving = true
                error = null
                scope.launch {
                    val (result, found) = vm.createBooking(
                        NewBooking(
                            operationId, job!!.job.id, workerIds, slots, firmness,
                            reason.takeIf { keepOverlap && it.isNotBlank() }, note,
                        ),
                    )
                    if (result.ok) {
                        onDone(result.message)
                    } else {
                        saving = false
                        serverConflicts = found
                        error = result.message
                    }
                }
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text(
                when {
                    slots.isEmpty() -> "Agendar"
                    firmness == BookingFirmness.TENTATIVE -> "Gravar pré-reserva · ${slots.size} dia(s)"
                    else -> "Agendar ${slots.size} dia(s)"
                },
            )
        }
    }
}
