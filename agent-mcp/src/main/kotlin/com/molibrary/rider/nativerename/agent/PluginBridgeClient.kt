package com.molibrary.rider.nativerename.agent

import com.fasterxml.jackson.module.kotlin.readValue
import com.molibrary.rider.nativerename.bridge.BridgeFiles
import com.molibrary.rider.nativerename.bridge.DiscoveryRecord
import com.molibrary.rider.nativerename.bridge.HealthResponse
import com.molibrary.rider.nativerename.bridge.RenameCommand
import com.molibrary.rider.nativerename.bridge.RenameResponse
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale

class PluginBridgeClient {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()
    private val isWsl: Boolean = run {
        val osReleaseFile = Path.of("/proc/sys/kernel/osrelease")
        Files.exists(osReleaseFile) &&
            Files.readString(osReleaseFile).contains("microsoft", ignoreCase = true)
    }

    fun health(projectPath: String): Pair<DiscoveryRecord, HealthResponse> {
        val discovery = readDiscovery(projectPath)
        val response = execute(
            BridgeHttpRequest(
                url = discovery.serverUrl + "/health",
                method = "GET",
                authToken = discovery.authToken,
            )
        )

        if (response.statusCode != 200) {
            throw PluginBridgeException("Rider plugin health check failed: HTTP ${response.statusCode} ${response.body}")
        }

        return discovery to AgentJson.mapper.readValue(response.body)
    }

    fun rename(projectPath: String, command: RenameCommand): RenameResponse {
        val discovery = readDiscovery(projectPath)
        val requestBody = AgentJson.mapper.writeValueAsString(command)
        val response = execute(
            BridgeHttpRequest(
                url = discovery.serverUrl + "/rename",
                method = "POST",
                body = requestBody,
                authToken = discovery.authToken,
            )
        )

        if (response.statusCode != 200) {
            throw PluginBridgeException("Rider plugin rename failed: HTTP ${response.statusCode} ${response.body}")
        }

        return AgentJson.mapper.readValue(response.body)
    }

    private fun readDiscovery(projectPath: String): DiscoveryRecord {
        val discoveryFile = BridgeFiles.discoveryFile(projectPath)
        if (!Files.exists(discoveryFile)) {
            throw PluginBridgeException(
                "Discovery file not found at $discoveryFile. Open the target project in Rider with the plugin enabled first."
            )
        }

        return AgentJson.mapper.readValue(Files.readString(discoveryFile).trimUtf8Bom())
    }

    private fun execute(request: BridgeHttpRequest): BridgeHttpResponse {
        try {
            return executeWithHttpClient(request)
        } catch (exception: IOException) {
            if (!shouldUseCurlFallback(request.url)) {
                throw exception
            }

            return try {
                executeWithCurl(request)
            } catch (curlException: Exception) {
                throw PluginBridgeException(
                    "Bridge request failed through both Java HTTP and curl fallback. " +
                        "Java HTTP: ${exception.message ?: exception.javaClass.name}. " +
                        "curl: ${curlException.message ?: curlException.javaClass.name}"
                )
            }
        }
    }

    private fun executeWithHttpClient(request: BridgeHttpRequest): BridgeHttpResponse {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(request.url))
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("X-Rider-Native-Rename-Token", request.authToken)

        val httpRequest = when (request.method.uppercase(Locale.ROOT)) {
            "GET" -> builder.GET().build()
            "POST" -> builder.POST(
                HttpRequest.BodyPublishers.ofString(request.body ?: "", StandardCharsets.UTF_8)
            ).build()
            else -> throw PluginBridgeException("Unsupported bridge HTTP method: ${request.method}")
        }

        val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        return BridgeHttpResponse(response.statusCode(), response.body())
    }

    private fun executeWithCurl(request: BridgeHttpRequest): BridgeHttpResponse {
        val command = mutableListOf(
            resolveCurlExecutable(),
            "--silent",
            "--show-error",
            "--request", request.method,
            "--header", "Accept: application/json",
            "--header", "Content-Type: application/json",
            "--header", "X-Rider-Native-Rename-Token: ${request.authToken}",
            "--write-out", "\n__STATUS__:%{http_code}",
        )
        if (request.body != null) {
            command.add("--data-binary")
            command.add(request.body)
        }
        command.add(request.url)

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw PluginBridgeException("curl bridge request exited with code $exitCode: $output")
        }

        val marker = "\n__STATUS__:"
        val markerIndex = output.lastIndexOf(marker)
        if (markerIndex < 0) {
            throw PluginBridgeException("curl bridge request did not return an HTTP status marker.")
        }

        val statusCode = output.substring(markerIndex + marker.length).trim().toIntOrNull()
            ?: throw PluginBridgeException("curl bridge request returned a non-numeric status code.")

        return BridgeHttpResponse(
            statusCode = statusCode,
            body = output.substring(0, markerIndex),
        )
    }

    private fun shouldUseCurlFallback(url: String): Boolean {
        if (!isWsl) {
            return false
        }

        val host = URI.create(url).host?.lowercase(Locale.ROOT) ?: return false
        if (host != "127.0.0.1" && host != "localhost") {
            return false
        }

        return runCatching { commandAvailable(resolveCurlExecutable()) }.getOrDefault(false)
    }

    private fun resolveCurlExecutable(): String {
        val windowsCurl = Path.of("/mnt/c/Windows/System32/curl.exe")
        if (isWsl && Files.isRegularFile(windowsCurl)) {
            return windowsCurl.toString()
        }

        return "curl"
    }

    private fun commandAvailable(command: String): Boolean {
        val process = ProcessBuilder(command, "--version")
            .redirectErrorStream(true)
            .start()
        process.inputStream.close()
        return process.waitFor() == 0
    }
}

private fun String.trimUtf8Bom(): String =
    if (startsWith("\uFEFF")) substring(1) else this

class PluginBridgeException(message: String) : RuntimeException(message)

private data class BridgeHttpRequest(
    val url: String,
    val method: String,
    val body: String? = null,
    val authToken: String,
)

private data class BridgeHttpResponse(
    val statusCode: Int,
    val body: String,
)
