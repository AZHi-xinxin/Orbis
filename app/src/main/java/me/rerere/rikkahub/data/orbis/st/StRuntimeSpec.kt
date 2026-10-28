package me.rerere.rikkahub.data.orbis.st

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class StRuntimeSpec(val source: String, val python: String, val config: String) {
    fun validate() {
        listOf(source, python, config).forEach {
            require(it.length in 2..400 && it.startsWith('/') &&
                it.split('/').drop(1).all { part -> part.isNotEmpty() && part != "." && part != ".." } &&
                it.none { c -> c.code < 32 || c == '\u007f' }) { "st_path_invalid" }
        }
        require(source.startsWith("/workspace/") || source.startsWith("/root/")) { "st_source_outside_workspace" }
        require(config.startsWith("/root/") || config.startsWith("/workspace/")) { "st_config_outside_workspace" }
        require(python.startsWith("/opt/") || python.startsWith("/usr/") || python.startsWith("/root/") || python.startsWith("/workspace/")) { "st_python_path_invalid" }
        require(config != source && !config.startsWith("$source/")) { "st_config_inside_source" }
    }
}

/** No shell evaluation. Credentials are read by the installed ST inside its own rootfs. */
internal fun stRuntimeCommand(native: File, base: File, wrapper: File, spec: StRuntimeSpec): List<String> {
    spec.validate()
    return buildList {
        addAll(listOf(File(native, "libproot_exec.so").path, "--root-id", "--link2symlink", "--kill-on-exit",
            "-r", File(base, "linux").path, "-w", spec.source,
            "-b", "${File(base, "files").path}:/workspace", "-b", "${wrapper.path}:/orbis-st-host.py"))
        listOf("/dev", "/proc", "/sys").forEach { addAll(listOf("-b", it)) }
        addAll(listOf("/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8", "PYTHONNOUSERSITE=1", "PYTHONDONTWRITEBYTECODE=1", "PYTHONUNBUFFERED=1",
            spec.python, "-B", "/orbis-st-host.py", spec.source, spec.config))
    }
}
