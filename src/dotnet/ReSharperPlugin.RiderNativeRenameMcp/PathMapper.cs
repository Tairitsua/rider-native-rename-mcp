#nullable enable

using System;
using System.IO;
using System.Text.RegularExpressions;

namespace ReSharperPlugin.RiderNativeRenameMcp;

internal static class PathMapper
{
    private static readonly Regex WindowsDrivePattern = new("^([A-Za-z]):[\\\\/](.*)$", RegexOptions.Compiled);
    private static readonly Regex WslDrivePattern = new("^/mnt/([A-Za-z])/(.*)$", RegexOptions.Compiled);

    public static string ToSystemIndependent(string path) => path.Replace('\\', '/');

    public static string ToWindowsPath(string path)
    {
        var normalized = ToSystemIndependent(path);
        var match = WslDrivePattern.Match(normalized);
        if (!match.Success)
        {
            return normalized;
        }

        var drive = match.Groups[1].Value.ToUpperInvariant();
        var tail = match.Groups[2].Value;
        return $"{drive}:/{tail}";
    }

    public static string ToWslPath(string path)
    {
        var normalized = ToSystemIndependent(path);
        var match = WindowsDrivePattern.Match(normalized);
        if (!match.Success)
        {
            return normalized;
        }

        var drive = match.Groups[1].Value.ToLowerInvariant();
        var tail = match.Groups[2].Value;
        return $"/mnt/{drive}/{tail}";
    }

    public static string ToLocalPath(string path)
    {
        var normalized = Environment.OSVersion.Platform == PlatformID.Win32NT
            ? ToWindowsPath(path)
            : ToWslPath(path);

        return Environment.OSVersion.Platform == PlatformID.Win32NT
            ? normalized.Replace('/', Path.DirectorySeparatorChar)
            : normalized;
    }
}
