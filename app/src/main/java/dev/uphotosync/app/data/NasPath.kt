package dev.uphotosync.app.data

/** SMB paths inside a share: backslash-separated, no leading or trailing separator. */
object NasPath {
    private val separators = Regex("""[\\/]+""")

    fun clean(path: String): String =
        path.trim().split(separators).filter { it.isNotBlank() }.joinToString("\\")

    fun join(vararg parts: String): String = clean(parts.filter { it.isNotBlank() }.joinToString("\\"))

    fun parent(path: String): String = clean(path).substringBeforeLast('\\', "")

    fun name(path: String): String = clean(path).substringAfterLast('\\')

    /** Replaces characters SMB/NTFS won't accept in a file name. */
    fun safeName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trimEnd('.').ifBlank { "file" }
}
