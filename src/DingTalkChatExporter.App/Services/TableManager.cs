using System.IO;
using System.Linq;
using System.Text.Json;
using DingTalkChatExporter.Core.Abstractions;
using DingTalkChatExporter.Core.Infrastructure;
using DingTalkChatExporter.Core.Management;
using DingTalkChatExporter.Core.Services;
using Serilog;

namespace DingTalkChatExporter.App.Services;

/// <summary>
/// Instant-persistence table list. Removing a table never deletes the Excel file;
/// a separate physical-delete entry point exists for the explicit destructive action.
/// </summary>
public sealed class TableManager
{
    private sealed record TableFile
    {
        public List<ManagedTable> Tables { get; init; } = new();
    }

    private readonly object _sync = new();
    private readonly AppPaths _paths;
    private readonly ITableIndexService _indexService;
    private readonly IndexStateStore _indexStates;
    private readonly ILogger _logger;

    private List<ManagedTable> _tables = new();
    private List<ImportHistoryEntry> _history = new();
    private List<RemovedTableEntry> _removed = new();

    public TableManager(AppPaths paths, ITableIndexService indexService, ILogger logger)
    {
        _paths = paths;
        _indexService = indexService;
        _indexStates = new IndexStateStore(paths);
        _logger = logger.ForContext<TableManager>();
    }

    public event EventHandler? Changed;

    public string? LastLoadWarning { get; private set; }

    public IReadOnlyList<ManagedTable> Tables
    {
        get
        {
            lock (_sync)
            {
                return _tables.ToArray();
            }
        }
    }

    public IReadOnlyList<ImportHistoryEntry> ImportHistory
    {
        get
        {
            lock (_sync)
            {
                return _history.OrderByDescending(entry => entry.ImportedAt).ToArray();
            }
        }
    }

    public IReadOnlyList<RemovedTableEntry> RemovedTables
    {
        get
        {
            lock (_sync)
            {
                return _removed.OrderByDescending(entry => entry.RemovedAt).ToArray();
            }
        }
    }

    public void Load()
    {
        lock (_sync)
        {
            LastLoadWarning = null;
            _tables = LoadTablesCore();
            _history = LoadHistoryCore();
            _removed = LoadRemovedCore();
            MarkStaleCore();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    public ManagedTable? GetActive()
    {
        lock (_sync)
        {
            return _tables.FirstOrDefault(table => table.IsActive);
        }
    }

    public ManagedTable? Get(Guid id)
    {
        lock (_sync)
        {
            return _tables.FirstOrDefault(table => table.Id == id);
        }
    }

    public ManagedTable Import(string filePath, string? displayName = null, string? boundRuleLibraryId = null)
    {
        if (!File.Exists(filePath))
        {
            throw new FileNotFoundException("表格文件不存在。", filePath);
        }

        ManagedTable table;
        lock (_sync)
        {
            var existing = _tables.FirstOrDefault(
                candidate => string.Equals(candidate.FilePath, filePath, StringComparison.OrdinalIgnoreCase));

            if (existing is not null)
            {
                table = existing with
                {
                    IsActive = true,
                    LastUsedAt = DateTimeOffset.UtcNow,
                    BoundRuleLibraryId = boundRuleLibraryId ?? existing.BoundRuleLibraryId
                };
                _tables = _tables
                    .Select(candidate => candidate.Id == existing.Id
                        ? table
                        : candidate with { IsActive = false })
                    .ToList();
            }
            else
            {
                table = new ManagedTable
                {
                    Id = Guid.NewGuid(),
                    DisplayName = string.IsNullOrWhiteSpace(displayName)
                        ? Path.GetFileNameWithoutExtension(filePath)
                        : displayName.Trim(),
                    FilePath = filePath,
                    IsActive = true,
                    BoundRuleLibraryId = boundRuleLibraryId,
                    AddedAt = DateTimeOffset.UtcNow,
                    LastUsedAt = DateTimeOffset.UtcNow,
                    IndexStatus = IndexStatus.NotBuilt
                };
                _tables = _tables
                    .Select(candidate => candidate with { IsActive = false })
                    .Append(table)
                    .ToList();
            }

            _history.Insert(0, new ImportHistoryEntry
            {
                Id = Guid.NewGuid(),
                TableId = table.Id,
                ImportedAt = DateTimeOffset.UtcNow,
                FilePath = table.FilePath,
                DisplayName = table.DisplayName,
                BoundRuleLibraryId = table.BoundRuleLibraryId
            });
            _history = _history.Take(500).ToList();
            _removed = _removed
                .Where(entry => !string.Equals(entry.FilePath, filePath, StringComparison.OrdinalIgnoreCase))
                .ToList();

            SaveTablesCore();
            SaveHistoryCore();
            SaveRemovedCore();
        }

        Changed?.Invoke(this, EventArgs.Empty);
        return table;
    }

    public void SetActive(Guid id)
    {
        lock (_sync)
        {
            var now = DateTimeOffset.UtcNow;
            _tables = _tables
                .Select(table => table.Id == id
                    ? table with { IsActive = true, LastUsedAt = now }
                    : table with { IsActive = false })
                .ToList();
            SaveTablesCore();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void Rename(Guid id, string displayName)
    {
        if (string.IsNullOrWhiteSpace(displayName))
        {
            throw new ArgumentException("显示名称不能为空。", nameof(displayName));
        }

        lock (_sync)
        {
            _tables = _tables
                .Select(table => table.Id == id ? table with { DisplayName = displayName.Trim() } : table)
                .ToList();
            SaveTablesCore();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    public async Task RemoveAsync(Guid id, CancellationToken cancellationToken = default)
    {
        lock (_sync)
        {
            var table = _tables.FirstOrDefault(candidate => candidate.Id == id);
            _tables = _tables.Where(candidate => candidate.Id != id).ToList();
            if (table is not null)
            {
                _removed.Insert(0, new RemovedTableEntry
                {
                    TableId = table.Id,
                    DisplayName = table.DisplayName,
                    FilePath = table.FilePath,
                    RemovedAt = DateTimeOffset.UtcNow,
                    BoundRuleLibraryId = table.BoundRuleLibraryId
                });
                _removed = _removed.Take(500).ToList();
                SaveRemovedCore();
            }

            SaveTablesCore();
        }

        _indexStates.Remove(id);
        await _indexService.DeleteIndexAsync(id, cancellationToken).ConfigureAwait(false);
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public ManagedTable RestoreRemoved(Guid entryId)
    {
        RemovedTableEntry? entry;
        lock (_sync)
        {
            entry = _removed.FirstOrDefault(candidate => candidate.Id == entryId);
        }

        if (entry is null)
        {
            throw new InvalidOperationException("找不到要恢复的表格记录。");
        }

        if (!File.Exists(entry.FilePath))
        {
            throw new FileNotFoundException("原 Excel 文件已不存在，无法恢复。", entry.FilePath);
        }

        var restored = Import(entry.FilePath, entry.DisplayName, entry.BoundRuleLibraryId);
        lock (_sync)
        {
            _removed = _removed.Where(candidate => candidate.Id != entryId).ToList();
            SaveRemovedCore();
        }

        Changed?.Invoke(this, EventArgs.Empty);
        return restored;
    }

    public void DismissRemoved(Guid entryId)
    {
        lock (_sync)
        {
            _removed = _removed.Where(entry => entry.Id != entryId).ToList();
            SaveRemovedCore();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    public async Task BuildIndexAsync(
        Guid id,
        string? sheetName = null,
        CancellationToken cancellationToken = default)
    {
        var table = Get(id) ?? throw new InvalidOperationException("表格不存在，无法建立索引。");
        try
        {
            await _indexService.BuildIndexAsync(table, sheetName, cancellationToken).ConfigureAwait(false);
            var hash = IndexStateHasher.TryCompute(table.FilePath);
            if (hash is not null)
            {
                _indexStates.Save(table, sheetName, hash);
            }

            UpdateIndexState(id, IndexStatus.Ready, DateTimeOffset.UtcNow);
        }
        catch (OperationCanceledException)
        {
            UpdateIndexState(id, IndexStatus.NotBuilt, null);
            throw;
        }
        catch
        {
            UpdateIndexState(id, IndexStatus.Unavailable, null);
            throw;
        }
    }

    public async Task DeletePhysicallyAsync(Guid id, CancellationToken cancellationToken = default)
    {
        ManagedTable? table;
        lock (_sync)
        {
            table = _tables.FirstOrDefault(candidate => candidate.Id == id);
            if (table is null)
            {
                return;
            }

            _tables = _tables.Where(candidate => candidate.Id != id).ToList();
            SaveTablesCore();
        }

        try
        {
            if (File.Exists(table.FilePath))
            {
                File.Delete(table.FilePath);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            _logger.Warning(ex, "Failed to physically delete table file. TableId={TableId}.", id);
        }

        lock (_sync)
        {
            _removed = _removed.Where(entry => entry.TableId != id).ToList();
            SaveRemovedCore();
        }

        _indexStates.Remove(id);
        await _indexService.DeleteIndexAsync(id, cancellationToken).ConfigureAwait(false);
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void BindRuleLibrary(Guid id, string? ruleLibraryId)
    {
        lock (_sync)
        {
            _tables = _tables
                .Select(table => table.Id == id ? table with { BoundRuleLibraryId = ruleLibraryId } : table)
                .ToList();
            SaveTablesCore();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void UpdateIndexState(Guid id, IndexStatus status, DateTimeOffset? indexedAt)
    {
        lock (_sync)
        {
            _tables = _tables
                .Select(table => table.Id == id
                    ? table with { IndexStatus = status, IndexedAt = indexedAt ?? table.IndexedAt }
                    : table)
                .ToList();
            SaveTablesCore();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void MarkStaleIfExcelModified()
    {
        lock (_sync)
        {
            MarkStaleCore();
            SaveTablesCore();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void ClearImportHistory()
    {
        lock (_sync)
        {
            _history.Clear();
            SaveHistoryCore();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    private void MarkStaleCore()
    {
        _tables = _tables.Select(table =>
        {
            if (table.IndexStatus != IndexStatus.Ready || table.IndexedAt is null || !File.Exists(table.FilePath))
            {
                return table;
            }

            try
            {
                var state = _indexStates.Load(table.Id);
                var currentHash = IndexStateHasher.TryCompute(table.FilePath);
                if (state is not null
                    && currentHash is not null
                    && !string.Equals(state.SourceHash, currentHash, StringComparison.Ordinal))
                {
                    return table with { IndexStatus = IndexStatus.Stale };
                }

                var modified = File.GetLastWriteTimeUtc(table.FilePath);
                return modified > table.IndexedAt.Value.UtcDateTime
                    ? table with { IndexStatus = IndexStatus.Stale }
                    : table;
            }
            catch (IOException)
            {
                return table;
            }
        }).ToList();
    }

    private List<ManagedTable> LoadTablesCore()
    {
        try
        {
            if (!File.Exists(_paths.TablesFile))
            {
                return new List<ManagedTable>();
            }

            var json = File.ReadAllText(_paths.TablesFile);
            var file = JsonSerializer.Deserialize<TableFile>(json, JsonDefaults.Indented);
            var tables = file?.Tables ?? new List<ManagedTable>();

            var activeSeen = false;
            tables = tables
                .Select(table =>
                {
                    if (!table.IsActive)
                    {
                        return table;
                    }

                    if (activeSeen)
                    {
                        return table with { IsActive = false };
                    }

                    activeSeen = true;
                    return table;
                })
                .ToList();

            return tables;
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            LastLoadWarning = "表格列表损坏或无法读取，已使用空列表：" + ex.Message;
            _logger.Warning(ex, "Failed to load tables.json.");
            return new List<ManagedTable>();
        }
    }

    private List<RemovedTableEntry> LoadRemovedCore()
    {
        try
        {
            if (!File.Exists(_paths.RemovedTablesFile))
            {
                return new List<RemovedTableEntry>();
            }

            return JsonSerializer.Deserialize<List<RemovedTableEntry>>(
                       File.ReadAllText(_paths.RemovedTablesFile),
                       JsonDefaults.Indented)
                   ?? new List<RemovedTableEntry>();
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            _logger.Warning(ex, "Failed to load removed-tables.json.");
            return new List<RemovedTableEntry>();
        }
    }

    private List<ImportHistoryEntry> LoadHistoryCore()
    {
        try
        {
            if (!File.Exists(_paths.ImportHistoryFile))
            {
                return new List<ImportHistoryEntry>();
            }

            var json = File.ReadAllText(_paths.ImportHistoryFile);
            return JsonSerializer.Deserialize<List<ImportHistoryEntry>>(json, JsonDefaults.Indented)
                   ?? new List<ImportHistoryEntry>();
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            _logger.Warning(ex, "Failed to load import-history.json.");
            return new List<ImportHistoryEntry>();
        }
    }

    private void SaveTablesCore()
    {
        _paths.EnsureDirectories();
        var json = JsonSerializer.Serialize(new TableFile { Tables = _tables }, JsonDefaults.Indented);
        AtomicFile.WriteAllText(_paths.TablesFile, json);
    }

    private void SaveRemovedCore()
    {
        _paths.EnsureDirectories();
        var json = JsonSerializer.Serialize(_removed, JsonDefaults.Indented);
        AtomicFile.WriteAllText(_paths.RemovedTablesFile, json);
    }

    private void SaveHistoryCore()
    {
        _paths.EnsureDirectories();
        var json = JsonSerializer.Serialize(_history, JsonDefaults.Indented);
        AtomicFile.WriteAllText(_paths.ImportHistoryFile, json);
    }
}
