package com.molibrary.rider.nativerename.bridge

import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

object BridgeFiles {
    const val DiscoveryDirectoryName: String = ".idea"
    const val DiscoveryFileName: String = "rider-native-rename-mcp.json"

    fun discoveryFile(projectPath: String): Path =
        Paths.get(PathMapper.toLocalPath(projectPath))
            .resolve(DiscoveryDirectoryName)
            .resolve(DiscoveryFileName)
}

data class DiscoveryRecord(
    val protocolVersion: Int = 1,
    val serverUrl: String,
    val authToken: String,
    val projectPath: String,
    val projectName: String,
    val writtenAtUtc: String,
)

data class HealthResponse(
    val ok: Boolean,
    val projectName: String,
    val projectPath: String,
    val pluginVersion: String,
    val message: String,
)

data class RenameCommand(
    val filePath: String,
    val line: Int,
    val column: Int,
    val newName: String,
    val searchInComments: Boolean = false,
    val searchTextOccurrences: Boolean = false,
)

data class RenameResponse(
    val ok: Boolean,
    val message: String,
    val projectPath: String,
    val filePath: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

object PathMapper {
    private val windowsDrivePattern = Regex("^([A-Za-z]):[\\\\/](.*)$")
    private val wslDrivePattern = Regex("^/mnt/([A-Za-z])/(.*)$")

    fun toSystemIndependent(path: String): String = path.replace('\\', '/')

    fun toWindowsPath(path: String): String {
        val normalized = toSystemIndependent(path)
        val wslMatch = wslDrivePattern.matchEntire(normalized) ?: return normalized
        val drive = wslMatch.groupValues[1].uppercase(Locale.ROOT)
        val tail = wslMatch.groupValues[2]
        return "$drive:/$tail"
    }

    fun toWslPath(path: String): String {
        val normalized = toSystemIndependent(path)
        val windowsMatch = windowsDrivePattern.matchEntire(normalized) ?: return normalized
        val drive = windowsMatch.groupValues[1].lowercase(Locale.ROOT)
        val tail = windowsMatch.groupValues[2]
        return "/mnt/$drive/$tail"
    }

    fun toIdePath(path: String): String = toSystemIndependent(toWindowsPath(path))

    fun toLocalPath(path: String): String {
        val normalized = if (isWindows()) toWindowsPath(path) else toWslPath(path)
        return if (isWindows()) normalized.replace('/', '\\') else normalized
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
}
