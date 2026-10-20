using System.Text.Json;

namespace DingTalkChatExporter.Core.Infrastructure;

/// <summary>
/// Small atomic JSON store for settings-like files. A corrupt file is moved aside
/// (never deleted) and the caller receives the supplied default value.
/// </summary>
public sealed class JsonFileStore<T>
    where T : class
{
    private readonly object _sync = new();
    private readonly Func<T> _defaultFactory;
    private readonly JsonSerializerOptions _options;

    public JsonFileStore(string path, Func<T> defaultFactory, JsonSerializerOptions? options = null)
    {
        Path = path;
        _defaultFactory = defaultFactory;
        _options = options ?? JsonDefaults.Indented;
    }

    public string FilePath => Path;

    public string Path { get; }

    public string? LastWarning { get; private set; }

    public T Load()
    {
        lock (_sync)
        {
            LastWarning = null;
            if (!File.Exists(Path))
            {
                return _defaultFactory();
            }

            try
            {
                var json = File.ReadAllText(Path);
                if (string.IsNullOrWhiteSpace(json))
                {
                    return _defaultFactory();
                }

                return JsonSerializer.Deserialize<T>(json, _options) ?? _defaultFactory();
            }
            catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
            {
                LastWarning = "配置文件损坏，已隔离并恢复默认值：" + ex.Message;
                Quarantine();
                return _defaultFactory();
            }
        }
    }

    public void Save(T value)
    {
        lock (_sync)
        {
            AtomicFile.WriteAllText(Path, JsonSerializer.Serialize(value, _options));
        }
    }

    private void Quarantine()
    {
        try
        {
            var directory = System.IO.Path.GetDirectoryName(System.IO.Path.GetFullPath(Path))
                            ?? throw new InvalidOperationException("Invalid settings path.");
            var fileName = System.IO.Path.GetFileName(Path);
            var target = System.IO.Path.Combine(
                directory,
                $"{fileName}.corrupt-{DateTimeOffset.UtcNow:yyyyMMddHHmmss}-{Guid.NewGuid():N}");
            File.Move(Path, target);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            LastWarning += "（隔离失败：" + ex.Message + "）";
        }
    }
}
