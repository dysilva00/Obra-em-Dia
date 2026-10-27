package pt.obraemdia.app.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Ficheiro já copiado para a pasta privada da app. */
data class StoredFile(val relativePath: String, val mimeType: String, val sizeBytes: Long, val sha256: String)

class NotEnoughSpaceException : IllegalStateException("Pouco espaço livre no telemóvel para guardar o ficheiro")

/**
 * Anexos em filesDir/attachments. A importação copia sempre o conteúdo (um URI de galeria ou de
 * câmara é temporário) e só devolve o caminho relativo depois de o ficheiro estar completo no disco.
 */
class AttachmentStore(private val context: Context) {

    private val root: File get() = context.filesDir
    private val dir: File get() = File(root, DIR).apply { mkdirs() }

    fun file(relativePath: String): File = File(root, relativePath)

    /**
     * Copia [uri] para a pasta privada. Fotografias são reduzidas (lado maior ≤ [MAX_SIDE]) e a
     * orientação EXIF é aplicada; PDFs e outros ficheiros são copiados tal como estão.
     */
    fun import(uri: Uri): StoredFile {
        if (dir.usableSpace < MIN_FREE_BYTES) throw NotEnoughSpaceException()
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val isImage = mime.startsWith("image/")
        val name = UUID.randomUUID().toString() + if (isImage) ".jpg" else extension(mime)
        val tmp = File(dir, ".tmp-$name")
        try {
            if (isImage) writeCompressedImage(uri, tmp) else copy(uri, tmp)
            val target = File(dir, name)
            check(tmp.renameTo(target)) { "Não foi possível guardar o ficheiro" }
            return StoredFile("$DIR/$name", if (isImage) "image/jpeg" else mime, target.length(), sha256(target))
        } finally {
            tmp.delete()
        }
    }

    /** Apaga um ficheiro importado (ex.: a gravação na base falhou depois da cópia). */
    fun delete(relativePath: String) {
        file(relativePath).delete()
    }

    /** Miniatura para listas, sem carregar a imagem inteira em memória. */
    fun thumbnail(relativePath: String, maxSide: Int): Bitmap? {
        val f = file(relativePath)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        return BitmapFactory.decodeFile(f.path, opts)
    }

    private fun copy(uri: Uri, to: File) {
        val input = context.contentResolver.openInputStream(uri) ?: error("Não foi possível ler o ficheiro")
        input.use { i -> to.outputStream().use { o -> i.copyTo(o) } }
    }

    private fun writeCompressedImage(uri: Uri, to: File) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Com inJustDecodeBounds o decodeStream devolve sempre null; só interessam as dimensões.
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Não foi possível ler a imagem")
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE) }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: error("Imagem inválida")

        // Fotografias tiradas ao alto vêm com a rotação só no EXIF: aplicá-la antes de reescrever.
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull()
        val degrees = when (rotation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        // Reduzir ainda mais se o lado maior continuar acima do limite.
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        to.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) { "Não foi possível comprimir a imagem" } }
        bitmap.recycle()
    }

    companion object {
        const val DIR = "attachments"
        private const val MAX_SIDE = 2048 // legível para documentos e fotografias de obra
        private const val JPEG_QUALITY = 85
        private const val MIN_FREE_BYTES = 20L * 1024 * 1024

        fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
            var sample = 1
            while (maxOf(w, h) / (sample * 2) >= maxSide) sample *= 2
            return sample
        }

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun extension(mime: String) = when (mime) {
            "application/pdf" -> ".pdf"
            else -> ""
        }
    }
}
