using System.IO;
using System.Linq;
using System.Text.Json;
using DingTalkChatExporter.Core.Infrastructure;
using DingTalkChatExporter.Core.Management;
using Serilog;

namespace DingTalkChatExporter.App.Services;

/// <summary>
/// Incremental snapshot storage. snapshot.json holds workbench state, while the
/// per-group incremental read watermarks are stored independently in state.json.
/// Every write goes through a same-directory temp file + File.Replace.
/// </summary>
public sealed class SnapshotService
{
    private sealed record IncrementalStateFile
    {
        public Dictionary<string, GroupIncrementalState> Groups { get; init; } = new(StringComparer.Ordinal);
    }

    private readonly object _sync = new();
    private readonly AppPaths _paths;
    private readonly ILogger _logger;
    private readonly Func<bool> _autoSnapshotEnabled;
    private readonly Func<bool> _savePreviewEnabled;

    private AppSnapshot _current = new();
    private DateTimeOffset? _lastSavedAt;
    private string? _lastWarning;

    public SnapshotService(
        AppPaths paths,
        ILogger logger,
        Func<bool>? autoSnapshotEnabled = null,
        Func<bool>? savePreviewEnabled = null)
    {
        _paths = paths;
        _logger = logger.ForContext<SnapshotService>();
        _autoSnapshotEnabled = autoSnapshotEnabled ?? (() => true);
        _savePreviewEnabled = savePreviewEnabled ?? (() => true);
    }

    public string SnapshotPath => _paths.SnapshotFile;
    public string StatePath => _paths.IncrementalStateFile;

    public AppSnapshot Current
    {
        get
        {
            lock (_sync)
            {
                return _current;
            }
        }
    }

    public DateTimeOffset? LastSavedAt
    {
        get
        {
            lock (_sync)
            {
                return _lastSavedAt;
            }
        }
    }

    public string? LastWarning
    {
        get
        {
            lock (_sync)
            {
                return _lastWarning;
            }
        }
    }

    public bool IsSnapshotFileValid
    {
        get
        {
            if (!File.Exists(_paths.SnapshotFile))
            {
                return false;
            }

            try
            {
                var text = File.ReadAllText(_paths.SnapshotFile);
                using var document = JsonDocument.Parse(text);
                return document.RootElement.ValueKind == JsonValueKind.Object;
            }
            catch (Exception)
            {
                return false;
            }
        }
    }

    public AppSnapshot Load()
    {
        lock (_sync)
        {
            _lastWarning = null;
            var snapshot = new AppSnapshot();
            var states = new Dictionary<string, GroupIncrementalState>(StringComparer.Ordinal);

            if (File.Exists(_paths.SnapshotFile))
            {
                try
                {
                    var json = File.ReadAllText(_paths.SnapshotFile);
                    snapshot = JsonSerializer.Deserialize<AppSnapshot>(json, JsonDefaults.Indented) ?? new AppSnapshot();

                    if (snapshot.SchemaVersion != AppSnapshot.CurrentSchemaVersion)
                    {
                        _lastWarning = $"快照版本 {snapshot.SchemaVersion} 与当前版本 {AppSnapshot.CurrentSchemaVersion} 不兼容，已使用干净状态启动。";
                        _logger.Warning("Snapshot schema version {SchemaVersion} is incompatible; starting clean.", snapshot.SchemaVersion);
                        Quarantine(_paths.SnapshotFile, "incompatible");
                        snapshot = new AppSnapshot();
                    }
                    else
                    {
                        _lastSavedAt = File.GetLastWriteTimeUtc(_paths.SnapshotFile);
                    }
                }
                catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
                {
                    _lastWarning = "快照文件损坏或无法读取，已使用干净状态启动：" + ex.Message;
                    _logger.Warning(ex, "Snapshot file is corrupt or unreadable; starting clean.");
                    Quarantine(_paths.SnapshotFile, "corrupt");
                    snapshot = new AppSnapshot();
                }
            }

            if (File.Exists(_paths.IncrementalStateFile))
            {
                try
                {
                    var json = File.ReadAllText(_paths.IncrementalStateFile);
                    var file = JsonSerializer.Deserialize<IncrementalStateFile>(json, JsonDefaults.Indented);
                    if (file?.Groups is not null)
                    {
                        states = new Dictionary<string, GroupIncrementalState>(file.Groups, StringComparer.Ordinal);
                    }
                }
                catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
                {
                    var warning = "增量状态文件损坏，已忽略：" + ex.Message;
                    _lastWarning = _lastWarning is null ? warning : _lastWarning + " " + warning;
                    _logger.Warning(ex, "Incremental state file is corrupt; ignoring it.");
                }
            }

            _current = snapshot with { IncrementalStates = states };
            return _current;
        }
    }

    public void Update(Func<AppSnapshot, AppSnapshot> mutate, bool saveImmediately = true)
    {
        lock (_sync)
        {
            _current = mutate(_current);
        }

        if (saveImmediately)
        {
            SaveNow();
        }
    }

    public void SetNavigationPage(string pageKey) =>
        Update(snapshot => snapshot with { LastNavigationPage = pageKey });

    public void SetWorkbenchSelection(
        string? groupIdentifier,
        string? dataSource,
        string? templatePath,
        string? ruleLibraryId) =>
        Update(snapshot => snapshot with
        {
            CurrentGroupIdentifier = groupIdentifier,
            CurrentDataSource = dataSource,
            CurrentTemplatePath = templatePath,
            CurrentRuleLibraryId = ruleLibraryId
        });

    public void SetWindowPlacement(WindowPlacementState placement) =>
        Update(snapshot => snapshot with { WindowPlacement = placement }, saveImmediately: false);

    public void SetPreview(GroupPreviewState preview)
    {
        if (string.IsNullOrWhiteSpace(preview.GroupIdentifier))
        {
            return;
        }

        Update(snapshot =>
        {
            var previews = new Dictionary<string, GroupPreviewState>(snapshot.Previews, StringComparer.Ordinal)
            {
                [preview.GroupIdentifier] = preview
            };
            return snapshot with { Previews = previews };
        });
    }

    public void SetIncrementalState(GroupIncrementalState state)
    {
        if (string.IsNullOrWhiteSpace(state.GroupIdentifier))
        {
            return;
        }

        Update(snapshot =>
        {
            var states = new Dictionary<string, GroupIncrementalState>(snapshot.IncrementalStates, StringComparer.Ordinal)
            {
                [state.GroupIdentifier] = state
            };
            return snapshot with { IncrementalStates = states };
        });
    }

    public void SaveNow(bool force = false)
    {
        lock (_sync)
        {
            if (!force && !_autoSnapshotEnabled())
            {
                return;
            }

            try
            {
                _paths.EnsureDirectories();
                var timestamp = DateTimeOffset.UtcNow;
                var toWrite = _current with { SavedAt = timestamp };

                if (!_savePreviewEnabled())
                {
                    var metadataOnly = toWrite.Previews.ToDictionary(
                        pair => pair.Key,
                        pair => pair.Value with { Messages = new List<PreviewMessage>() },
                        StringComparer.Ordinal);
                    toWrite = toWrite with { Previews = metadataOnly };
                }

                var snapshotJson = JsonSerializer.Serialize(toWrite, JsonDefaults.Indented);
                AtomicFile.WriteAllText(_paths.SnapshotFile, snapshotJson);

                var stateJson = JsonSerializer.Serialize(
                    new IncrementalStateFile
                    {
                        Groups = new Dictionary<string, GroupIncrementalState>(
                            _current.IncrementalStates,
                            StringComparer.Ordinal)
                    },
                    JsonDefaults.Indented);
                AtomicFile.WriteAllText(_paths.IncrementalStateFile, stateJson);

                _current = toWrite;
                _lastSavedAt = timestamp;
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                _logger.Warning(ex, "Failed to save snapshot. Path={SnapshotPath}.", _paths.SnapshotFile);
            }
        }
    }

    public Task SaveAsync(bool force = false) => Task.Run(() => SaveNow(force));

    public void Clear(bool deleteFiles = true)
    {
        lock (_sync)
        {
            _current = new AppSnapshot();
            _lastSavedAt = null;
            _lastWarning = null;

            if (!deleteFiles)
            {
                return;
            }

            foreach (var path in new[] { _paths.SnapshotFile, _paths.IncrementalStateFile })
            {
                try
                {
                    if (File.Exists(path))
                    {
                        File.Delete(path);
                    }
                }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
                {
                    _logger.Warning(ex, "Failed to clear snapshot file {Path}.", path);
                }
            }
        }
    }

    private void Quarantine(string path, string reason)
    {
        try
        {
            var target = $"{path}.{reason}-{DateTimeOffset.UtcNow:yyyyMMddHHmmss}.bak";
            File.Move(path, target, overwrite: false);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            _logger.Debug(ex, "Could not quarantine snapshot file.");
        }
    }
}
