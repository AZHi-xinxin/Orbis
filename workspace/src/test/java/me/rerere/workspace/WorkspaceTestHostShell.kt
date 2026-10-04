package me.rerere.workspace

import java.io.File
import java.nio.file.Files

/**
 * The Android/Linux product runner intentionally uses /system/bin/sh or /bin/sh.
 * Windows JVM tests use a real Git-for-Windows POSIX shell instead; command
 * execution, working directory, stdin, timeout and output collection remain real.
 * No commands/results are mocked, and a missing shell fails rather than skips.
 */
internal fun workspaceTestHostShell(): WorkspaceShellRunner {
    if (File.separatorChar != '\\') return HostShellRunner()

    val configured = System.getenv("ORBIS_TEST_POSIX_SHELL")?.takeIf { it.isNotBlank() }
    val shell = if (configured != null) {
        File(configured).also {
            check(it.isAbsolute && it.isFile) {
                "ORBIS_TEST_POSIX_SHELL must name an existing absolute POSIX shell executable"
            }
        }
    } else {
        val pathDirectories = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { File(it.trim('"')) }
            .filter { it.isAbsolute }
        val candidates = pathDirectories.flatMap { directory ->
            listOf(
                File(directory, "sh.exe"),
                // Git commonly adds only <installation>/cmd to PATH.
                File(directory, "../usr/bin/sh.exe"),
                File(directory, "../bin/sh.exe"),
            )
        } + listOf(
            File("C:/Program Files/Git/usr/bin/sh.exe"),
            File("C:/Program Files (x86)/Git/usr/bin/sh.exe"),
        )
        checkNotNull(candidates.firstOrNull { it.isFile }) {
            "Windows workspace tests require Git sh; set ORBIS_TEST_POSIX_SHELL to its absolute path"
        }
    }.canonicalFile

    return object : WorkspaceShellRunner {
        override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
            // ProcessBuilder's Windows argv quoting and Git/MSYS sh -c do not
            // preserve embedded double quotes reliably. Pass unchanged command
            // bytes in a unique script instead of adding another escaping layer.
            // stdin stays available to the command, just as in the product runner.
            val script = Files.createTempFile(context.tempDir.toPath(), "host-command-", ".sh")
            try {
                script.toFile().writeText(context.command, Charsets.UTF_8)
                val builder = ProcessBuilder(shell.absolutePath, script.toFile().invariantSeparatorsPath)
                    .directory(context.workingDir)
                    .redirectErrorStream(false)
                builder.environment().apply {
                    // Keep the fixture independent of interactive startup files.
                    remove("BASH_ENV")
                    remove("ENV")
                    // Java's mutable Windows map may contain Path rather than
                    // PATH. Do not emit duplicate differently-cased keys to MSYS.
                    val inheritedPath = entries.firstOrNull { it.key?.equals("PATH", ignoreCase = true) == true }
                        ?.value.orEmpty()
                    keys.filter { it.equals("PATH", ignoreCase = true) }.forEach { remove(it) }
                    put("PATH", checkNotNull(shell.parent) + File.pathSeparator + inheritedPath)
                }
                return builder.start().readResult(context.timeoutMillis, context.stdin)
            } finally {
                Files.deleteIfExists(script)
            }
        }
    }
}
