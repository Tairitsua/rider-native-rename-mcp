# Rider Native Rename MCP

Expose JetBrains Rider's native rename refactoring to MCP-compatible AI agents.

This project connects an MCP client to Rider's built-in rename workflow, so an agent can request a real IDE rename instead of doing a plain text replacement. The result is closer to what a developer would do manually in Rider: symbol-aware updates, cross-file edits, and file renames when Rider decides they are part of the refactoring.

## Highlights

- Uses Rider's native rename pipeline instead of a custom text-based rename flow
- Supports classes, interfaces, and methods
- Updates references across files through Rider
- Can rename the corresponding `.cs` file when Rider treats it as part of the rename
- Works with both Windows paths and WSL paths
- Keeps everything local through a loopback bridge on `127.0.0.1`

## How It Works

The system has two runtime pieces:

1. A Rider plugin that starts a local HTTP bridge inside the Rider/ReSharper backend.
2. A stdio MCP companion process that an MCP host can launch.

When Rider opens a solution or project with the plugin enabled, the plugin writes a discovery file:

```text
.idea/rider-native-rename-mcp.json
```

That file contains the local bridge address and a per-project auth token. The MCP companion reads it, connects to the bridge, and forwards tool calls into Rider.

## Repository Layout

- `src/rider`
  Rider frontend plugin packaging
- `src/dotnet`
  ReSharper/Rider backend bridge implementation
- `bridge-contract`
  Shared DTOs and path helpers for the plugin and MCP companion
- `agent-mcp`
  The stdio MCP server

## Requirements

- Rider 2025.3 or newer
- JDK 21 to build the IntelliJ/Rider plugin and MCP companion
- .NET SDK to build the Rider backend plugin project

## Build

```bash
cd /path/to/RiderNativeRenameMcp
./gradlew buildPlugin
./gradlew :agent-mcp:installDist
```

Build outputs:

- Rider plugin zip:
  `output/ReSharperPlugin.RiderNativeRenameMcp-9999.0.0.zip`
- MCP launcher on Linux or WSL:
  `agent-mcp/build/install/rider-native-rename-mcp/bin/rider-native-rename-mcp`
- MCP launcher on Windows:
  `agent-mcp/build/install/rider-native-rename-mcp/bin/rider-native-rename-mcp.bat`

## Install The Rider Plugin

1. Build the plugin zip with `./gradlew buildPlugin`.
2. In Rider, open `Settings` -> `Plugins`.
3. Click the gear icon -> `Install Plugin from Disk...`.
4. Select `output/ReSharperPlugin.RiderNativeRenameMcp-9999.0.0.zip`.
5. Restart Rider.
6. Open the target solution or project in Rider.

After Rider finishes loading the target project, the discovery file should appear under `.idea/`.

## Configure An MCP Client

Any MCP-compatible client can launch the stdio companion. Below are example commands for Codex because it is a common MCP host, but the server itself is not tied to Codex.

### Option 1: `codex mcp add`

WSL or Linux:

```bash
codex mcp add rider-native-rename -- \
  /path/to/RiderNativeRenameMcp/agent-mcp/build/install/rider-native-rename-mcp/bin/rider-native-rename-mcp
```

Windows:

```powershell
codex mcp add rider-native-rename -- `
  D:\path\to\RiderNativeRenameMcp\agent-mcp\build\install\rider-native-rename-mcp\bin\rider-native-rename-mcp.bat
```

### Option 2: `config.toml`

WSL or Linux:

```toml
[mcp_servers.rider-native-rename]
command = "/path/to/RiderNativeRenameMcp/agent-mcp/build/install/rider-native-rename-mcp/bin/rider-native-rename-mcp"
```

Windows:

```toml
[mcp_servers.rider-native-rename]
command = "D:\\path\\to\\RiderNativeRenameMcp\\agent-mcp\\build\\install\\rider-native-rename-mcp\\bin\\rider-native-rename-mcp.bat"
```

Useful Codex commands:

```bash
codex mcp list
codex mcp get rider-native-rename
```

## MCP Tools

### `rider_native_status`

Checks whether the Rider bridge is available for a given project.

Arguments:

- `project_path`

### `rider_native_rename`

Invokes Rider's native rename refactoring at a file location.

Required arguments:

- `project_path`
- `file_path`
- `line`
- `column`
- `new_name`

Optional arguments:

- `search_in_comments`
- `search_text_occurrences`

## Example Request

Example prompt shape for an MCP host:

```text
Use rider_native_rename with:
project_path=/path/to/YourSolution
file_path=/path/to/YourSolution/src/App/Services/IOrderService.cs
line=8
column=18
new_name=IOrderQueryService
```

## Security Model

- The bridge listens only on `127.0.0.1`
- Every opened project gets its own auth token
- The token is stored in `.idea/rider-native-rename-mcp.json`
- The MCP companion uses that token on every bridge request

## Behavior Notes

- Rename runs through Rider, not through a separate Roslyn-only implementation
- If Rider decides a preview or conflict dialog is required, Rider may still show UI
- Path input can be Windows style or WSL style
- Updating the plugin or MCP companion is safest with a Rider restart so the bridge and discovery file are refreshed

## Troubleshooting

If `rider_native_status` fails:

1. Make sure Rider is running and the target solution is open.
2. Confirm `.idea/rider-native-rename-mcp.json` exists in the target project.
3. Restart Rider after plugin updates.
4. Reinstall the plugin zip if Rider is still loading an older build.

If rename requests fail:

1. Verify the `file_path`, `line`, and `column` point at the target symbol.
2. Check whether Rider is showing a preview or conflict UI.
3. Confirm the target symbol type is currently supported.

## Current Scope

This project currently focuses on Rider native rename for:

- Classes
- Interfaces
- Methods

The design leaves room to add more Rider-native refactorings later.
