using System.Security.Cryptography;
using System.Text;

namespace DingTalkChatExporter.Core.Services;

/// <summary>
/// Fast source fingerprint (path, length, last write) used to detect that an Excel
/// file changed after an index was built. Content hashing is deliberately avoided
/// because large workbooks are re-read only on explicit rebuilds.
/// </summary>
public static class IndexStateHasher
{
    public static string? TryCompute(string filePath)
    {
        try
        {
            var info = new FileInfo(filePath);
            if (!info.Exists)
            {
                return null;
            }

            var raw = string.Join(
                "|",
                info.FullName.ToUpperInvariant(),
                info.Length.ToString(System.Globalization.CultureInfo.InvariantCulture),
                info.LastWriteTimeUtc.Ticks.ToString(System.Globalization.CultureInfo.InvariantCulture));
            var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(raw));
            return Convert.ToHexString(bytes);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }
}
