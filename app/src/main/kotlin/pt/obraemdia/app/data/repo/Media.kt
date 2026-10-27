package pt.obraemdia.app.data.repo

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.AttachmentEntity
import pt.obraemdia.app.data.db.AttachmentKind
import pt.obraemdia.app.data.db.AuditEventEntity
import pt.obraemdia.app.data.db.ChecklistItemEntity
import pt.obraemdia.app.data.db.ChecklistKind
import pt.obraemdia.app.data.db.MediaCounts
import pt.obraemdia.app.data.db.NoteEntity
import pt.obraemdia.app.data.files.AttachmentStore
import java.time.LocalDate

/**
 * Fotografias, notas e checklist de uma obra. Não têm impacto financeiro, por isso podem
 * ser acrescentadas mesmo com a obra encerrada.
 */
class Media(
    private val db: AppDatabase,
    private val repo: ObraRepository,
    private val store: AttachmentStore,
    private val clock: () -> Long,
) {
    val counts: Flow<List<MediaCounts>> = db.mediaDao().observeCounts()

    fun attachments(jobId: String) = db.mediaDao().observeAttachments(jobId)
    fun notes(jobId: String) = db.mediaDao().observeNotes(jobId)
    fun checklist(jobId: String) = db.mediaDao().observeChecklist(jobId)

    /**
     * Copia o ficheiro para a pasta privada e só depois grava o registo. Se a gravação falhar,
     * o ficheiro copiado é apagado: nunca fica um registo a apontar para um ficheiro em falta.
     */
    suspend fun addAttachment(jobId: String?, uri: Uri, kind: AttachmentKind, caption: String?, takenOn: LocalDate, purchaseDocumentId: String? = null) {
        val stored = withContext(Dispatchers.IO) { store.import(uri) }
        try {
            repo.transaction {
                val id = newId()
                db.mediaDao().insertAttachment(
                    AttachmentEntity(
                        id = id, jobId = jobId, purchaseDocumentId = purchaseDocumentId, kind = kind, relativePath = stored.relativePath, mimeType = stored.mimeType,
                        sizeBytes = stored.sizeBytes, sha256 = stored.sha256, caption = caption?.takeIf { it.isNotBlank() },
                        takenOn = takenOn, createdAt = clock(),
                    ),
                )
                audit("attachment", id, "create", "$kind ${stored.sizeBytes / 1024} KB")
            }
        } catch (e: Exception) {
            store.delete(stored.relativePath)
            throw e
        }
    }

    suspend fun updateCaption(id: String, caption: String?, kind: AttachmentKind) = repo.transaction {
        val a = requireNotNull(db.mediaDao().attachment(id)) { "Fotografia não encontrada" }
        db.mediaDao().updateAttachment(a.copy(caption = caption?.takeIf { it.isNotBlank() }, kind = kind))
    }

    /** Fica no histórico; o ficheiro mantém-se até um eventual "limpar" futuro. */
    suspend fun removeAttachment(id: String) = repo.transaction {
        val a = requireNotNull(db.mediaDao().attachment(id)) { "Fotografia não encontrada" }
        db.mediaDao().updateAttachment(a.copy(deletedAt = clock()))
        audit("attachment", id, "delete", a.caption)
    }

    suspend fun addNote(jobId: String, text: String) {
        require(text.isNotBlank()) { "A nota está vazia" }
        repo.transaction {
            val now = clock()
            db.mediaDao().insertNote(NoteEntity(newId(), jobId, text.trim(), now, now))
        }
    }

    suspend fun updateNote(id: String, text: String) {
        require(text.isNotBlank()) { "A nota está vazia" }
        repo.transaction {
            val n = requireNotNull(db.mediaDao().note(id)) { "Nota não encontrada" }
            db.mediaDao().updateNote(n.copy(text = text.trim(), updatedAt = clock()))
            audit("note", id, "update", null)
        }
    }

    suspend fun removeNote(id: String) = repo.transaction {
        val n = requireNotNull(db.mediaDao().note(id)) { "Nota não encontrada" }
        db.mediaDao().updateNote(n.copy(deletedAt = clock()))
        audit("note", id, "delete", n.text.take(60))
    }

    suspend fun addItem(jobId: String, kind: ChecklistKind, text: String) {
        require(text.isNotBlank()) { "O item está vazio" }
        repo.transaction {
            val now = clock()
            db.mediaDao().insertItem(ChecklistItemEntity(newId(), jobId, kind, text.trim(), createdAt = now, updatedAt = now))
        }
    }

    suspend fun toggleItem(id: String) = repo.transaction {
        val i = requireNotNull(db.mediaDao().item(id)) { "Item não encontrado" }
        db.mediaDao().updateItem(i.copy(done = !i.done, updatedAt = clock()))
    }

    suspend fun removeItem(id: String) = repo.transaction {
        val i = requireNotNull(db.mediaDao().item(id)) { "Item não encontrado" }
        db.mediaDao().updateItem(i.copy(deletedAt = clock()))
    }

    fun thumbnail(relativePath: String, maxSide: Int) = store.thumbnail(relativePath, maxSide)
    fun file(relativePath: String) = store.file(relativePath)

    private suspend fun audit(entity: String, id: String, action: String, detail: String?) {
        db.auditDao().insert(AuditEventEntity(newId(), entity, id, action, detail, clock()))
    }
}
