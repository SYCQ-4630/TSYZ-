using System.IO;
using DingTalkChatExporter.Core.Infrastructure;
using DingTalkChatExporter.Core.Management;

namespace DingTalkChatExporter.App.Services;

public sealed record IndexStateRecord
{
    public Guid TableId { get; init; }
    public string FilePath { get; init; } = string.Empty;
    public string SourceHash { get; init; } = string.Empty;
    public string? SheetName { get; init; }
    public DateTimeOffset IndexedAt { get; init; }
}

/// <summary>
/// Stores the source fingerprint next to each index database
/// (indexes/&lt;tableGuid&gt;.state.json) so a stale index is detected reliably.
/// </summary>
public sealed class IndexStateStore
{
    private readonly AppPaths _paths;

    public IndexStateStore(AppPaths paths)
    {
        _paths = paths;
    }

    public string GetStatePath(Guid tableId) =>
        Path.Combine(_paths.IndexesDirectory, tableId.ToString("N") + ".state.json");

    public IndexStateRecord? Load(Guid tableId)
    {
        var path = GetStatePath(tableId);
        try
        {
            if (!File.Exists(path))
            {
                return null;
            }

            return System.Text.Json.JsonSerializer.Deserialize<IndexStateRecord>(
                File.ReadAllText(path),
                JsonDefaults.Indented);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException
                                       or System.Text.Json.JsonException)
        {
            return null;
        }
    }

    public void Save(ManagedTable table, string? sheetName, string sourceHash)
    {
        _paths.EnsureDirectories();
        var record = new IndexStateRecord
        {
            TableId = table.Id,
            FilePath = table.FilePath,
            SheetName = sheetName,
            SourceHash = sourceHash,
            IndexedAt = DateTimeOffset.UtcNow
        };
        AtomicFile.WriteAllText(
            GetStatePath(table.Id),
            System.Text.Json.JsonSerializer.Serialize(record, JsonDefaults.Indented));
    }

    public void Remove(Guid tableId)
    {
        var path = GetStatePath(tableId);
        try
        {
            if (File.Exists(path))
            {
                File.Move(path, path + $".removed-{DateTimeOffset.UtcNow:yyyyMMddHHmmss}");
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
        }
    }
}
