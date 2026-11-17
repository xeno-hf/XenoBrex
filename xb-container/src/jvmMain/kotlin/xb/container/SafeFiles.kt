package xb.container

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/** 共用的限量讀取與原子存檔。 */
object SafeFiles {
    class SizeLimitException(maxBytes: Long) : IOException("File exceeds the $maxBytes byte limit")
    private val filePermissions = PosixFilePermissions.fromString("rw-------")
    private val dirPermissions = PosixFilePermissions.fromString("rwx------")
    private val reserved = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")

    fun requireSafeBasename(name: String): String {
        require(name.length in 1..180 && name[0] != '.' && name.last() != '.' &&
            name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._-" } &&
            !reserved.matches(name)) { "Unsafe filename" }
        return name
    }

    fun resolveBasename(dir: Path, name: String): Path {
        requireSafeBasename(name)
        val base = dir.toAbsolutePath().normalize()
        val target = base.resolve(name).normalize()
        require(target.parent == base && target.startsWith(base)) { "Filename escapes its directory" }
        return target
    }

    fun resolveMember(dir: Path, name: String): Path {
        ArchiveSafety.validateMemberName(name)
        require(!name.endsWith('/')) { "Not a file member" }
        val base = dir.toAbsolutePath().normalize()
        val target = base.resolve(name).normalize()
        require(target != base && target.startsWith(base)) { "Member escapes its directory" }
        target.parent?.let(::requireNoSymlinkParents)
        return target
    }

    fun boundedRead(input: InputStream, maxBytes: Long): ByteArray {
        require(maxBytes in 0..Int.MAX_VALUE.toLong())
        val out = ByteArrayOutputStream()
        copyBounded(input, out, maxBytes)
        return out.toByteArray()
    }

    fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        require(maxBytes >= 0)
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            val n = input.read(buffer, 0, (if (maxBytes - count >= buffer.size) buffer.size else (maxBytes - count + 1).toInt()))
            if (n < 0) return count
            if (n == 0) continue
            if (n > maxBytes - count) throw SizeLimitException(maxBytes)
            output.write(buffer, 0, n)
            count += n
        }
    }

    fun ensurePrivateDirectory(path: Path) {
        val dir = path.toAbsolutePath().normalize()
        requireNoSymlinkParents(dir)
        if (!Files.exists(dir, NOFOLLOW_LINKS)) {
            dir.parent?.let { parent ->
                if (!Files.exists(parent, NOFOLLOW_LINKS)) ensurePrivateDirectory(parent)
                if (untrustedLink(parent)) throw IOException("Refusing a symbolic-link directory")
            }
            val posix = Files.getFileAttributeView(dir.parent, PosixFileAttributeView::class.java) != null
            try {
                if (posix) Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(dirPermissions))
                else Files.createDirectory(dir)
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, NOFOLLOW_LINKS)) throw e
            }
        }
        if (!Files.isDirectory(dir, NOFOLLOW_LINKS)) throw IOException("Not a directory")
        privatePermissions(dir, directory = true)
    }

    private fun requireNoSymlinkParents(dir: Path) {
        var current: Path? = dir
        while (current != null) {
            if (untrustedLink(current)) throw IOException("Refusing a symbolic-link directory")
            current = current.parent
        }
    }

    /**
     * 拒絕使用者能動的符號連結。root 擁有的連結放行，當作管理者設好的系統路徑別名（macOS 的 /var、/tmp，
     * Fedora Atomic 的 /home），否則這些系統的暫存區與家目錄完全存不了檔。這是信任假設，不是保證：
     * 一般使用者建不出 root 擁有的連結，但這裡不檢查連結所在的資料夾能不能被改、也不追連結指向的路徑，
     * 指向的位置是否可信交給管理者。讀不到 uid 的檔案系統（Windows）照舊全擋。
     */
    private fun untrustedLink(path: Path): Boolean {
        if (!Files.isSymbolicLink(path)) return false
        val uid = try { Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) as? Int } catch (e: Exception) { null }
        return uid != 0
    }

    private fun privatePermissions(path: Path, directory: Boolean) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS) != null) {
            Files.setPosixFilePermissions(path, if (directory) dirPermissions else filePermissions)
            return
        }
        // Windows 優先限為擁有者 ACL；不支援 ACL 時退回舊介面。
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
        if (acl != null) {
            acl.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.owner)
                .setPermissions(AclEntryPermission.entries.toSet()).build())
            return
        }
        // 兩種介面都沒有：舊介面做不到就拒絕，不能默默當成已限制。
        val file = try {
            path.toFile()
        } catch (e: UnsupportedOperationException) {
            throw IOException("Cannot restrict access to this file on this file system; refusing to store a secret")
        }
        legacyOwnerOnly(file, directory)
    }

    /** 不支援 POSIX／ACL 的檔案系統：靠舊介面盡量只留給擁有者；任何一步失敗就丟 [IOException]。 */
    internal fun legacyOwnerOnly(file: File, directory: Boolean, restrict: (File, Boolean) -> Boolean = ::restrictToOwner): Unit {
        val ok = try { restrict(file, directory) } catch (e: UnsupportedOperationException) { false }
        if (!ok) throw IOException("Cannot restrict access to this file on this file system; refusing to store a secret")
    }

    private fun restrictToOwner(file: File, directory: Boolean): Boolean {
        var ok = file.setReadable(false, false)
        ok = file.setWritable(false, false) && ok
        ok = file.setExecutable(false, false) && ok
        ok = file.setReadable(true, true) && ok
        ok = file.setWritable(true, true) && ok
        if (directory) ok = file.setExecutable(true, true) && ok
        return ok
    }

    /** 暫存檔從建立時就只給自己讀寫；不降級成非原子覆蓋。 */
    fun atomicWrite(path: Path, secret: Boolean = false, validate: (Path) -> Unit = {}, write: (OutputStream) -> Unit) {
        val target = path.toAbsolutePath().normalize()
        val dir = target.parent ?: throw IOException("No parent directory")
        if (secret) {
            ensurePrivateDirectory(dir)
            if (Files.isSymbolicLink(target)) throw IOException("Refusing a symbolic-link secret file")
        } else {
            requireNoSymlinkParents(dir)
            Files.createDirectories(dir)
        }
        if (untrustedLink(dir)) throw IOException("Refusing a symbolic-link directory")
        val posix = Files.getFileAttributeView(dir, PosixFileAttributeView::class.java) != null
        val tmp = if (posix) Files.createTempFile(dir, ".xb-", ".tmp", PosixFilePermissions.asFileAttribute(filePermissions))
            else Files.createTempFile(dir, ".xb-", ".tmp")
        try {
            if (!posix) privatePermissions(tmp, directory = false)
            Files.newOutputStream(tmp, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use(write)
            validate(tmp)
            if (secret && Files.isSymbolicLink(target)) throw IOException("Refusing a symbolic-link secret file")
            Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(tmp) }
    }
}
