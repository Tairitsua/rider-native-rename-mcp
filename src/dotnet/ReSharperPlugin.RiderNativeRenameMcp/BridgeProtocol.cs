#nullable enable

namespace ReSharperPlugin.RiderNativeRenameMcp;

public sealed class DiscoveryRecord
{
    public int protocolVersion { get; set; } = 1;

    public string serverUrl { get; set; } = string.Empty;

    public string authToken { get; set; } = string.Empty;

    public string projectPath { get; set; } = string.Empty;

    public string projectName { get; set; } = string.Empty;

    public string writtenAtUtc { get; set; } = string.Empty;
}

public sealed class HealthResponse
{
    public bool ok { get; set; }

    public string projectName { get; set; } = string.Empty;

    public string projectPath { get; set; } = string.Empty;

    public string pluginVersion { get; set; } = string.Empty;

    public string message { get; set; } = string.Empty;
}

public sealed class RenameCommand
{
    public string filePath { get; set; } = string.Empty;

    public int line { get; set; }

    public int column { get; set; }

    public string newName { get; set; } = string.Empty;

    public bool searchInComments { get; set; }

    public bool searchTextOccurrences { get; set; }
}

public sealed class RenameResponse
{
    public bool ok { get; set; }

    public string message { get; set; } = string.Empty;

    public string projectPath { get; set; } = string.Empty;

    public string? filePath { get; set; }

    public int? line { get; set; }

    public int? column { get; set; }
}

public sealed class ErrorResponse
{
    public bool ok { get; set; }

    public string message { get; set; } = string.Empty;
}
