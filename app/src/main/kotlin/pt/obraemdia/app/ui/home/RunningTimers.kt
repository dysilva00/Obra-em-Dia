package pt.obraemdia.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pt.obraemdia.app.data.db.ActiveTimerEntity
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.ui.formatMinutes
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import java.time.Instant
import java.time.format.DateTimeFormatter

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

/** Cronómetros a contar, em destaque no Início para não ficarem esquecidos. */
@Composable
fun RunningTimers(
    timers: List<ActiveTimerEntity>,
    snapshot: Snapshot,
    onStop: (groupId: String) -> Unit,
    onDiscard: (groupId: String) -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    var discard by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        timers.groupBy { it.groupId }.forEach { (groupId, group) ->
            val first = group.first()
            val started = Instant.ofEpochMilli(first.startedAt).atZone(AGENDA_ZONE)
            val elapsed = ((now - first.startedAt) / 60_000).toInt().coerceAtLeast(0)
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Timer, contentDescription = null)
                        Text(
                            "Cronómetro · ${formatMinutes(elapsed)}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        "${snapshot.summary(first.jobId)?.job?.title ?: "Obra"} · " +
                            group.joinToString(" e ") { snapshot.workerName(it.workerId) } +
                            " · desde as ${started.format(hhmm)}" +
                            if (started.toLocalDate() != Instant.ofEpochMilli(now).atZone(AGENDA_ZONE).toLocalDate()) " de ${started.dayOfMonth}/${started.monthValue}" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onStop(groupId) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Text("  Parar e registar")
                        }
                        TextButton(onClick = { discard = groupId }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Descartar") }
                    }
                }
            }
        }
    }

    discard?.let { groupId ->
        AlertDialog(
            onDismissRequest = { discard = null },
            title = { Text("Descartar o cronómetro?") },
            text = { Text("O tempo contado não é registado.") },
            confirmButton = { TextButton(onClick = { onDiscard(groupId); discard = null }) { Text("Descartar") } },
            dismissButton = { TextButton(onClick = { discard = null }) { Text("Continuar a contar") } },
        )
    }
}
