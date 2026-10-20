using System.Linq;
using DingTalkChatExporter.Core.Infrastructure;
using DingTalkChatExporter.Core.Management;
using Serilog;

namespace DingTalkChatExporter.App.Services;

/// <summary>
/// Owns all non-snapshot configuration files. Every save is atomic and independent,
/// so a damaged file only resets that one category to its defaults.
/// </summary>
public sealed class SettingsService
{
    public const int MaxExportStats = 200;
    public const int MaxRecentGroups = 12;

    private readonly JsonFileStore<FeatureSettings> _features;
    private readonly JsonFileStore<UiSettings> _ui;
    private readonly JsonFileStore<DataSourceSettings> _dataSources;
    private readonly JsonFileStore<List<ExportStats>> _exportStats;
    private readonly ILogger _logger;

    private FeatureSettings _featureValues = new();
    private UiSettings _uiValues = new();
    private DataSourceSettings _dataSourceValues = new();
    private List<ExportStats> _exportValues = new();

    public SettingsService(AppPaths paths, ILogger logger)
    {
        _logger = logger.ForContext<SettingsService>();
        _features = new JsonFileStore<FeatureSettings>(paths.FeaturesFile, () => new FeatureSettings());
        _ui = new JsonFileStore<UiSettings>(paths.UiSettingsFile, () => new UiSettings());
        _dataSources = new JsonFileStore<DataSourceSettings>(
            paths.DataSourceSettingsFile,
            () => new DataSourceSettings());
        _exportStats = new JsonFileStore<List<ExportStats>>(
            paths.ExportStatsFile,
            () => new List<ExportStats>());
    }

    public IReadOnlyList<string> LastWarnings { get; private set; } = Array.Empty<string>();

    public FeatureSettings Features => _featureValues;

    public UiSettings Ui => _uiValues;

    public DataSourceSettings DataSources => _dataSourceValues;

    public IReadOnlyList<ExportStats> ExportStats => _exportValues;

    public void LoadAll()
    {
        var warnings = new List<string>();
        _featureValues = _features.Load();
        AddWarning(_features, warnings);
        _uiValues = _ui.Load();
        AddWarning(_ui, warnings);
        _dataSourceValues = _dataSources.Load();
        AddWarning(_dataSources, warnings);
        _exportValues = _exportStats.Load() ?? new List<ExportStats>();
        AddWarning(_exportStats, warnings);

        _uiValues = _uiValues with
        {
            RecentGroups = _uiValues.RecentGroups
                .OrderByDescending(group => group.LastUsedAt)
                .Take(MaxRecentGroups)
                .ToList()
        };
        _exportValues = _exportValues
            .OrderByDescending(entry => entry.ExportedAt)
            .Take(MaxExportStats)
            .ToList();
        LastWarnings = warnings;

        foreach (var warning in warnings)
        {
            _logger.Warning("Settings fallback: {Warning}", warning);
        }
    }

    public void SaveFeatures(FeatureSettings value)
    {
        _featureValues = value;
        _features.Save(value);
    }

    public void SaveUi(UiSettings value)
    {
        _uiValues = value;
        _ui.Save(value);
    }

    public void SaveDataSources(DataSourceSettings value)
    {
        _dataSourceValues = value;
        _dataSources.Save(value);
    }

    public void AddRecentGroup(string identifier, string displayName)
    {
        if (string.IsNullOrWhiteSpace(identifier))
        {
            return;
        }

        var now = DateTimeOffset.UtcNow;
        var groups = _uiValues.RecentGroups
            .Where(group => !string.Equals(group.Identifier, identifier, StringComparison.Ordinal))
            .Prepend(new RecentGroup(identifier.Trim(), displayName?.Trim() ?? identifier.Trim(), now))
            .Take(MaxRecentGroups)
            .ToList();
        SaveUi(_uiValues with { RecentGroups = groups });
    }

    public void RecordExport(ExportStats stats)
    {
        var values = _exportValues
            .Prepend(stats)
            .OrderByDescending(entry => entry.ExportedAt)
            .Take(MaxExportStats)
            .ToList();
        _exportValues = values;
        _exportStats.Save(values);
    }

    public void ClearExportStats()
    {
        _exportValues = new List<ExportStats>();
        _exportStats.Save(_exportValues);
    }

    private static void AddWarning<T>(JsonFileStore<T> store, List<string> warnings)
        where T : class
    {
        if (!string.IsNullOrWhiteSpace(store.LastWarning))
        {
            warnings.Add(store.LastWarning);
        }
    }
}
