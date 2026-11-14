package io.github.thepytales.llmhub.ssh

import io.github.thepytales.llmhub.ui.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Files of the server over SFTP (AP-131 b) – what WinSCP did: list a folder, up- and download with progress and
 * *Abbrechen*, rename, delete (the screen asks first), make a folder, open a text file and save it back. Uses the SSH
 * connection of the session (no second login). Every change is written to the protocol of the session.
 */
class ServerFiles(conn: SshConnection, private val log: (String) -> Unit = {}) : Closeable {
    private val sftp: SftpClient = SftpClientFactory.instance().createSftpClient(conn.session)

    val isOpen: Boolean get() = sftp.isOpen

    /** One entry of a folder. [permissions] like `rwxr-xr-x`, [modified] in milliseconds. */
    data class Entry(val name: String, val path: String, val isDir: Boolean, val isLink: Boolean, val size: Long, val permissions: String, val modified: Long)

    /** A running up- or download: [done] of [total] bytes. */
    data class Transfer(val name: String, val upload: Boolean, val done: Long, val total: Long) {
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    class Cancelled : Exception(tr("Abgebrochen"))

    private val _transfer = MutableStateFlow<Transfer?>(null)
    val transfer: StateFlow<Transfer?> = _transfer
    private val cancel = AtomicBoolean(false)

    /** The home folder of the user on the server (absolute). */
    fun home(): String = sftp.canonicalPath(".")

    /** [path] made absolute: `~` and `~/…` start at [home]; `..` is resolved by the server. */
    fun resolve(path: String): String {
        val p = path.trim().ifEmpty { "." }
        val withHome = when {
            p == "~" -> home()
            p.startsWith("~/") -> home().trimEnd('/') + "/" + p.removePrefix("~/")
            else -> p
        }
        return sftp.canonicalPath(withHome)
    }

    /** The folder [dir]: folders first, then files, each alphabetically; without „.“ and „..“. */
    fun list(dir: String): List<Entry> {
        val base = dir.trimEnd('/').ifEmpty { "/" }
        return sftp.readDir(base).filter { it.filename != "." && it.filename != ".." }.map { e ->
            val a = e.attributes
            Entry(e.filename, child(base, e.filename), a.isDirectory, a.isSymbolicLink, a.size, permissions(a.permissions), a.modifyTime?.toMillis() ?: 0L)
        }.sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() }))
    }

    /** Downloads [remote] into [target] (a file); [Cancelled] when the user stopped it – the half file is removed. */
    fun download(remote: String, target: File) {
        val total = runCatching { sftp.stat(remote).size }.getOrDefault(-1L)
        target.parentFile?.mkdirs()
        val ok = runCatching { sftp.read(remote).use { input -> target.outputStream().use { out -> copy(input, out, Transfer(name(remote), false, 0, total)) } } }
        ok.exceptionOrNull()?.let { target.delete(); throw it }
        log("Heruntergeladen: $remote → ${target.name}")
    }

    /** Uploads [file] into the folder [dir]; an existing file of the same name is replaced only when [replace]. */
    fun upload(file: File, dir: String, replace: Boolean = false): String {
        val remote = child(dir, file.name)
        if (!replace && exists(remote)) throw java.nio.file.FileAlreadyExistsException(tr("„{0}“ gibt es dort schon.", file.name))
        val ok = runCatching { sftp.write(remote).use { out -> file.inputStream().use { input -> copy(input, out, Transfer(file.name, true, 0, file.length())) } } }
        ok.exceptionOrNull()?.let { e -> if (e is Cancelled) runCatching { sftp.remove(remote) }; throw e }
        log("Hochgeladen: ${file.name} → $remote")
        return remote
    }

    fun exists(remote: String): Boolean = runCatching { sftp.stat(remote) }.isSuccess

    fun rename(remote: String, newName: String): String {
        val n = newName.trim()
        require(n.isNotEmpty() && '/' !in n && n != "." && n != "..") { tr("Ungültiger Name") }
        val target = child(parent(remote), n)
        if (exists(target)) throw java.nio.file.FileAlreadyExistsException(tr("„{0}“ gibt es dort schon.", n))
        sftp.rename(remote, target)
        log("Umbenannt: $remote → $target")
        return target
    }

    /** How many files and folders [delete] would remove (for the question before). */
    fun countInside(entry: Entry, limit: Int = 10_000): Int {
        if (!entry.isDir || entry.isLink) return 1
        var n = 1
        for (e in list(entry.path)) { n += countInside(e, limit - n); if (n >= limit) break }
        return n
    }

    /** Deletes a file, or a folder with everything inside – only after the question on the screen. */
    fun delete(entry: Entry) {
        if (entry.isDir && !entry.isLink) {
            list(entry.path).forEach { delete(it) }
            sftp.rmdir(entry.path)
        } else sftp.remove(entry.path)
        log("Gelöscht: ${entry.path}")
    }

    fun mkdir(dir: String, name: String): String {
        val n = name.trim()
        require(n.isNotEmpty() && '/' !in n) { tr("Ungültiger Name") }
        val p = child(dir, n)
        sftp.mkdir(p)
        log("Ordner angelegt: $p")
        return p
    }

    /** A text file for the editor – null when it is too big or looks binary. */
    fun readText(remote: String, max: Long = MAX_TEXT): String? {
        val size = sftp.stat(remote).size
        if (size > max) return null
        val bytes = sftp.read(remote).use { it.readNBytes(max.toInt() + 1) }
        if (bytes.size > max || bytes.take(8000).any { it == 0.toByte() }) return null
        return bytes.toString(Charsets.UTF_8)
    }

    /**
     * Saves [text] back to [remote]. The file is overwritten in place, so owner, group and rights on the server stay as
     * they were (a new file and a rename would lose them).
     */
    fun writeText(remote: String, text: String) {
        sftp.write(remote).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        log("Gespeichert: $remote")
    }

    /** Stops the running transfer. */
    fun cancelTransfer() = cancel.set(true)

    private fun copy(input: InputStream, out: OutputStream, start: Transfer) {
        cancel.set(false)
        _transfer.value = start
        try {
            val buf = ByteArray(64 * 1024)
            var done = 0L
            while (true) {
                if (cancel.get()) throw Cancelled()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                _transfer.update { it?.copy(done = done) }
            }
        } finally {
            _transfer.value = null
        }
    }

    override fun close() = sftp.close()

    companion object {
        const val MAX_TEXT = 2L * 1024 * 1024

        fun child(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"

        fun parent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

        fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')

        /** `rwxr-xr-x` from the mode bits. */
        fun permissions(mode: Int): String = buildString {
            val bits = intArrayOf(0x100, 0x80, 0x40, 0x20, 0x10, 0x8, 0x4, 0x2, 0x1)
            val chars = "rwxrwxrwx"
            bits.forEachIndexed { i, b -> append(if (mode and b != 0) chars[i] else '-') }
        }

        /** Size for people: 512 B, 1,2 KB, 3,4 MB. */
        fun size(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(java.util.Locale.GERMANY, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.GERMANY, "%.1f MB", bytes / 1024.0 / 1024)
            else -> String.format(java.util.Locale.GERMANY, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
        }
    }
}
