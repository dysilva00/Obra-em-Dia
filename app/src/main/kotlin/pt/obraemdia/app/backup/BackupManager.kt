package pt.obraemdia.app.backup

import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.SCHEMA_VERSION
import pt.obraemdia.app.data.files.AttachmentStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Formato do ficheiro de backup; muda se a estrutura do ZIP mudar. */
const val BACKUP_FORMAT = 1

@Serializable
data class BackupFile(val path: String, val size: Long, val sha256: String)

@Serializable
data class BackupManifest(
    val format: Int,
    val schemaVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val counts: Map<String, Long>,
    val files: List<BackupFile>,
)

/** Onde estão os dados vivos e onde se pode trabalhar (tudo dentro da pasta privada da app). */
data class BackupPaths(val databaseFile: File, val filesDir: File, val workDir: File) {
    val attachmentsDir get() = File(filesDir, AttachmentStore.DIR)
    val journal get() = File(workDir, "restore-journal")
    val rollbackDir get() = File(workDir, "rollback")
    val pending get() = File(workDir, "restore-pending")
    val result get() = File(workDir, "restore-result")
}

class BackupException(message: String) : Exception(message)

/** Pronto a aplicar: já validado (integridade, checksums, anexos, esquema). */
data class StagedRestore(val dir: File, val manifest: BackupManifest)

/**
 * Backup completo (base + anexos + manifesto) num ZIP, e restauro seguro.
 * Spec §16: snapshot consistente, validação antes de tocar nos dados, troca com recuperação.
 */
class BackupManager(private val paths: BackupPaths, private val openDatabase: (File) -> AppDatabase) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    // ---------------------------------------------------------------- exportar

    /**
     * Cria o backup e escreve-o em [out]. O ZIP é primeiro criado e verificado na área de trabalho;
     * só depois é copiado para o destino escolhido.
     */
    suspend fun export(db: AppDatabase, appVersion: String, now: Long, out: OutputStream): BackupManifest {
        val stage = File(paths.workDir, "export").apply { deleteRecursively(); mkdirs() }
        try {
            val dbCopy = File(stage, DB_ENTRY.substringAfter('/'))
            val attachmentPaths = mutableListOf<String>()
            // Com a ligação de escrita reservada, ninguém grava enquanto a cópia é tirada.
            db.useWriterConnection { transactor ->
                transactor.usePrepared("VACUUM INTO ?") { it.bindText(1, dbCopy.absolutePath); it.step() }
                transactor.usePrepared("SELECT relativePath FROM attachments") { st ->
                    while (st.step()) attachmentPaths += st.getText(0)
                }
            }
            val zip = File(stage, "backup.zip")
            val files = mutableListOf<BackupFile>()
            ZipOutputStream(zip.outputStream().buffered()).use { z ->
                files += putFile(z, DB_ENTRY, dbCopy)
                attachmentPaths.distinct().sorted().forEach { rel ->
                    val f = File(paths.filesDir, rel)
                    // Um anexo em falta no disco não impede o backup, mas fica registado na contagem.
                    if (f.isFile) files += putFile(z, rel, f)
                }
                val manifest = BackupManifest(BACKUP_FORMAT, SCHEMA_VERSION, appVersion, now, counts(dbCopy) + ("missingAttachments" to (attachmentPaths.distinct().size - (files.size - 1)).toLong()), files)
                z.putNextEntry(ZipEntry(MANIFEST))
                z.write(json.encodeToString(manifest).toByteArray())
                z.closeEntry()
            }
            // Verificar o próprio ficheiro antes de o entregar.
            val manifest = verifyZip(zip)
            zip.inputStream().use { it.copyTo(out) }
            out.flush()
            return manifest
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun putFile(z: ZipOutputStream, name: String, f: File): BackupFile {
        z.putNextEntry(ZipEntry(name))
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                z.write(buf, 0, n)
            }
        }
        z.closeEntry()
        return BackupFile(name, f.length(), md.digest().hex())
    }

    private fun verifyZip(zip: File): BackupManifest = ZipFile(zip).use { zf ->
        val manifest = zf.getEntry(MANIFEST)?.let { e -> zf.getInputStream(e).use { json.decodeFromString<BackupManifest>(it.readBytes().decodeToString()) } }
            ?: throw BackupException("O backup não tem manifesto.")
        manifest.files.forEach { f ->
            val e = zf.getEntry(f.path) ?: throw BackupException("Falta o ficheiro ${f.path} no backup.")
            val sha = zf.getInputStream(e).use { sha256(it) }
            if (sha != f.sha256) throw BackupException("O ficheiro ${f.path} está corrompido.")
        }
        manifest
    }

    // ---------------------------------------------------------------- restaurar

    /**
     * Copia, extrai e valida um backup numa área separada. Não toca nos dados atuais.
     * Rejeita pacotes malformados, caminhos fora da pasta, tamanhos abusivos, checksums errados,
     * esquemas mais recentes que a app e bases com erros de integridade.
     */
    fun prepare(input: InputStream): StagedRestore {
        val stage = File(paths.workDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            val zip = File(stage, "in.zip")
            zip.outputStream().use { out -> copyLimited(input, out, MAX_ZIP_BYTES) }
            val out = File(stage, "data").apply { mkdirs() }
            val root = out.canonicalPath + File.separator
            var total = 0L
            ZipFile(zip).use { zf ->
                val entries = zf.entries().toList()
                if (entries.size > MAX_ENTRIES) throw BackupException("O ficheiro tem demasiadas entradas para ser um backup desta app.")
                entries.forEach { e ->
                    if (e.isDirectory) return@forEach
                    val target = File(out, e.name)
                    // Proteção "zip slip": nada pode ser escrito fora da pasta de trabalho.
                    if (!target.canonicalPath.startsWith(root)) throw BackupException("O ficheiro contém caminhos inválidos.")
                    target.parentFile?.mkdirs()
                    zf.getInputStream(e).use { inp -> target.outputStream().use { o -> total += copyLimited(inp, o, MAX_UNZIPPED_BYTES - total) } }
                }
            }
            zip.delete()

            val manifestFile = File(out, MANIFEST)
            if (!manifestFile.isFile) throw BackupException("Este ficheiro não é um backup da Obra em Dia.")
            val manifest = runCatching { json.decodeFromString<BackupManifest>(manifestFile.readText()) }
                .getOrElse { throw BackupException("O manifesto do backup está ilegível.") }
            if (manifest.format > BACKUP_FORMAT) throw BackupException("Backup de uma versão mais recente da app. Atualize a app antes de restaurar.")
            if (manifest.schemaVersion > SCHEMA_VERSION) throw BackupException("Backup de uma versão mais recente da app (esquema ${manifest.schemaVersion}). Atualize a app antes de restaurar.")
            if (manifest.files.none { it.path == DB_ENTRY }) throw BackupException("O backup não contém a base de dados.")
            manifest.files.forEach { f ->
                val file = File(out, f.path)
                if (!file.isFile) throw BackupException("Falta o ficheiro ${f.path} no backup.")
                if (file.length() != f.size || file.inputStream().use(::sha256) != f.sha256) throw BackupException("O ficheiro ${f.path} está corrompido.")
            }
            checkDatabase(File(out, DB_ENTRY))
            // Os dados restaurados vêm de uma cópia: essa é a última cópia conhecida.
            BundledSQLiteDriver().open(File(out, DB_ENTRY).absolutePath).use { c ->
                c.prepare("INSERT OR REPLACE INTO settings(`key`, value) VALUES (?, ?)").use {
                    it.bindText(1, LAST_BACKUP_KEY); it.bindText(2, manifest.createdAt.toString()); it.step()
                }
            }
            return StagedRestore(out, manifest)
        } catch (e: Exception) {
            stage.deleteRecursively()
            throw e as? BackupException ?: BackupException("Não foi possível ler o backup: ${e.message}")
        }
    }

    /** Integridade e chaves estrangeiras; abrir com o Room aplica as migrações suportadas. */
    private fun checkDatabase(file: File) {
        BundledSQLiteDriver().open(file.absolutePath).use { c ->
            val integrity = c.single("PRAGMA integrity_check")
            if (integrity != "ok") throw BackupException("A base de dados do backup está danificada ($integrity).")
        }
        val db = openDatabase(file)
        try {
            kotlinx.coroutines.runBlocking {
                db.useWriterConnection { t ->
                    t.usePrepared("PRAGMA foreign_key_check") { if (it.step()) throw BackupException("A base de dados do backup tem relações inválidas.") }
                }
            }
        } finally {
            db.close()
        }
        // O Room pode ter deixado ficheiros -wal/-shm: integrar e limpar.
        BundledSQLiteDriver().open(file.absolutePath).use { it.single("PRAGMA wal_checkpoint(TRUNCATE)") }
        listOf("-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
    }

    /**
     * Troca os dados atuais pelos do backup. Antes de chamar, a base atual tem de estar FECHADA.
     * O journal regista a fase: "out" (a mover os dados atuais para rollback) e "in" (a pôr os
     * restaurados no lugar). Se o processo morrer a meio, [recoverIfNeeded] repõe os dados atuais.
     */
    fun apply(staged: StagedRestore) {
        val rollback = paths.rollbackDir.apply { deleteRecursively(); mkdirs() }
        paths.journal.writeText(PHASE_OUT)
        dbFiles(paths.databaseFile).filter { it.exists() }.forEach { move(it, File(rollback, it.name)) }
        if (paths.attachmentsDir.exists()) move(paths.attachmentsDir, File(rollback, AttachmentStore.DIR))

        paths.journal.writeText(PHASE_IN)
        paths.databaseFile.parentFile?.mkdirs()
        move(File(staged.dir, DB_ENTRY), paths.databaseFile)
        val stagedAttachments = File(staged.dir, AttachmentStore.DIR)
        if (stagedAttachments.exists()) move(stagedAttachments, paths.attachmentsDir) else paths.attachmentsDir.mkdirs()

        paths.journal.delete()
        // O estado anterior fica em rollback até ao próximo restauro, como rede de segurança.
        staged.dir.parentFile?.deleteRecursively()
    }

    /**
     * Marca o restauro preparado para ser aplicado no próximo arranque, antes de a base abrir.
     * Assim nunca se fecha a base debaixo de ecrãs e tarefas que a estão a usar.
     */
    fun schedule(staged: StagedRestore) {
        check(File(staged.dir, DB_ENTRY).isFile) { "Restauro não preparado" }
        paths.pending.writeText(staged.dir.absolutePath)
    }

    /**
     * Chamar no arranque, ANTES de abrir a base: desfaz um restauro interrompido e aplica um
     * restauro pendente. O resultado fica para [consumeResult] mostrar ao utilizador.
     */
    fun onStartup() {
        if (recoverIfNeeded()) paths.result.writeText("O restauro foi interrompido. Os dados anteriores foram mantidos.")
        if (!paths.pending.exists()) return
        val dir = File(paths.pending.readText())
        // Apagar o pedido primeiro: se o processo morrer durante a troca, não se repete às cegas.
        paths.pending.delete()
        val message = try {
            val manifest = json.decodeFromString<BackupManifest>(File(dir, MANIFEST).readText())
            apply(StagedRestore(dir, manifest))
            "Dados restaurados da cópia de ${java.time.Instant.ofEpochMilli(manifest.createdAt).atZone(pt.obraemdia.domain.schedule.AGENDA_ZONE).toLocalDate().let { "${it.dayOfMonth}/${it.monthValue}/${it.year}" }}."
        } catch (e: Exception) {
            recoverIfNeeded()
            "Não foi possível restaurar: ${e.message}. Os dados anteriores foram mantidos."
        }
        paths.result.writeText(message)
    }

    /** Desiste de um restauro preparado e não confirmado. */
    fun discard() {
        File(paths.workDir, "restore").deleteRecursively()
    }

    /** Mensagem do último restauro (uma só vez). */
    fun consumeResult(): String? = paths.result.takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }

    /** Desfaz um restauro interrompido. */
    fun recoverIfNeeded(): Boolean {
        if (!paths.journal.exists()) return false
        val rollback = paths.rollbackDir
        if (paths.journal.readText() == PHASE_IN) {
            // Tudo o que está no lugar veio do backup (incompleto): sai, e os originais voltam.
            dbFiles(paths.databaseFile).forEach { it.delete() }
            paths.attachmentsDir.deleteRecursively()
        }
        // Na fase "out" só alguns originais tinham saído; os que ficaram no lugar não se tocam.
        dbFiles(paths.databaseFile).forEach { f -> File(rollback, f.name).takeIf { it.exists() }?.let { move(it, f) } }
        File(rollback, AttachmentStore.DIR).takeIf { it.exists() }?.let { move(it, paths.attachmentsDir) }
        paths.journal.delete()
        File(paths.workDir, "restore").deleteRecursively()
        return true
    }

    // ---------------------------------------------------------------- utilitários

    private fun counts(dbFile: File): Map<String, Long> = BundledSQLiteDriver().open(dbFile.absolutePath).use { c ->
        mapOf(
            "obras" to "SELECT COUNT(*) FROM jobs WHERE deletedAt IS NULL",
            "horas" to "SELECT COUNT(*) FROM time_entries WHERE deletedAt IS NULL",
            "movimentos" to "SELECT COUNT(*) FROM cash_movements",
            "compras" to "SELECT COUNT(*) FROM purchase_documents WHERE deletedAt IS NULL",
            "faturasEmitidas" to "SELECT COUNT(*) FROM issued_invoices WHERE deletedAt IS NULL",
            "anexos" to "SELECT COUNT(*) FROM attachments WHERE deletedAt IS NULL",
            "demonstracao" to "SELECT COUNT(*) FROM jobs WHERE isDemo = 1",
        ).mapValues { (_, sql) -> c.single(sql).toLong() }
    }

    private fun SQLiteConnection.single(sql: String): String = prepare(sql).use { st -> if (st.step()) st.getText(0) else "" }

    private fun dbFiles(db: File) = listOf(db, File(db.path + "-wal"), File(db.path + "-shm"), File(db.path + "-journal"))

    /** Mover dentro do mesmo armazenamento (rename); se falhar, copiar e apagar. */
    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (to.exists()) to.deleteRecursively()
        if (from.renameTo(to)) return
        if (from.isDirectory) from.copyRecursively(to, overwrite = true) else from.copyTo(to, overwrite = true)
        from.deleteRecursively()
    }

    private fun copyLimited(input: InputStream, out: OutputStream, limit: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw BackupException("O ficheiro é demasiado grande para ser um backup válido.")
            out.write(buf, 0, n)
        }
        return total
    }

    private fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val DB_ENTRY = "db/obra-em-dia.db"
        const val MANIFEST = "manifest.json"
        /** Igual a Settings.LAST_BACKUP_AT (repetido para não depender da camada de repositório). */
        private const val LAST_BACKUP_KEY = "last_backup_at"
        private const val PHASE_OUT = "out"
        private const val PHASE_IN = "in"
        private const val MAX_ENTRIES = 50_000
        private const val MAX_ZIP_BYTES = 4L * 1024 * 1024 * 1024
        private const val MAX_UNZIPPED_BYTES = 8L * 1024 * 1024 * 1024
    }
}
