using System.IO;
using System.Linq;
namespace DingTalkChatExporter.App.Services;

public sealed class AppPaths
{
    public AppPaths(string? baseDirectory = null)
    {
        BaseDirectory = string.IsNullOrWhiteSpace(baseDirectory)
            ? Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "DingTalkChatExporter")
            : baseDirectory;
    }

    public string BaseDirectory { get; }

    public string SnapshotFile => Path.Combine(BaseDirectory, "snapshot.json");
    public string IncrementalStateFile => Path.Combine(BaseDirectory, "state.json");
    public string TablesFile => Path.Combine(BaseDirectory, "tables.json");
    public string ImportHistoryFile => Path.Combine(BaseDirectory, "import-history.json");
    public string RemovedTablesFile => Path.Combine(BaseDirectory, "removed-tables.json");
    public string FeaturesFile => Path.Combine(BaseDirectory, "features.json");
    public string UiSettingsFile => Path.Combine(BaseDirectory, "ui-settings.json");
    public string DataSourceSettingsFile => Path.Combine(BaseDirectory, "data-sources.json");
    public string ExportStatsFile => Path.Combine(BaseDirectory, "export-stats.json");
    public string RuleLibrariesDirectory => Path.Combine(BaseDirectory, "rule-libraries");
    public string IndexesDirectory => Path.Combine(BaseDirectory, "indexes");
    public string LogsDirectory => Path.Combine(BaseDirectory, "logs");
    public string TempDirectory => Path.Combine(BaseDirectory, "temp");

    public void EnsureDirectories()
    {
        Directory.CreateDirectory(BaseDirectory);
        Directory.CreateDirectory(RuleLibrariesDirectory);
        Directory.CreateDirectory(IndexesDirectory);
        Directory.CreateDirectory(LogsDirectory);
        Directory.CreateDirectory(TempDirectory);
    }

    public string GetIndexPath(Guid tableId) => Path.Combine(IndexesDirectory, tableId.ToString("N") + ".db");
}
