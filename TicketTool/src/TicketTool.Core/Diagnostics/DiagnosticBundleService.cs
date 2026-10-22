using System.IO.Compression;
using System.Text.Json;
using Serilog;
using TicketTool.Core.Data;
using TicketTool.Core.Models;

namespace TicketTool.Core.Diagnostics;

/// <summary>一键生成诊断包，不包含数据库主文件。</summary>
public sealed class DiagnosticBundleService
{
    private readonly AppPaths _paths;
    private readonly DiagnosticsDataService _data;
    private readonly DatabaseInitializer _initializer;
    private readonly SyncQueueRepository _syncQueue;
    private readonly SnapshotRepository _snapshots;
    private readonly ILogger _logger;

    /// <summary>创建服务。</summary>
    public DiagnosticBundleService(
        AppPaths paths,
        DiagnosticsDataService data,
        DatabaseInitializer initializer,
        SyncQueueRepository syncQueue,
        SnapshotRepository snapshots,
        ILogger? logger = null)
    {
        _paths = paths;
        _data = data;
        _initializer = initializer;
        _syncQueue = syncQueue;
        _snapshots = snapshots;
        _logger = logger ?? Log.ForContext<DiagnosticBundleService>();
    }

    /// <summary>导出诊断包。</summary>
    public async Task<DiagnosticBundleResult> ExportAsync(string? targetPath = null, CancellationToken cancellationToken = default)
    {
        Directory.CreateDirectory(_paths.DiagnosticsDirectory);
        var filePath = targetPath ?? Path.Combine(
            _paths.DiagnosticsDirectory,
            $"TicketTool-diagnostics-{DateTime.Now:yyyyMMdd-HHmmss}.zip");
        var temp = Path.Combine(_paths.TempDirectory, "diag-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(temp);

        try
        {
            var logDir = Path.Combine(temp, "logs");
            Directory.CreateDirectory(logDir);
            var fileCount = 0;
            if (Directory.Exists(_paths.LogsDirectory))
            {
                foreach (var logFile in Directory.EnumerateFiles(_paths.LogsDirectory, "*.log"))
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    var destination = Path.Combine(logDir, Path.GetFileName(logFile));
                    CopyShared(logFile, destination);
                    fileCount++;
                }
            }

            var config = await _data.GetConfigAsync(cancellationToken).ConfigureAwait(false);
            var schema = await _initializer.GetSchemaVersionAsync(cancellationToken).ConfigureAwait(false);
            config["schema_version"] = schema.ToString(System.Globalization.CultureInfo.InvariantCulture);
            await WriteTextAsync(Path.Combine(temp, "app_config.json"),
                JsonSerializer.Serialize(config, new JsonSerializerOptions { WriteIndented = true }),
                cancellationToken).ConfigureAwait(false);
            fileCount++;

            var summary = await _data.BuildSummaryAsync(cancellationToken).ConfigureAwait(false);
            await WriteTextAsync(Path.Combine(temp, "summary.txt"), summary, cancellationToken).ConfigureAwait(false);
            fileCount++;

            var syncItems = await _data.GetSyncItemsAsync(5000, cancellationToken).ConfigureAwait(false);
            await WriteTextAsync(Path.Combine(temp, "sync_queue.json"),
                JsonSerializer.Serialize(syncItems, JsonDefaults.Options),
                cancellationToken).ConfigureAwait(false);
            fileCount++;

            var snapshotStatus = await _snapshots.GetStatusAsync(cancellationToken).ConfigureAwait(false);
            await WriteTextAsync(Path.Combine(temp, "snapshot_status.json"),
                JsonSerializer.Serialize(new
                {
                    count = snapshotStatus.Count,
                    latest = snapshotStatus.Latest
                }, JsonDefaults.Options),
                cancellationToken).ConfigureAwait(false);
            fileCount++;

            var counts = await _data.GetTableCountsAsync(cancellationToken).ConfigureAwait(false);
            await WriteTextAsync(Path.Combine(temp, "table_counts.json"),
                JsonSerializer.Serialize(counts, JsonDefaults.Options),
                cancellationToken).ConfigureAwait(false);
            fileCount++;

            if (File.Exists(filePath))
            {
                File.Delete(filePath);
            }
            ZipFile.CreateFromDirectory(temp, filePath, CompressionLevel.Optimal, false);
            _logger.Information("DiagnosticBundleExported {Path} {Files}", filePath, fileCount);
            return new DiagnosticBundleResult { FilePath = filePath, FileCount = fileCount };
        }
        finally
        {
            try
            {
                if (Directory.Exists(temp))
                {
                    Directory.Delete(temp, true);
                }
            }
            catch (IOException)
            {
            }
        }
    }

    private static void CopyShared(string source, string destination)
    {
        using var input = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        using var output = new FileStream(destination, FileMode.Create, FileAccess.Write, FileShare.None);
        input.CopyTo(output);
    }

    private static Task WriteTextAsync(string path, string content, CancellationToken cancellationToken)
        => File.WriteAllTextAsync(path, content, System.Text.Encoding.UTF8, cancellationToken);
}
