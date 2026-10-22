using System.Text.Json;
using System.Text.Json.Serialization;

namespace TicketTool.Core.Data;

/// <summary>应用统一目录与序列化设置。</summary>
public sealed class AppPaths
{
    /// <summary>创建默认路径；可通过环境变量 TICKETTOOL_DATA_DIR 覆盖，便于测试与绿色部署。</summary>
    public AppPaths(string? rootOverride = null)
    {
        var root = rootOverride;
        if (string.IsNullOrWhiteSpace(root))
        {
            root = Environment.GetEnvironmentVariable("TICKETTOOL_DATA_DIR");
        }

        if (string.IsNullOrWhiteSpace(root))
        {
            root = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "TicketTool");
        }

        Root = Path.GetFullPath(root);
        DatabasePath = Path.Combine(Root, "ticket.db");
        LogsDirectory = Path.Combine(Root, "logs");
        BackupsDirectory = Path.Combine(Root, "backups");
        TempDirectory = Path.Combine(Root, "temp");
        ConfigDirectory = Path.Combine(Root, "config");
        DiagnosticsDirectory = Path.Combine(Root, "diagnostics");
        EnsureDirectories();
    }

    /// <summary>数据根目录。</summary>
    public string Root { get; }

    /// <summary>SQLite 数据库路径。</summary>
    public string DatabasePath { get; }

    /// <summary>日志目录。</summary>
    public string LogsDirectory { get; }

    /// <summary>备份目录。</summary>
    public string BackupsDirectory { get; }

    /// <summary>临时文件目录。</summary>
    public string TempDirectory { get; }

    /// <summary>配置目录。</summary>
    public string ConfigDirectory { get; }

    /// <summary>诊断包输出目录。</summary>
    public string DiagnosticsDirectory { get; }

    /// <summary>确保所有目录存在。</summary>
    public void EnsureDirectories()
    {
        Directory.CreateDirectory(Root);
        Directory.CreateDirectory(LogsDirectory);
        Directory.CreateDirectory(BackupsDirectory);
        Directory.CreateDirectory(TempDirectory);
        Directory.CreateDirectory(ConfigDirectory);
        Directory.CreateDirectory(DiagnosticsDirectory);
    }

    /// <summary>为临时文件生成位于应用临时目录内的绝对路径。</summary>
    public string NewTempFile(string extension)
        => Path.Combine(TempDirectory, $"{Guid.NewGuid():N}{extension}");
}

/// <summary>全局 JSON 序列化设置。</summary>
public static class JsonDefaults
{
    /// <summary>通用设置。</summary>
    public static JsonSerializerOptions Options { get; } = Create();

    private static JsonSerializerOptions Create()
    {
        var options = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = true,
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
            WriteIndented = false
        };
        options.Converters.Add(new JsonStringEnumConverter());
        return options;
    }
}

/// <summary>Zstd 压缩与解压的单一实现。</summary>
public static class CompressionService
{
    /// <summary>压缩文本。</summary>
    public static byte[] CompressText(string text)
        => CompressBytes(System.Text.Encoding.UTF8.GetBytes(text));

    /// <summary>解压文本。</summary>
    public static string DecompressText(byte[] payload)
        => System.Text.Encoding.UTF8.GetString(DecompressBytes(payload));

    /// <summary>压缩字节。</summary>
    public static byte[] CompressBytes(ReadOnlySpan<byte> data)
    {
        using var compressor = new ZstdSharp.Compressor(3);
        return compressor.Wrap(data).ToArray();
    }

    /// <summary>解压字节。</summary>
    public static byte[] DecompressBytes(ReadOnlySpan<byte> data)
    {
        using var decompressor = new ZstdSharp.Decompressor();
        return decompressor.Unwrap(data).ToArray();
    }
}
