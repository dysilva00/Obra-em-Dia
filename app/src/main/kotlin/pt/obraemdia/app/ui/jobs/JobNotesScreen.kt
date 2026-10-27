package pt.obraemdia.app.ui.jobs

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.obraemdia.app.data.db.AttachmentEntity
import pt.obraemdia.app.data.db.AttachmentKind
import pt.obraemdia.app.data.db.ChecklistItemEntity
import pt.obraemdia.app.data.db.ChecklistKind
import pt.obraemdia.app.data.db.NoteEntity
import pt.obraemdia.app.data.repo.Media
import pt.obraemdia.app.ui.components.SectionCard
import pt.obraemdia.app.ui.shortLabel
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

val AttachmentKind.label: String
    get() = when (this) {
        AttachmentKind.BEFORE -> "Antes"
        AttachmentKind.DURING -> "Durante"
        AttachmentKind.AFTER -> "Depois"
        AttachmentKind.DOCUMENT -> "Documento"
    }

private val photoKinds = listOf(AttachmentKind.BEFORE, AttachmentKind.DURING, AttachmentKind.AFTER)

/** Notas, fotografias antes/durante/depois, tarefas e lista de compras de uma obra. */
@Composable
fun JobNotesScreen(media: Media, jobId: String, today: LocalDate, onMessage: (String) -> Unit) {
    val photos by remember(jobId) { media.attachments(jobId) }.collectAsState(emptyList())
    val notes by remember(jobId) { media.notes(jobId) }.collectAsState(emptyList())
    val items by remember(jobId) { media.checklist(jobId) }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val photoActions = rememberPhotoActions(media, jobId, today, onMessage)

    fun run(block: suspend () -> Unit, ok: String? = null) = scope.launch {
        runCatching { block() }.fold(onSuccess = { ok?.let(onMessage) }, onFailure = { onMessage("Não foi possível gravar: ${it.message}") })
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PhotosSection(media, photos, photoActions, ::run)
        ChecklistSection("Tarefas", ChecklistKind.TASK, items, onAdd = { run({ media.addItem(jobId, ChecklistKind.TASK, it) }) }, onToggle = { run({ media.toggleItem(it) }) }, onRemove = { run({ media.removeItem(it) }) })
        ChecklistSection("Lista de compras", ChecklistKind.SHOPPING, items, onAdd = { run({ media.addItem(jobId, ChecklistKind.SHOPPING, it) }) }, onToggle = { run({ media.toggleItem(it) }) }, onRemove = { run({ media.removeItem(it) }) })
        NotesSection(notes, onAdd = { run({ media.addNote(jobId, it) }, "Nota gravada") }, onUpdate = { id, t -> run({ media.updateNote(id, t) }, "Nota corrigida") }, onRemove = { run({ media.removeNote(it) }, "Nota removida") })
    }
}

/** Tirar foto (app de câmara do sistema, sem pedir permissão) ou escolher da galeria. */
private class PhotoActions(val take: (AttachmentKind) -> Unit, val pick: (AttachmentKind) -> Unit)

@Composable
private fun rememberPhotoActions(media: Media, jobId: String, today: LocalDate, onMessage: (String) -> Unit): PhotoActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kindName by rememberSaveable { mutableStateOf(AttachmentKind.DURING.name) }
    var pendingCamera by rememberSaveable { mutableStateOf<String?>(null) }

    fun import(uri: Uri, afterwards: () -> Unit = {}) = scope.launch {
        runCatching { media.addAttachment(jobId, uri, AttachmentKind.valueOf(kindName), null, today) }
            .fold(onSuccess = { onMessage("Fotografia guardada") }, onFailure = { onMessage("Não foi possível guardar: ${it.message}") })
        afterwards()
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingCamera ?: return@rememberLauncherForActivityResult
        pendingCamera = null
        val file = File(path)
        if (ok && file.length() > 0) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            import(uri) { file.delete() }
        } else {
            file.delete()
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) import(uri)
    }
    val take: (AttachmentKind) -> Unit = { kind ->
        kindName = kind.name
        val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "foto-${System.currentTimeMillis()}.jpg")
        pendingCamera = file.path
        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
    }
    val pick: (AttachmentKind) -> Unit = { kind ->
        kindName = kind.name
        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    return PhotoActions(take, pick)
}

@Composable
private fun PhotosSection(media: Media, photos: List<AttachmentEntity>, actions: PhotoActions, run: (suspend () -> Unit, String?) -> Unit) {
    var filter by rememberSaveable { mutableStateOf<AttachmentKind?>(null) }
    var viewing by remember { mutableStateOf<AttachmentEntity?>(null) }
    val shown = photos.filter { it.kind != AttachmentKind.DOCUMENT && (filter == null || it.kind == filter) }
    val newKind = filter ?: AttachmentKind.DURING

    SectionCard("Fotografias", caption = "${photos.size} · guardadas só neste telemóvel (incluídas no backup)") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == null, { filter = null }, { Text("Todas") }, Modifier.heightIn(min = 48.dp))
            photoKinds.forEach { k -> FilterChip(filter == k, { filter = k }, { Text(k.label) }, Modifier.heightIn(min = 48.dp)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { actions.take(newKind) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.PhotoCamera, null)
                Text("  Tirar foto (${newKind.label.lowercase()})")
            }
            FilledTonalButton(onClick = { actions.pick(newKind) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.PhotoLibrary, null)
                Text("  Da galeria")
            }
        }
        if (shown.isEmpty()) Text("Sem fotografias${filter?.let { " \"${it.label}\"" } ?: ""}.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { p -> Thumb(media, p) { viewing = p } }
        }
    }
    viewing?.let { p ->
        PhotoDialog(
            media, p,
            onDismiss = { viewing = null },
            onSave = { caption, kind -> run({ media.updateCaption(p.id, caption, kind) }, "Fotografia atualizada"); viewing = null },
            onRemove = { run({ media.removeAttachment(p.id) }, "Fotografia removida"); viewing = null },
        )
    }
}

@Composable
private fun decoded(media: Media, path: String, maxSide: Int): Bitmap? =
    produceState<Bitmap?>(null, path, maxSide) { value = withContext(Dispatchers.IO) { media.thumbnail(path, maxSide) } }.value

@Composable
private fun Thumb(media: Media, p: AttachmentEntity, onClick: () -> Unit) {
    val bmp = decoded(media, p.relativePath, 320)
    Column(Modifier.clickable(onClick = onClick).size(104.dp, 132.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(104.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = p.caption ?: "Fotografia ${p.kind.label}", contentScale = ContentScale.Crop, modifier = Modifier.size(104.dp))
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(104.dp)) {}
            }
        }
        Text("${p.kind.label} · ${p.takenOn.shortLabel()}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PhotoDialog(media: Media, p: AttachmentEntity, onDismiss: () -> Unit, onSave: (String?, AttachmentKind) -> Unit, onRemove: () -> Unit) {
    var caption by rememberSaveable(p.id) { mutableStateOf(p.caption.orEmpty()) }
    var kind by rememberSaveable(p.id) { mutableStateOf(p.kind) }
    var confirmRemove by remember { mutableStateOf(false) }
    val bmp = decoded(media, p.relativePath, 1600)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${p.kind.label} · ${p.takenOn.shortLabel()}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bmp?.let { Image(it.asImageBitmap(), contentDescription = p.caption, modifier = Modifier.fillMaxWidth()) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    photoKinds.forEach { k -> FilterChip(kind == k, { kind = k }, { Text(k.label) }, Modifier.heightIn(min = 48.dp)) }
                }
                OutlinedTextField(caption, { caption = it }, label = { Text("Legenda") }, modifier = Modifier.fillMaxWidth())
                if (confirmRemove) {
                    Text("Remover esta fotografia? Fica no histórico.", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRemove) { Text("Sim, remover", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { confirmRemove = true }) { Text("Remover fotografia…", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(caption, kind) }) { Text("Gravar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
private fun ChecklistSection(
    title: String,
    kind: ChecklistKind,
    all: List<ChecklistItemEntity>,
    onAdd: (String) -> Unit,
    onToggle: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val items = all.filter { it.kind == kind }
    var text by rememberSaveable(kind) { mutableStateOf("") }
    SectionCard(title, caption = "${items.count { !it.done }} por fazer · ${items.count { it.done }} feito(s)") {
        items.forEach { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).toggleable(i.done, role = Role.Checkbox) { onToggle(i.id) },
                ) {
                    Checkbox(i.done, onCheckedChange = null)
                    Text(
                        "  ${i.text}",
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (i.done) TextDecoration.LineThrough else null,
                        color = if (i.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { onRemove(i.id) }) { Icon(Icons.Filled.Close, contentDescription = "Remover ${i.text}") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, label = { Text(if (kind == ChecklistKind.TASK) "Nova tarefa" else "Material a comprar") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(enabled = text.isNotBlank(), onClick = { onAdd(text); text = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Adicionar") }
        }
    }
}

@Composable
private fun NotesSection(notes: List<NoteEntity>, onAdd: (String) -> Unit, onUpdate: (String, String) -> Unit, onRemove: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<NoteEntity?>(null) }
    SectionCard("Notas", caption = "Toque numa nota para a corrigir") {
        OutlinedTextField(text, { text = it }, label = { Text("Nova nota") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Button(enabled = text.isNotBlank(), onClick = { onAdd(text); text = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Gravar nota") }
        notes.forEach { n ->
            Column(Modifier.fillMaxWidth().clickable { editing = n }.padding(vertical = 6.dp)) {
                Text(n.text, style = MaterialTheme.typography.bodyLarge)
                val day = java.time.Instant.ofEpochMilli(n.createdAt).atZone(ZoneId.of("Europe/Lisbon")).toLocalDate()
                Text(day.shortLabel() + if (n.updatedAt != n.createdAt) " · corrigida" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    editing?.let { n ->
        var edit by rememberSaveable(n.id) { mutableStateOf(n.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Nota") },
            text = { OutlinedTextField(edit, { edit = it }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = edit.isNotBlank(), onClick = { onUpdate(n.id, edit); editing = null }) { Text("Gravar") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { onRemove(n.id); editing = null }) { Text("Remover", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { editing = null }) { Text("Cancelar") }
                }
            },
        )
    }
}
