package pt.obraemdia.app.ui.settings

import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Business
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import pt.obraemdia.app.data.db.TimeOffEntity
import pt.obraemdia.app.data.db.WorkerEntity
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.ui.AppViewModel
import pt.obraemdia.app.ui.components.DatePickerChip
import pt.obraemdia.app.ui.components.SectionCard
import pt.obraemdia.app.ui.components.WarningBox
import pt.obraemdia.app.ui.forms.editText
import pt.obraemdia.app.ui.shortLabel
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.WorkHours
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
private fun parseTime(s: String): LocalTime? = runCatching { LocalTime.parse(s.trim().padStart(5, '0'), hhmm) }.getOrNull()

@Composable
fun SettingsScreen(vm: AppViewModel, snapshot: Snapshot, timeOff: List<TimeOffEntity>, onMessage: (String) -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pessoas", style = MaterialTheme.typography.titleMedium)
        snapshot.workers.forEach { w -> WorkerCard(vm, w, onMessage) }
        BusinessCard(vm, snapshot, onMessage)
        RemindersCard(vm, snapshot, onMessage)
        TimeOffCard(vm, snapshot, timeOff, onMessage)
        SectionCard(icon = Icons.Filled.Science, title = "Dados de demonstração", caption = "Obras, horas e contas fictícias para experimentar") {
            if (snapshot.hasDemoData) {
                Text("Use \"Remover\" na faixa amarela do topo para os apagar antes de começar a usar a sério.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("Não há dados de demonstração.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun WorkerCard(vm: AppViewModel, w: WorkerEntity, onMessage: (String) -> Unit) {
    var name by rememberSaveable(w.id) { mutableStateOf(w.name) }
    var sale by rememberSaveable(w.id) { mutableStateOf(Money(w.defaultSaleRateCents).editText()) }
    var cost by rememberSaveable(w.id) { mutableStateOf(Money(w.defaultCostRateCents).editText()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val saleMoney = Money.parse(sale)?.takeIf { !it.isNegative }
    val costMoney = Money.parse(cost)?.takeIf { !it.isNegative }
    val changed = name.trim() != w.name || saleMoney?.cents != w.defaultSaleRateCents || costMoney?.cents != w.defaultCostRateCents

    SectionCard(w.name, icon = Icons.Filled.Person, caption = "Taxas habituais: só pré-preenchem novos registos de horas") {
        OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                sale, { sale = it }, label = { Text("Venda €/h") }, singleLine = true, isError = saleMoney == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                cost, { cost = it }, label = { Text("Custo interno €/h") }, singleLine = true, isError = costMoney == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Venda: o que se cobra ao cliente nas obras à hora. Custo interno: o valor atribuído ao trabalho para calcular o resultado.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            enabled = changed && name.isNotBlank() && saleMoney != null && costMoney != null && !busy,
            onClick = {
                busy = true
                scope.launch { onMessage(vm.updateWorker(w.id, name, saleMoney!!, costMoney!!).message); busy = false }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Guardar") }
    }
}

@Composable
private fun BusinessCard(vm: AppViewModel, snapshot: Snapshot, onMessage: (String) -> Unit) {
    val h = snapshot.workHours
    var opening by rememberSaveable { mutableStateOf(snapshot.openingBalance.editText()) }
    var margin by rememberSaveable { mutableStateOf(snapshot.settings[pt.obraemdia.app.data.repo.Settings.TRAVEL_MARGIN_MINUTES] ?: "0") }
    var ms by rememberSaveable { mutableStateOf(h.morningStart.format(hhmm)) }
    var me by rememberSaveable { mutableStateOf(h.morningEnd.format(hhmm)) }
    var asT by rememberSaveable { mutableStateOf(h.afternoonStart.format(hhmm)) }
    var ae by rememberSaveable { mutableStateOf(h.afternoonEnd.format(hhmm)) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val openingMoney = Money.parse(opening)
    val marginMin = margin.toIntOrNull()?.takeIf { it in 0..240 }
    val hours = runCatching { WorkHours(parseTime(ms)!!, parseTime(me)!!, parseTime(asT)!!, parseTime(ae)!!) }.getOrNull()

    SectionCard(icon = Icons.Filled.Business, title = "Atividade", caption = "Valores provisórios até serem confirmados") {
        OutlinedTextField(
            opening, { opening = it }, label = { Text("Saldo inicial (€)") }, singleLine = true, isError = openingMoney == null,
            supportingText = { Text("Ponto de partida do saldo acumulado; não é o saldo bancário.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            margin, { margin = it.filter(Char::isDigit).take(3) }, label = { Text("Margem de deslocação (minutos)") }, singleLine = true,
            isError = marginMin == null,
            supportingText = { Text("Entre obras em locais diferentes; 0 = sem margem.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        Text("Horário de trabalho", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(ms, { ms = it }, label = { Text("Manhã das") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(me, { me = it }, label = { Text("às") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(asT, { asT = it }, label = { Text("Tarde das") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(ae, { ae = it }, label = { Text("às") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        if (hours == null) WarningBox("Horário inválido: use hh:mm e a manhã antes da tarde.")
        Text(
            "O horário define \"dia completo\", \"manhã\" e \"tarde\" nas novas marcações. As marcações já feitas não mudam.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            enabled = openingMoney != null && marginMin != null && hours != null && !busy &&
                (openingMoney != snapshot.openingBalance || marginMin.toString() != (snapshot.settings[pt.obraemdia.app.data.repo.Settings.TRAVEL_MARGIN_MINUTES] ?: "0") || hours != h),
            onClick = {
                busy = true
                scope.launch { onMessage(vm.saveBusiness(openingMoney!!, marginMin!!, hours!!).message); busy = false }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Guardar") }
    }
}

@Composable
private fun TimeOffCard(vm: AppViewModel, snapshot: Snapshot, timeOff: List<TimeOffEntity>, onMessage: (String) -> Unit) {
    val today = vm.today
    var worker by rememberSaveable { mutableStateOf(snapshot.workers.firstOrNull()?.id) }
    var from by rememberSaveable { mutableStateOf(today) }
    var to by rememberSaveable { mutableStateOf(today) }
    var reason by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(AGENDA_ZONE).toLocalDate()

    SectionCard(icon = Icons.Filled.EventBusy, title = "Indisponibilidades", caption = "Férias, consultas, descanso. Contam nos conflitos da agenda.") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            snapshot.workers.forEach { w -> FilterChip(worker == w.id, { worker = w.id }, { Text(w.name) }, Modifier.heightIn(min = 48.dp)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DatePickerChip("De ${from.shortLabel()}", from) { from = it; if (to < it) to = it }
            DatePickerChip("A ${to.shortLabel()}", to) { to = it }
        }
        OutlinedTextField(reason, { reason = it }, label = { Text("Motivo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(
            enabled = worker != null && reason.isNotBlank() && !to.isBefore(from) && !busy,
            onClick = {
                busy = true
                scope.launch {
                    val r = vm.addTimeOff(worker!!, from, to, reason)
                    if (r.ok) reason = ""
                    onMessage(r.message)
                    busy = false
                }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Adicionar indisponibilidade") }

        val upcoming = timeOff.filter { !day(it.endMs - 1).isBefore(today) }.sortedBy { it.startMs }
        if (upcoming.isEmpty()) Text("Sem indisponibilidades a partir de hoje.", style = MaterialTheme.typography.bodySmall)
        upcoming.forEach { t ->
            val start = day(t.startMs)
            val end = day(t.endMs - 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${snapshot.workerName(t.workerId)} · ${start.shortLabel()}" + (if (end != start) "–${end.shortLabel()}" else "") + " · ${t.reason}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { scope.launch { onMessage(vm.removeTimeOff(t.id).message) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Remover")
                }
            }
        }
    }
}

/**
 * Resumo diário opcional. A permissão de notificações só é pedida ao ligar; se for recusada,
 * nada mais deixa de funcionar.
 */
@Composable
private fun RemindersCard(vm: AppViewModel, snapshot: Snapshot, onMessage: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val saved = pt.obraemdia.app.reminders.ReminderPrefs.from(snapshot.settings)
    var prefs by remember(saved) { mutableStateOf(saved) }
    val scope = rememberCoroutineScope()

    fun save(p: pt.obraemdia.app.reminders.ReminderPrefs) {
        prefs = p
        scope.launch { onMessage(vm.saveReminders(p, context).message) }
    }

    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) save(prefs.copy(enabled = true))
        else onMessage("Sem autorização para notificações: os avisos continuam no Início da app")
    }

    fun enable() {
        val needs = android.os.Build.VERSION.SDK_INT >= 33 && !pt.obraemdia.app.reminders.ReminderWorker.canNotify(context)
        if (needs) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS) else save(prefs.copy(enabled = true))
    }

    SectionCard(
        icon = Icons.Filled.Notifications,
        title = "Lembretes",
        caption = "Um resumo por dia, só quando há algo novo. Funciona sem internet.",
    ) {
        ToggleRow("Resumo diário", prefs.enabled) { on -> if (on) enable() else save(prefs.copy(enabled = false)) }
        if (prefs.enabled) {
            Text("Hora", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(LocalTime.of(7, 0), LocalTime.of(8, 0), LocalTime.of(9, 0), LocalTime.of(19, 0)).forEach { t ->
                    FilterChip(prefs.time == t, { save(prefs.copy(time = t)) }, { Text(t.format(hhmm)) }, Modifier.heightIn(min = 48.dp))
                }
            }
            Text("Incluir", style = MaterialTheme.typography.labelLarge)
            ToggleRow("Marcações de amanhã", prefs.tomorrow) { save(prefs.copy(tomorrow = it)) }
            ToggleRow("Prestações e faturas vencidas", prefs.overdue) { save(prefs.copy(overdue = it)) }
            ToggleRow("Obras em espera a rever", prefs.holdReview) { save(prefs.copy(holdReview = it)) }
            ToggleRow("Sobreposições na agenda", prefs.conflicts) { save(prefs.copy(conflicts = it)) }
            OutlinedButton(
                onClick = { pt.obraemdia.app.reminders.ReminderWorker.runNow(context); onMessage("A preparar o resumo…") },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Experimentar agora") }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked, onCheckedChange = null)
    }
}
