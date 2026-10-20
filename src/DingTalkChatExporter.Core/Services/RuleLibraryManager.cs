using System.Text.Json;
using System.Text.RegularExpressions;
using DingTalkChatExporter.Core.Infrastructure;
using DingTalkChatExporter.Core.Management;
using DingTalkChatExporter.Core.Models;

namespace DingTalkChatExporter.Core.Services;

/// <summary>
/// One JSON file per rule library under rule-libraries/. Saves keep up to five
/// embedded versions plus .bak.json snapshots, and "delete" moves the library to
/// .trash instead of physically destroying it.
/// </summary>
public sealed class RuleLibraryManager
{
    private const int MaxVersions = 5;

    private static readonly Regex SafeIdRegex = new("^[A-Za-z0-9_-]{1,80}$", RegexOptions.Compiled);

    private readonly object _sync = new();
    private readonly List<string> _loadErrors = new();

    public RuleLibraryManager(string directoryPath)
    {
        DirectoryPath = directoryPath ?? throw new ArgumentNullException(nameof(directoryPath));
        Directory.CreateDirectory(DirectoryPath);
    }

    public string DirectoryPath { get; }

    public IReadOnlyList<string> LoadErrors
    {
        get
        {
            lock (_sync)
            {
                return _loadErrors.ToArray();
            }
        }
    }

    public IReadOnlyList<RuleLibrary> LoadAll()
    {
        lock (_sync)
        {
            var libraries = new List<RuleLibrary>();
            if (!Directory.Exists(DirectoryPath))
            {
                return libraries;
            }

            foreach (var file in Directory.EnumerateFiles(DirectoryPath, "*.json"))
            {
                if (file.EndsWith(".bak.json", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                try
                {
                    var json = File.ReadAllText(file);
                    var library = JsonSerializer.Deserialize<RuleLibrary>(json, JsonDefaults.Indented);
                    if (library is null || string.IsNullOrWhiteSpace(library.Id))
                    {
                        _loadErrors.Add($"规则库文件内容为空或缺少 Id：{file}");
                        continue;
                    }

                    libraries.Add(library);
                }
                catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
                {
                    _loadErrors.Add($"规则库文件损坏或无法读取：{file}（{ex.Message}）");
                }
            }

            return libraries
                .OrderByDescending(library => library.IsDefault)
                .ThenBy(library => library.Name, StringComparer.CurrentCultureIgnoreCase)
                .ToList();
        }
    }

    public RuleLibrary? Load(string id)
    {
        lock (_sync)
        {
            return LoadCore(id);
        }
    }

    public RuleLibrary Create(string name)
    {
        var library = new RuleLibrary
        {
            Id = Guid.NewGuid().ToString("N"),
            Name = string.IsNullOrWhiteSpace(name) ? "未命名规则库" : name.Trim(),
            CreatedAt = DateTimeOffset.UtcNow,
            UpdatedAt = DateTimeOffset.UtcNow
        };

        return Save(library);
    }

    public RuleLibrary Save(RuleLibrary library)
    {
        lock (_sync)
        {
            return SaveCore(library, createVersion: true);
        }
    }

    public RuleLibrary Duplicate(string sourceId, string? newName = null)
    {
        lock (_sync)
        {
            var source = LoadCore(sourceId) ?? throw new InvalidOperationException("找不到要复制的规则库。");
            var copy = source with
            {
                Id = Guid.NewGuid().ToString("N"),
                Name = string.IsNullOrWhiteSpace(newName) ? source.Name + " - 副本" : newName.Trim(),
                IsDefault = false,
                Rules = source.Rules.Select(rule => rule with { }).ToList(),
                Versions = new List<RuleLibraryVersion>(),
                CreatedAt = DateTimeOffset.UtcNow,
                UpdatedAt = DateTimeOffset.UtcNow
            };

            WriteLibraryFile(copy);
            return copy;
        }
    }

    public bool Delete(string id)
    {
        lock (_sync)
        {
            var path = GetLibraryPath(id);
            if (!File.Exists(path))
            {
                return false;
            }

            var trashDirectory = Path.Combine(DirectoryPath, ".trash");
            Directory.CreateDirectory(trashDirectory);
            var target = Path.Combine(
                trashDirectory,
                $"{id}-{DateTimeOffset.UtcNow:yyyyMMddHHmmssfff}.json");
            File.Move(path, target);
            return true;
        }
    }

    public void SetDefault(string id)
    {
        lock (_sync)
        {
            var libraries = LoadAll().ToList();
            foreach (var library in libraries)
            {
                var shouldBeDefault = string.Equals(library.Id, id, StringComparison.Ordinal);
                if (library.IsDefault == shouldBeDefault)
                {
                    continue;
                }

                WriteLibraryFile(library with
                {
                    IsDefault = shouldBeDefault,
                    UpdatedAt = shouldBeDefault ? DateTimeOffset.UtcNow : library.UpdatedAt
                });
            }
        }
    }

    public RuleLibrary? RestoreVersion(string libraryId, string versionId)
    {
        lock (_sync)
        {
            var library = LoadCore(libraryId);
            var version = library?.Versions.FirstOrDefault(v => v.VersionId == versionId);
            if (library is null || version is null)
            {
                return null;
            }

            var restored = library with
            {
                Rules = version.Rules.Select(rule => rule with { }).ToList(),
                UpdatedAt = DateTimeOffset.UtcNow
            };

            return SaveCore(restored, createVersion: true);
        }
    }

    public string GetLibraryPath(string id)
    {
        if (string.IsNullOrWhiteSpace(id) || !SafeIdRegex.IsMatch(id))
        {
            throw new ArgumentException("规则库 Id 不合法。", nameof(id));
        }

        return Path.Combine(DirectoryPath, id + ".json");
    }

    private RuleLibrary SaveCore(RuleLibrary library, bool createVersion)
    {
        Validate(library);
        var path = GetLibraryPath(library.Id);
        RuleLibrary? previous = null;

        if (File.Exists(path))
        {
            try
            {
                previous = LoadCore(library.Id);
            }
            catch (Exception)
            {
                previous = null;
            }
        }

        var updated = library with { UpdatedAt = DateTimeOffset.UtcNow };

        if (createVersion && previous is not null)
        {
            var snapshot = new RuleLibraryVersion
            {
                VersionId = Guid.NewGuid().ToString("N"),
                SavedAt = previous.UpdatedAt,
                Rules = previous.Rules.Select(rule => rule with { }).ToList(),
                Note = "自动备份"
            };

            updated = updated with
            {
                Versions = previous.Versions
                    .Concat(new[] { snapshot })
                    .TakeLast(MaxVersions)
                    .ToList()
            };

            BackupFile(path, library.Id, previous);
        }

        WriteLibraryFile(updated);

        if (updated.IsDefault)
        {
            foreach (var other in LoadAll().Where(other => !string.Equals(other.Id, updated.Id, StringComparison.Ordinal) && other.IsDefault))
            {
                WriteLibraryFile(other with { IsDefault = false });
            }
        }

        return updated;
    }

    private void BackupFile(string path, string id, RuleLibrary previous)
    {
        try
        {
            var timestamp = DateTimeOffset.UtcNow.ToString("yyyyMMddHHmmssfff");
            var backupPath = Path.Combine(DirectoryPath, $"rules-{id}-{timestamp}.bak.json");
            File.Copy(path, backupPath, overwrite: false);

            var staleBackups = Directory
                .EnumerateFiles(DirectoryPath, $"rules-{id}-*.bak.json")
                .OrderByDescending(File.GetLastWriteTimeUtc)
                .Skip(MaxVersions)
                .ToList();

            foreach (var stale in staleBackups)
            {
                try
                {
                    File.Delete(stale);
                }
                catch (IOException)
                {
                }
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            _loadErrors.Add($"规则库备份文件写入失败：{previous.Id}（{ex.Message}）");
        }
    }

    private RuleLibrary? LoadCore(string id)
    {
        var path = GetLibraryPath(id);
        if (!File.Exists(path))
        {
            return null;
        }

        try
        {
            var json = File.ReadAllText(path);
            return JsonSerializer.Deserialize<RuleLibrary>(json, JsonDefaults.Indented);
        }
        catch (JsonException ex)
        {
            _loadErrors.Add($"规则库文件损坏：{path}（{ex.Message}）");
            return null;
        }
        catch (IOException ex)
        {
            _loadErrors.Add($"规则库文件无法读取：{path}（{ex.Message}）");
            return null;
        }
    }

    private void WriteLibraryFile(RuleLibrary library)
    {
        Validate(library);
        var path = GetLibraryPath(library.Id);
        var json = JsonSerializer.Serialize(library, JsonDefaults.Indented);
        AtomicFile.WriteAllText(path, json);
    }

    private static void Validate(RuleLibrary library)
    {
        if (library is null)
        {
            throw new ArgumentNullException(nameof(library));
        }

        if (string.IsNullOrWhiteSpace(library.Id) || !SafeIdRegex.IsMatch(library.Id))
        {
            throw new ArgumentException("规则库 Id 不合法。", nameof(library));
        }

        if (string.IsNullOrWhiteSpace(library.Name))
        {
            throw new ArgumentException("规则库名称不能为空。", nameof(library));
        }
    }
}
