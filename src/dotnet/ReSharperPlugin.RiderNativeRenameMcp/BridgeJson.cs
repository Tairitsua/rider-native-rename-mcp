#nullable enable

using System.IO;
using System.Text;
using System.Text.Json;

namespace ReSharperPlugin.RiderNativeRenameMcp;

internal static class BridgeJson
{
    private static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = null,
    };

    public static T Deserialize<T>(Stream stream)
    {
        using var reader = new StreamReader(stream, new UTF8Encoding(false), true, 1024, true);
        var json = reader.ReadToEnd();
        return JsonSerializer.Deserialize<T>(TrimUtf8Bom(json), Options)
               ?? throw new InvalidDataException($"Failed to deserialize bridge payload to {typeof(T).Name}.");
    }

    public static string Serialize<T>(T value)
    {
        return JsonSerializer.Serialize(value, Options);
    }

    private static string TrimUtf8Bom(string value)
    {
        return value.Length > 0 && value[0] == '\uFEFF'
            ? value.Substring(1)
            : value;
    }
}
