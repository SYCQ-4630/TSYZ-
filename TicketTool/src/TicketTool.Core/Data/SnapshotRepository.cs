using Microsoft.Data.Sqlite;
using TicketTool.Core.Models;

namespace TicketTool.Core.Data;

/// <summary>应用快照的持久化。</summary>
public sealed class SnapshotRepository
{
    private readonly Database _database;

    /// <summary>创建仓储。</summary>
    public SnapshotRepository(Database database) => _database = database;

    /// <summary>保存快照。</summary>
    public Task<long> SaveAsync(byte[] payload, bool cleanExit, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            await using var transaction = await connection.BeginTransactionAsync(token).ConfigureAwait(false);
            long id;
            await using (var command = connection.CreateCommand())
            {
                command.Transaction = (SqliteTransaction)transaction;
                command.CommandText = """
                    INSERT INTO app_snapshots(payload_zstd, clean_exit, created_at)
                    VALUES ($payload, $clean, $created);
                    SELECT last_insert_rowid();
                    """;
                command.Parameters.AddWithValue("$payload", payload);
                command.Parameters.AddWithValue("$clean", cleanExit ? 1 : 0);
                command.Parameters.AddWithValue("$created", TimeUtil.ToDb(DateTimeOffset.Now));
                id = Convert.ToInt64(await command.ExecuteScalarAsync(token).ConfigureAwait(false), System.Globalization.CultureInfo.InvariantCulture);
            }

            await using (var cleanup = connection.CreateCommand())
            {
                cleanup.Transaction = (SqliteTransaction)transaction;
                cleanup.CommandText = """
                    DELETE FROM app_snapshots
                    WHERE id NOT IN (SELECT id FROM app_snapshots ORDER BY id DESC LIMIT 30);
                    """;
                await cleanup.ExecuteNonQueryAsync(token).ConfigureAwait(false);
            }

            await transaction.CommitAsync(token).ConfigureAwait(false);
            return id;
        }, cancellationToken);

    /// <summary>读取最新快照。</summary>
    public Task<(long Id, byte[] Payload, bool CleanExit, DateTimeOffset CreatedAt)?> GetLatestAsync(CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = """
                SELECT id, payload_zstd, clean_exit, created_at
                FROM app_snapshots ORDER BY id DESC LIMIT 1;
                """;
            await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
            if (!await reader.ReadAsync(token).ConfigureAwait(false))
            {
                return ((long, byte[], bool, DateTimeOffset)?)null;
            }
            return (reader.GetInt64(0), (byte[])reader[1], reader.GetInt32(2) != 0, TimeUtil.FromDb(reader.GetString(3)));
        }, cancellationToken);

    /// <summary>标记快照为正常退出。</summary>
    public Task MarkCleanExitAsync(long snapshotId, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = "UPDATE app_snapshots SET clean_exit = 1 WHERE id = $id;";
            command.Parameters.AddWithValue("$id", snapshotId);
            await command.ExecuteNonQueryAsync(token).ConfigureAwait(false);
        }, cancellationToken);

    /// <summary>删除全部快照。</summary>
    public Task ClearAsync(CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = "DELETE FROM app_snapshots;";
            await command.ExecuteNonQueryAsync(token).ConfigureAwait(false);
        }, cancellationToken);

    /// <summary>获取快照统计信息。</summary>
    public Task<(int Count, DateTimeOffset? Latest)> GetStatusAsync(CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = "SELECT COUNT(*), MAX(created_at) FROM app_snapshots;";
            await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
            if (!await reader.ReadAsync(token).ConfigureAwait(false))
            {
                return (0, (DateTimeOffset?)null);
            }
            var count = reader.GetInt32(0);
            var latest = reader.IsDBNull(1) ? (DateTimeOffset?)null : TimeUtil.FromDb(reader.GetString(1));
            return (count, latest);
        }, cancellationToken);
}
