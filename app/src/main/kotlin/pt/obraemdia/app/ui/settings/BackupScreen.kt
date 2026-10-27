package pt.obraemdia.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pt.obraemdia.app.backup.BackupException
import pt.obraemdia.app.backup.BackupManifest
import pt.obraemdia.app.backup.StagedRestore
import pt.obraemdia.app.data.repo.BACKUP_MAX_AGE_DAYS
import pt.obraemdia.app.data.repo.Settings
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.ui.AppViewModel
import pt.obraemdia.app.ui.components.LabelValue
import pt.obraemdia.app.ui.components.SectionCard
import pt.obraemdia.app.ui.components.WarningBox
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

private val stamp = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm")
private fun Long.stampLabel() = Instant.ofEpochMilli(this).atZone(AGENDA_ZONE).format(stamp)
private val fileStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

/** Resumo legível das contagens do manifesto. */
private fun BackupManifest.countLines(): List<Pair<String, String>> = listOf(
    "Obras" to "obras",
    "Registos de horas" to "horas",
    "Movimentos de dinheiro" to "movimentos",
    "Compras" to "compras",
    "Faturas emitidas" to "faturasEmitidas",
    "Fotos e anexos" to "anexos",
).map { (label, key) -> label to (counts[key] ?: 0).toString() }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BackupScreen(vm: AppViewModel, snapshot: Snapshot, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var staged by remember { mutableStateOf<StagedRestore?>(null) }
    val lastBackup = snapshot.settings[Settings.LAST_BACKUP_AT]?.toLongOrNull()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "A criar a cópia…"
        error = null
        scope.launch {
            try {
                val manifest = context.contentResolver.openOutputStream(uri, "wt")!!.use { vm.exportBackup(it) }
                onMessage("Cópia guardada: ${manifest.files.size - 1} anexo(s) incluído(s)")
            } catch (e: Exception) {
                // Não deixar um ficheiro meio escrito com aspeto de backup.
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                error = "Não foi possível criar a cópia: ${(e as? BackupException)?.message ?: e.message}"
            } finally {
                busy = null
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "A verificar a cópia…"
        error = null
        scope.launch {
            try {
                staged = context.contentResolver.openInputStream(uri)!!.use { vm.prepareRestore(it) }
            } catch (e: BackupException) {
                error = e.message
            } catch (e: Exception) {
                error = "Não foi possível ler o ficheiro: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard("Guardar cópia", icon = Icons.Filled.CloudUpload, caption = "Um ficheiro .zip com todos os dados, fotos e anexos") {
            Text(
                if (lastBackup == null) "Ainda não foi feita nenhuma cópia." else "Última cópia: ${lastBackup.stampLabel()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Guarde o ficheiro fora do telemóvel (Google Drive, e-mail, computador ou pen). " +
                    "Para mudar de telemóvel, instale a app no novo e use \"Restaurar\" com esse ficheiro. " +
                    "A app lembra no Início quando a cópia tiver mais de $BACKUP_MAX_AGE_DAYS dias.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { exportLauncher.launch("obra-em-dia-${java.time.LocalDateTime.now(AGENDA_ZONE).format(fileStamp)}.zip") },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null)
                Text("  Guardar cópia de segurança")
            }
        }

        SectionCard("Restaurar", icon = Icons.Filled.Restore, caption = "Substitui todos os dados deste telemóvel pelos da cópia") {
            Text(
                "O ficheiro é verificado antes de mudar alguma coisa. Se estiver danificado ou for de uma versão mais recente da app, nada é alterado.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Icon(Icons.Filled.Restore, contentDescription = null)
                Text("  Escolher ficheiro de cópia")
            }
        }

        busy?.let {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingIndicator()
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        error?.let { WarningBox(it) }
    }

    staged?.let { s ->
        AlertDialog(
            onDismissRequest = { vm.discardRestore(); staged = null },
            title = { Text("Restaurar esta cópia?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cópia de ${s.manifest.createdAt.stampLabel()}", style = MaterialTheme.typography.titleSmall)
                    s.manifest.countLines().forEach { (label, value) -> LabelValue(label, value) }
                    if ((s.manifest.counts["demonstracao"] ?: 0) > 0) {
                        Text("Inclui ${s.manifest.counts["demonstracao"]} obra(s) de demonstração.", style = MaterialTheme.typography.bodySmall)
                    }
                    WarningBox("Os dados atuais deste telemóvel são substituídos. A app fecha e volta a abrir.")
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.scheduleRestore(s)
                    // Reiniciar: o restauro é aplicado no arranque, antes de a base ser aberta.
                    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                    context.startActivity(Intent.makeRestartActivityTask(launch.component))
                    exitProcess(0)
                }) { Text("Restaurar") }
            },
            dismissButton = { TextButton(onClick = { vm.discardRestore(); staged = null }) { Text("Cancelar") } },
        )
    }
}
