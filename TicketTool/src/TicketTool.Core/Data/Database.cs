using Microsoft.Data.Sqlite;

namespace TicketTool.Core.Data;

/// <summary>
/// SQLite 访问入口。所有写操作通过单一信号量串行化，避免 database is locked。
/// </summary>
public sealed class Database : IAsyncDisposable
{
    private readonly SemaphoreSlim _writeGate = new(1, 1);
    private readonly string _connectionString;
    private bool _disposed;

    /// <summary>创建数据库访问入口。</summary>
    public Database(AppPaths paths)
    {
        Paths = paths;
        _connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = paths.DatabasePath,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Cache = SqliteCacheMode.Shared,
            ForeignKeys = true,
            DefaultTimeout = 30
        }.ToString();
    }

    /// <summary>应用路径。</summary>
    public AppPaths Paths { get; }

    /// <summary>数据库文件路径。</summary>
    public string DatabasePath => Paths.DatabasePath;

    /// <summary>打开一个已配置 PRAGMA 的连接。</summary>
    public async Task<SqliteConnection> OpenConnectionAsync(CancellationToken cancellationToken = default)
    {
        var connection = new SqliteConnection(_connectionString);
        await connection.OpenAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            PRAGMA journal_mode = WAL;
            PRAGMA synchronous = NORMAL;
            PRAGMA foreign_keys = ON;
            PRAGMA busy_timeout = 5000;
            """;
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        return connection;
    }

    /// <summary>执行只读操作。</summary>
    public async Task<T> ReadAsync<T>(
        Func<SqliteConnection, CancellationToken, Task<T>> action,
        CancellationToken cancellationToken = default)
    {
        await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        return await action(connection, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>执行写操作，全局串行。</summary>
    public async Task<T> WriteAsync<T>(
        Func<SqliteConnection, CancellationToken, Task<T>> action,
        CancellationToken cancellationToken = default)
    {
        await _writeGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await using var connection = await OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
            return await action(connection, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _writeGate.Release();
        }
    }

    /// <summary>执行写操作，忽略返回值。</summary>
    public Task WriteAsync(
        Func<SqliteConnection, CancellationToken, Task> action,
        CancellationToken cancellationToken = default)
        => WriteAsync(async (connection, token) =>
        {
            await action(connection, token).ConfigureAwait(false);
            return true;
        }, cancellationToken);

    /// <inheritdoc />
    public ValueTask DisposeAsync()
    {
        if (_disposed)
        {
            return ValueTask.CompletedTask;
        }

        _disposed = true;
        _writeGate.Dispose();
        return ValueTask.CompletedTask;
    }
}
