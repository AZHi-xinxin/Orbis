package me.rerere.rikkahub.data.orbis.ombre

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class OmbreRuntimeSpec(val source: String, val python: String, val config: String) {
    fun validate() {
        listOf(source, python, config).forEach {
            require(it.length in 2..400 && it.startsWith('/') &&
                it.split('/').drop(1).all { part -> part.isNotEmpty() && part != "." && part != ".." } &&
                it.none { c -> c.code < 32 || c == '\u007f' }) { "ombre_path_invalid" }
        }
        require(source.startsWith("/workspace/") || source.startsWith("/root/")) { "ombre_source_outside_workspace" }
        require(config.startsWith("/root/") || config.startsWith("/workspace/")) { "ombre_config_outside_workspace" }
        require(python.startsWith("/opt/") || python.startsWith("/usr/") || python.startsWith("/root/") || python.startsWith("/workspace/")) { "ombre_python_path_invalid" }
        require(config != source && !config.startsWith("$source/")) { "ombre_config_inside_source" }
    }
}

/** No shell evaluation. Credentials are read by the installed OmbreBrain inside its own rootfs. */
internal fun ombreRuntimeCommand(native: File, base: File, wrapper: File, spec: OmbreRuntimeSpec): List<String> {
    spec.validate()
    return buildList {
        addAll(listOf(File(native, "libproot_exec.so").path, "--root-id", "--link2symlink", "--kill-on-exit",
            "-r", File(base, "linux").path, "-w", spec.source,
            "-b", "${File(base, "files").path}:/workspace", "-b", "${wrapper.path}:/orbis-ombre-host.py"))
        listOf("/dev", "/proc", "/sys").forEach { addAll(listOf("-b", it)) }
        addAll(listOf("/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8", "PYTHONNOUSERSITE=1", "PYTHONDONTWRITEBYTECODE=1", "PYTHONUNBUFFERED=1",
            spec.python, "-B", "/orbis-ombre-host.py", spec.source, spec.config))
    }
}
