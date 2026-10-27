package pt.obraemdia.app.export

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

/** Gera o PDF na pasta temporária de exportações (a única partilhável pelo FileProvider). */
fun exportToCache(context: Context, report: Report): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    // Exportações antigas não ficam a ocupar espaço.
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
    return File(dir, report.fileName).also { PdfWriter.write(report, it) }
}

/** Abre o menu de partilha do Android: o envio é sempre manual, escolhido pelo utilizador. */
fun sharePdf(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Partilhar PDF"))
}

/**
 * Diálogo de exportação: escolher o documento (quando há mais do que um) e partilhar ou guardar.
 * [build] cria o relatório a partir dos dados atuais.
 */
@Composable
fun ExportDialog(
    title: String,
    options: List<Pair<String, String>>,
    build: (optionIndex: Int) -> Report,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    extraContent: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    // Índice do documento a guardar; guardado em estado persistente porque o seletor de ficheiros
    // pode recriar a atividade. O relatório é reconstruído a partir dos dados atuais quando volta.
    var pendingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val index = pendingIndex ?: return@rememberLauncherForActivityResult
        pendingIndex = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val report = build(index)
            context.contentResolver.openOutputStream(uri)?.use { PdfWriter.write(report, it) } ?: error("destino indisponível")
        }.fold(
            onSuccess = { onMessage("PDF guardado"); onDismiss() },
            onFailure = { onMessage("Não foi possível guardar: ${it.message}") },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                extraContent()
                options.forEachIndexed { i, (label, description) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            onClick = {
                                runCatching { sharePdf(context, exportToCache(context, build(i)), label) }
                                    .onFailure { onMessage("Não foi possível criar o PDF: ${it.message}") }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Partilhar") }
                        OutlinedButton(
                            onClick = {
                                runCatching { build(i) }.onSuccess { r ->
                                    pendingIndex = i
                                    saveLauncher.launch(r.fileName)
                                }.onFailure { onMessage("Não foi possível criar o PDF: ${it.message}") }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Guardar no telemóvel") }
                    }
                }
                Text(NOT_AN_INVOICE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
