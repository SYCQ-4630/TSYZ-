using Microsoft.Data.Sqlite;
using TicketTool.Core.Models;

namespace TicketTool.Core.Data;

/// <summary>同步队列的持久化与状态流转。</summary>
public sealed class SyncQueueRepository
{
    private readonly Database _database;

    /// <summary>创建仓储。</summary>
    public SyncQueueRepository(Database database) => _database = database;

    /// <summary>获取待处理记录（含启动补偿的 blocked / failed）。</summary>
    public Task<List<SyncQueueItem>> GetCandidatesAsync(int limit, bool includeFailed, CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            var result = new List<SyncQueueItem>();
            await using var command = connection.CreateCommand();
            command.CommandText = includeFailed
                ? """
                  SELECT id, ticket_id, status, retry, last_error, updated_at
                  FROM sync_queue
                  WHERE status IN ('pending', 'blocked', 'failed')
                  ORDER BY id LIMIT $limit;
                  """
                : """
                  SELECT id, ticket_id, status, retry, last_error, updated_at
                  FROM sync_queue
                  WHERE status IN ('pending', 'blocked')
                  ORDER BY id LIMIT $limit;
                  """;
            command.Parameters.AddWithValue("$limit", limit);
            await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
            while (await reader.ReadAsync(token).ConfigureAwait(false))
            {
                result.Add(new SyncQueueItem
                {
                    Id = reader.GetInt64(0),
                    TicketId = reader.GetInt64(1),
                    Status = EnumText.SyncFromDb(reader.GetString(2)),
                    Retry = reader.GetInt32(3),
                    LastError = reader.IsDBNull(4) ? null : reader.GetString(4),
                    UpdatedAt = TimeUtil.FromDb(reader.GetString(5))
                });
            }
            return result;
        }, cancellationToken);

    /// <summary>尝试认领一条记录，避免重复同步。</summary>
    public Task<bool> TryMarkSyncingAsync(long queueId, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = """
                UPDATE sync_queue
                SET status = 'syncing', updated_at = $now
                WHERE id = $id AND status <> 'syncing' AND status <> 'synced';
                """;
            command.Parameters.AddWithValue("$id", queueId);
            command.Parameters.AddWithValue("$now", TimeUtil.ToDb(DateTimeOffset.Now));
            return await command.ExecuteNonQueryAsync(token).ConfigureAwait(false) > 0;
        }, cancellationToken);

    /// <summary>标记同步成功。</summary>
    public Task MarkSyncedAsync(long queueId, CancellationToken cancellationToken = default)
        => UpdateStatusAsync(queueId, SyncStatus.Synced, null, false, cancellationToken);

    /// <summary>标记文件被占用。</summary>
    public Task MarkBlockedAsync(long queueId, string? error, CancellationToken cancellationToken = default)
        => UpdateStatusAsync(queueId, SyncStatus.Blocked, error, true, cancellationToken);

    /// <summary>标记失败。</summary>
    public Task MarkFailedAsync(long queueId, string error, CancellationToken cancellationToken = default)
        => UpdateStatusAsync(queueId, SyncStatus.Failed, error, true, cancellationToken);

    /// <summary>重置仍处于 syncing 的残留记录（异常退出后）。</summary>
    public Task ResetStaleSyncingAsync(CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = """
                UPDATE sync_queue SET status = 'pending', updated_at = $now WHERE status = 'syncing';
                """;
            command.Parameters.AddWithValue("$now", TimeUtil.ToDb(DateTimeOffset.Now));
            await command.ExecuteNonQueryAsync(token).ConfigureAwait(false);
        }, cancellationToken);

    /// <summary>手动重试失败记录。</summary>
    public Task RetryFailedAsync(long queueId, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = """
                UPDATE sync_queue SET status = 'pending', retry = 0, last_error = NULL, updated_at = $now
                WHERE id = $id;
                """;
            command.Parameters.AddWithValue("$id", queueId);
            command.Parameters.AddWithValue("$now", TimeUtil.ToDb(DateTimeOffset.Now));
            await command.ExecuteNonQueryAsync(token).ConfigureAwait(false);
        }, cancellationToken);

    /// <summary>统计各状态数量。</summary>
    public Task<SyncStatistics> GetStatisticsAsync(CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            var stats = new SyncStatistics();
            await using var command = connection.CreateCommand();
            command.CommandText = "SELECT status, COUNT(*) FROM sync_queue GROUP BY status;";
            await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
            while (await reader.ReadAsync(token).ConfigureAwait(false))
            {
                var status = EnumText.SyncFromDb(reader.GetString(0));
                var count = reader.GetInt32(1);
                switch (status)
                {
                    case SyncStatus.Pending: stats.Pending = count; break;
                    case SyncStatus.Syncing: stats.Syncing = count; break;
                    case SyncStatus.Synced: stats.Synced = count; break;
                    case SyncStatus.Blocked: stats.Blocked = count; break;
                    case SyncStatus.Failed: stats.Failed = count; break;
                }
            }
            return stats;
        }, cancellationToken);

    /// <summary>读取指定工单的队列记录。</summary>
    public Task<SyncQueueItem?> GetByTicketAsync(long ticketId, CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = """
                SELECT id, ticket_id, status, retry, last_error, updated_at
                FROM sync_queue WHERE ticket_id = $ticket ORDER BY id DESC LIMIT 1;
                """;
            command.Parameters.AddWithValue("$ticket", ticketId);
            await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
            if (!await reader.ReadAsync(token).ConfigureAwait(false))
            {
                return null;
            }
            return new SyncQueueItem
            {
                Id = reader.GetInt64(0),
                TicketId = reader.GetInt64(1),
                Status = EnumText.SyncFromDb(reader.GetString(2)),
                Retry = reader.GetInt32(3),
                LastError = reader.IsDBNull(4) ? null : reader.GetString(4),
                UpdatedAt = TimeUtil.FromDb(reader.GetString(5))
            };
        }, cancellationToken);

    private Task UpdateStatusAsync(long queueId, SyncStatus status, string? error, bool incrementRetry, CancellationToken cancellationToken)
        => _database.WriteAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = incrementRetry
                ? """
                  UPDATE sync_queue
                  SET status = $status, retry = retry + 1, last_error = $error, updated_at = $now
                  WHERE id = $id;
                  """
                : """
                  UPDATE sync_queue
                  SET status = $status, last_error = $error, updated_at = $now
                  WHERE id = $id;
                  """;
            command.Parameters.AddWithValue("$id", queueId);
            command.Parameters.AddWithValue("$status", EnumText.ToDb(status));
            command.Parameters.AddWithValue("$error", (object?)error ?? DBNull.Value);
            command.Parameters.AddWithValue("$now", TimeUtil.ToDb(DateTimeOffset.Now));
            await command.ExecuteNonQueryAsync(token).ConfigureAwait(false);
        }, cancellationToken);
}
