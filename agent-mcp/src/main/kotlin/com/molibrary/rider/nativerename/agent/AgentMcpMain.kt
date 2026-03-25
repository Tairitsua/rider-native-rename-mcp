package com.molibrary.rider.nativerename.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.molibrary.rider.nativerename.bridge.RenameCommand
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

fun main() {
    val server = AgentMcpServer(
        reader = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8)),
        writer = BufferedWriter(OutputStreamWriter(System.out, StandardCharsets.UTF_8)),
        bridgeClient = PluginBridgeClient(),
    )
    server.run()
}

private class AgentMcpServer(
    private val reader: BufferedReader,
    private val writer: BufferedWriter,
    private val bridgeClient: PluginBridgeClient,
) {
    private var shutdownRequested: Boolean = false
    private var shouldExit: Boolean = false

    fun run() {
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isBlank()) {
                continue
            }

            val request = try {
                AgentJson.mapper.readTree(line)
            } catch (_: Exception) {
                write(mapOf("jsonrpc" to "2.0", "id" to null, "error" to error(-32700, "Invalid JSON payload.")))
                continue
            }

            dispatch(request)
            if (shouldExit) {
                return
            }
        }
    }

    private fun dispatch(request: JsonNode) {
        val method = request["method"]?.asText()
        val id = request["id"]

        when (method) {
            "initialize" -> reply(
                id,
                mapOf(
                    "protocolVersion" to "2025-11-25",
                    "capabilities" to mapOf("tools" to emptyMap<String, Any>()),
                    "serverInfo" to mapOf(
                        "name" to "rider-native-rename-mcp",
                        "version" to "0.1.0",
                    ),
                    "instructions" to "Use rider_native_status before rename if you need to confirm the Rider bridge is active.",
                ),
            )

            "ping" -> reply(id, emptyMap<String, Any>())
            "shutdown" -> {
                shutdownRequested = true
                reply(id, emptyMap<String, Any>())
            }
            "exit" -> {
                shouldExit = true
                return
            }
            "notifications/initialized" -> return
            "notifications/cancelled" -> return
            "tools/list" -> reply(id, mapOf("tools" to toolDefinitions()))
            "tools/call" -> handleToolCall(id, request["params"] ?: AgentJson.mapper.createObjectNode())
            else -> {
                if (id != null && !id.isNull) {
                    write(mapOf("jsonrpc" to "2.0", "id" to id, "error" to error(-32601, "Unknown method: $method")))
                }
            }
        }
    }

    private fun handleToolCall(id: JsonNode?, params: JsonNode) {
        val toolName = params["name"]?.asText()
        val arguments = params["arguments"] as? ObjectNode ?: AgentJson.mapper.createObjectNode()

        val result = try {
            when (toolName) {
                "rider_native_status" -> statusResult(arguments)
                "rider_native_rename" -> renameResult(arguments)
                else -> error("Unknown tool: $toolName")
            }
        } catch (exception: Exception) {
            mapOf(
                "content" to listOf(
                    mapOf(
                        "type" to "text",
                        "text" to (exception.message ?: exception.javaClass.name),
                    )
                ),
                "isError" to true,
            )
        }

        reply(id, result)
    }

    private fun statusResult(arguments: ObjectNode): Map<String, Any> {
        val projectPath = requiredText(arguments, "project_path")
        val (discovery, health) = bridgeClient.health(projectPath)
        return mapOf(
            "content" to listOf(
                mapOf(
                    "type" to "text",
                    "text" to "Rider bridge is reachable for ${health.projectName} at ${discovery.serverUrl}.",
                )
            ),
            "structuredContent" to mapOf(
                "discovery" to discovery,
                "health" to health,
            ),
            "isError" to false,
        )
    }

    private fun renameResult(arguments: ObjectNode): Map<String, Any> {
        val projectPath = requiredText(arguments, "project_path")
        val rename = RenameCommand(
            filePath = requiredText(arguments, "file_path"),
            line = requiredInt(arguments, "line"),
            column = requiredInt(arguments, "column"),
            newName = requiredText(arguments, "new_name"),
            searchInComments = arguments["search_in_comments"]?.asBoolean(false) ?: false,
            searchTextOccurrences = arguments["search_text_occurrences"]?.asBoolean(false) ?: false,
        )

        val response = bridgeClient.rename(projectPath, rename)
        return mapOf(
            "content" to listOf(mapOf("type" to "text", "text" to response.message)),
            "structuredContent" to response,
            "isError" to !response.ok,
        )
    }

    private fun toolDefinitions(): List<Map<String, Any>> = listOf(
        mapOf(
            "name" to "rider_native_status",
            "description" to "Check whether the Rider bridge plugin is running for a project.",
            "inputSchema" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "project_path" to mapOf(
                        "type" to "string",
                        "description" to "Project root path. WSL and Windows paths are both accepted.",
                    )
                ),
                "required" to listOf("project_path"),
            ),
        ),
        mapOf(
            "name" to "rider_native_rename",
            "description" to "Invoke Rider's native rename refactoring at a file location.",
            "inputSchema" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "project_path" to mapOf(
                        "type" to "string",
                        "description" to "Project root path. WSL and Windows paths are both accepted.",
                    ),
                    "file_path" to mapOf(
                        "type" to "string",
                        "description" to "Target source file path. WSL and Windows paths are both accepted.",
                    ),
                    "line" to mapOf(
                        "type" to "integer",
                        "description" to "1-based line number where the symbol appears.",
                    ),
                    "column" to mapOf(
                        "type" to "integer",
                        "description" to "1-based column number where the symbol appears.",
                    ),
                    "new_name" to mapOf(
                        "type" to "string",
                        "description" to "Replacement identifier.",
                    ),
                    "search_in_comments" to mapOf(
                        "type" to "boolean",
                        "description" to "Whether comment occurrences should also be renamed.",
                    ),
                    "search_text_occurrences" to mapOf(
                        "type" to "boolean",
                        "description" to "Whether plain text occurrences should also be renamed.",
                    ),
                ),
                "required" to listOf("project_path", "file_path", "line", "column", "new_name"),
            ),
        ),
    )

    private fun requiredText(arguments: ObjectNode, name: String): String =
        arguments[name]?.asText()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing required string argument: $name")

    private fun requiredInt(arguments: ObjectNode, name: String): Int =
        arguments[name]?.takeIf { it.canConvertToInt() }?.asInt()
            ?: throw IllegalArgumentException("Missing required integer argument: $name")

    private fun reply(id: JsonNode?, result: Any) {
        write(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "result" to result,
            )
        )
    }

    private fun error(code: Int, message: String): Map<String, Any> =
        mapOf("code" to code, "message" to message)

    private fun write(payload: Map<String, Any?>) {
        writer.write(AgentJson.mapper.writeValueAsString(payload))
        writer.newLine()
        writer.flush()
    }
}
