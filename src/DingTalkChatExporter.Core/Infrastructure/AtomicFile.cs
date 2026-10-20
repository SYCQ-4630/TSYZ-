using System.Text;

namespace DingTalkChatExporter.Core.Infrastructure;

/// <summary>
/// Writes through a same-directory temp file and replaces the destination to avoid
/// half-written configuration files after a crash or forced termination.
/// </summary>
public static class AtomicFile
{
    private static readonly UTF8Encoding Utf8NoBom = new(encoderShouldEmitUTF8Identifier: false);

    public static void WriteAllText(string path, string content)
    {
        var directory = Path.GetDirectoryName(Path.GetFullPath(path));
        if (string.IsNullOrWhiteSpace(directory))
        {
            throw new ArgumentException("Path must include a directory.", nameof(path));
        }

        Directory.CreateDirectory(directory);
        var tempPath = Path.Combine(directory, $".{Path.GetFileName(path)}.{Guid.NewGuid():N}.tmp");
        try
        {
            File.WriteAllText(tempPath, content, Utf8NoBom);
            Replace(tempPath, path);
        }
        finally
        {
            if (File.Exists(tempPath))
            {
                try
                {
                    File.Delete(tempPath);
                }
                catch (IOException)
                {
                }
            }
        }
    }

    public static void WriteAllBytes(string path, byte[] bytes)
    {
        var directory = Path.GetDirectoryName(Path.GetFullPath(path));
        if (string.IsNullOrWhiteSpace(directory))
        {
            throw new ArgumentException("Path must include a directory.", nameof(path));
        }

        Directory.CreateDirectory(directory);
        var tempPath = Path.Combine(directory, $".{Path.GetFileName(path)}.{Guid.NewGuid():N}.tmp");
        try
        {
            File.WriteAllBytes(tempPath, bytes);
            Replace(tempPath, path);
        }
        finally
        {
            if (File.Exists(tempPath))
            {
                try
                {
                    File.Delete(tempPath);
                }
                catch (IOException)
                {
                }
            }
        }
    }

    private static void Replace(string tempPath, string destination)
    {
        if (!File.Exists(destination))
        {
            File.Move(tempPath, destination);
            return;
        }

        try
        {
            File.Replace(tempPath, destination, destinationBackupFileName: null, ignoreMetadataErrors: true);
        }
        catch (Exception ex) when (ex is PlatformNotSupportedException or IOException or UnauthorizedAccessException)
        {
            File.Move(tempPath, destination, overwrite: true);
        }
    }
}
