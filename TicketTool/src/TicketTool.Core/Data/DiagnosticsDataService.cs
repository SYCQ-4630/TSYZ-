using System.Text;
using Microsoft.Data.Sqlite;
using TicketTool.Core.Models;

namespace TicketTool.Core.Data;

/// <summary>诊断面板与诊断包所需的只读数据查询。</summary>
public sealed class DiagnosticsDataService
{
    private readonly Database _database;
    private readonly DatabaseInitializer _initializer;
    private readonly SyncQueueRepository _syncQueue;
    private readonly SnapshotRepository _snapshotRepository;
    private readonly BackupService _backup;

    /// <summary>创建服务。</summary>
    public DiagnosticsDataService(
        Database database,
        DatabaseInitializer initializer,
        SyncQueueRepository syncQueue,
        SnapshotRepository snapshotRepository,
        BackupService backup)
    {
        _database = database;
        _initializer = initializer;
        _syncQueue = syncQueue;
        _snapshotRepository = snapshotRepository;
        _backup = backup;
    }

    /// <summary>数据库文件路径。</summary>
    public string DatabasePath => _database.DatabasePath;

    /// <summary>读取全部 app_config。</summary>
    public Task<Dictionary<string, string>> GetConfigAsync(CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            var result = new Dictionary<string, string>(StringComparer.Ordinal);
            await using var command = connection.CreateCommand();
            command.CommandText = "SELECT k, v FROM app_config ORDER BY k;";
            await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
            while (await reader.ReadAsync(token).ConfigureAwait(false))
            {
                result[reader.GetString(0)] = reader.GetString(1);
            }
            return result;
        }, cancellationToken);

    /// <summary>生成诊断文本。</summary>
    public async Task<string> BuildSummaryAsync(CancellationToken cancellationToken = default)
    {
        var builder = new StringBuilder();
        var schema = await _initializer.GetSchemaVersionAsync(cancellationToken).ConfigureAwait(false);
        var integrity = await _backup.QuickCheckAsync(cancellationToken).ConfigureAwait(false);
        var stats = await _syncQueue.GetStatisticsAsync(cancellationToken).ConfigureAwait(false);
        var snapshots = await _snapshotRepository.GetStatusAsync(cancellationToken).ConfigureAwait(false);
        var counts = await GetTableCountsAsync(cancellationToken).ConfigureAwait(false);

        builder.AppendLine("TicketTool 诊断摘要");
        builder.AppendLine($"生成时间：{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss zzz}");
        builder.AppendLine($"数据库：{_database.DatabasePath}");
        builder.AppendLine($"Schema 版本：{schema}");
        builder.AppendLine($"quick_check：{(integrity.Ok ? "通过" : "异常")}（{integrity.Message}）");
        builder.AppendLine($"同步队列：待同步 {stats.Pending}，同步中 {stats.Syncing}，已同步 {stats.Synced}，等待文件 {stats.Blocked}，失败 {stats.Failed}");
        builder.AppendLine($"快照：{snapshots.Count} 份，最近 {snapshots.Latest?.ToString("yyyy-MM-dd HH:mm:ss") ?? "无"}");
        builder.AppendLine($"备份数量：{_backup.ListBackups().Count}");
        builder.AppendLine("表行数：");
        foreach (var item in counts)
        {
            builder.AppendLine($"  {item.Key} = {item.Value}");
        }
        return builder.ToString();
    }

    /// <summary>读取各表行数。</summary>
    public Task<Dictionary<string, long>> GetTableCountsAsync(CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            var result = new Dictionary<string, long>(StringComparer.Ordinal);
            var tables = new[] { "templates", "template_fields", "field_rules", "rule_versions", "tickets", "field_spans", "sync_queue", "app_snapshots" };
            foreach (var table in tables)
            {
                await using var command = connection.CreateCommand();
                command.CommandText = $"SELECT COUNT(*) FROM {table};";
                var count = await command.ExecuteScalarAsync(token).ConfigureAwait(false);
                result[table] = Convert.ToInt64(count, System.Globalization.CultureInfo.InvariantCulture);
            }
            return result;
        }, cancellationToken);

    /// <summary>读取同步队列明细。</summary>
    public Task<List<SyncQueueItem>> GetSyncItemsAsync(int limit, CancellationToken cancellationToken = default)
        => _syncQueue.GetCandidatesAsync(Math.Clamp(limit, 1, 10000), true, cancellationToken);
}
