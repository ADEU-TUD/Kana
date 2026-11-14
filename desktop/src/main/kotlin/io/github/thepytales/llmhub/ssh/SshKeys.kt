package io.github.thepytales.llmhub.ssh

import io.github.thepytales.llmhub.ui.tr
import io.github.thepytales.llmhub.ui.trLater
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.keyprovider.KeyPairProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.PublicKey

/**
 * SSH keys of the Server-Terminal (AP-131 a): a new Ed25519 pair in `ssh/schluessel/` of the data folder – the private
 * key in OpenSSH format, with a passphrase when one is given, readable only by the own Windows/macOS/Linux account – and
 * the public key as one line for `authorized_keys`. Existing keys (OpenSSH, PuTTY `.ppk`) are used where they are.
 */
object SshKeys {
    /** A generated pair: where the private key is and the line for `authorized_keys`. */
    data class Created(val privateKey: File, val publicLine: String, val fingerprint: String)

    fun folder(sshDir: File) = File(sshDir, "schluessel")

    /** A file name from [name]: letters, digits, dot, dash and underscore; never an existing file. */
    fun fileFor(sshDir: File, name: String): File {
        val base = name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').ifBlank { "lora" }.take(40)
        var f = File(folder(sshDir), base)
        var n = 2
        while (f.exists() || File(f.path + ".pub").exists()) f = File(folder(sshDir), "$base-${n++}")
        return f
    }

    /**
     * Creates an Ed25519 key pair named [name]; [passphrase] protects the private key (recommended). The comment of the
     * public key is [comment] (e.g. „lora@PC-Name“) – it shows on the server whose key it is.
     */
    fun create(sshDir: File, name: String, passphrase: String?, comment: String): Created {
        val kp = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256)
        val file = fileFor(sshDir, name)
        file.parentFile.mkdirs()
        val ctx = passphrase?.takeIf { it.isNotEmpty() }?.let { p -> OpenSSHKeyEncryptionContext().apply { password = p; cipherName = "AES"; cipherMode = "CTR"; cipherType = "256" } }
        file.outputStream().use { OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(kp, comment, ctx, it) }
        ownerOnly(file)
        val line = publicLine(kp.public, comment)
        File(file.path + ".pub").writeText(line + "\n")
        return Created(file, line, KnownHosts.fingerprint(kp.public))
    }

    /** `ssh-ed25519 AAAA… comment` – the line that goes into `authorized_keys`. */
    fun publicLine(key: PublicKey, comment: String): String =
        PublicKeyEntry.toString(key) + comment.trim().takeIf { it.isNotEmpty() }?.let { " " + it.replace(Regex("\\s+"), "_") }.orEmpty()

    /**
     * The public key for [keyFile]: from `<keyFile>.pub` when it is there, otherwise read from the private key (OpenSSH or
     * PuTTY; [passphrase] for a protected one). Throws [SecretNeeded] when the passphrase is missing or wrong.
     */
    fun publicLineOf(keyFile: File, passphrase: String?, comment: String = ""): String {
        File(keyFile.path + ".pub").takeIf { it.isFile }?.readText()?.trim()?.lineSequence()?.firstOrNull { it.startsWith("ssh-") || it.startsWith("ecdsa-") }?.let { return it }
        val kp = SshConnection.loadKeys(keyFile, passphrase).first()
        return publicLine(kp.public, comment)
    }

    /**
     * The command that puts [publicLine] into `~/.ssh/authorized_keys` – only once, with the rights OpenSSH demands
     * (folder 700, file 600). Runs as a step after the user's click like every other command.
     */
    fun installCommand(publicLine: String): String {
        val q = SshConnection.quote(publicLine.trim())
        return "umask 077 && mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys && " +
            "(grep -qxF $q ~/.ssh/authorized_keys || echo $q >> ~/.ssh/authorized_keys)"
    }

    /** The step for [installCommand] with its explanation. */
    fun installStep(publicLine: String): ServerStep {
        val cmd = installCommand(publicLine)
        return ServerStep(
            cmd,
            tr("Legt deinen öffentlichen Schlüssel auf den Server (~/.ssh/authorized_keys), damit du dich künftig ohne Passwort anmeldest."),
            tr(INSTALL_SAFETY),
            Risk.CHANGE,
        )
    }

    private val INSTALL_SAFETY = trLater("Nur der öffentliche Schlüssel geht auf den Server, der private bleibt auf diesem Gerät. Steht er schon dort, ändert sich nichts.")

    /** Only the own account may read the private key (POSIX 600; on Windows an ACL for the owner only). */
    fun ownerOnly(file: File) {
        val path = file.toPath()
        runCatching {
            if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
                Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            } else {
                val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
                val owner = Files.getOwner(path)
                val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(AclEntryPermission.values().toSet()).build()
                view.acl = listOf(entry)
            }
        }
    }
}
