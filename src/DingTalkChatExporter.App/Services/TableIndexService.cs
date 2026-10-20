using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using ClosedXML.Excel;
using DingTalkChatExporter.Core.Abstractions;
using DingTalkChatExporter.Core.Management;
using Microsoft.Data.Sqlite;
using Serilog;

namespace DingTalkChatExporter.App.Services;

public sealed record IndexStatusChangedEventArgs(
    Guid TableId,
    IndexStatus Status,
    DateTimeOffset? IndexedAt,
    string? Message);

/// <summary>
/// One SQLite database per managed table at indexes/&lt;tableGuid&gt;.db.
/// Values are stored in a normal indexed table plus an FTS5 virtual table;
/// searches fall back to LIKE when FTS5 is unavailable or the query syntax fails.
/// </summary>
public sealed class TableIndexService : ITableIndexService
{
    private static readonly Regex Whitespace = new(@"\s+", RegexOptions.Compiled);

    private readonly AppPaths _paths;
    private readonly ILogger _logger;

    public TableIndexService(AppPaths paths, ILogger logger)
    {
        _paths = paths;
        _logger = logger.ForContext<TableIndexService>();
    }

    public event EventHandler<IndexStatusChangedEventArgs>? StatusChanged;

    public bool IndexExists(Guid tableId) => File.Exists(_paths.GetIndexPath(tableId));

    public async Task BuildIndexAsync(
        ManagedTable table,
        string? sheetName,
        CancellationToken cancellationToken)
    {
        if (!File.Exists(table.FilePath))
        {
            Raise(table.Id, IndexStatus.Unavailable, null, "Excel 文件不存在，无法建立索引。");
            throw new FileNotFoundException("Excel 文件不存在。", table.FilePath);
        }

        _paths.EnsureDirectories();
        Raise(table.Id, IndexStatus.Building, null, null);

        var finalPath = _paths.GetIndexPath(table.Id);
        var tempPath = finalPath + $".tmp-{Guid.NewGuid():N}";
        var startedAt = DateTimeOffset.UtcNow;
        try
        {
            var cellCount = await Task.Run(
                () => WriteDatabase(tempPath, table.FilePath, sheetName, cancellationToken),
                cancellationToken).ConfigureAwait(false);

            SqliteConnection.ClearAllPools();
            ReplaceDatabase(tempPath, finalPath);

            var indexedAt = DateTimeOffset.UtcNow;
            Raise(table.Id, IndexStatus.Ready, indexedAt, null);
            _logger.Information(
                "Index built for table {TableId}: {CellCount} cells in {ElapsedMs} ms.",
                table.Id,
                cellCount,
                (indexedAt - startedAt).TotalMilliseconds);
        }
        catch (OperationCanceledException)
        {
            Raise(table.Id, IndexStatus.NotBuilt, null, "索引建立已取消。");
            throw;
        }
        catch (Exception ex)
        {
            Raise(table.Id, IndexStatus.Unavailable, null, "索引建立失败：" + ex.Message);
            _logger.Warning(ex, "Failed to build index for table {TableId}.", table.Id);
            throw;
        }
        finally
        {
            TryDelete(tempPath);
            TryDelete(tempPath + "-wal");
            TryDelete(tempPath + "-shm");
        }
    }

    public async Task<IReadOnlyList<TableIndexHit>> SearchAsync(
        Guid tableId,
        string query,
        string? columnName = null,
        int limit = 200,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(query))
        {
            return Array.Empty<TableIndexHit>();
        }

        var path = _paths.GetIndexPath(tableId);
        if (!File.Exists(path))
        {
            return Array.Empty<TableIndexHit>();
        }

        limit = Math.Clamp(limit, 1, 1000);
        try
        {
            return await Task.Run(
                () => SearchCore(path, tableId, query.Trim(), columnName, limit, cancellationToken),
                cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (SqliteException ex) when (IsCorruption(ex))
        {
            Raise(tableId, IndexStatus.Unavailable, null, "索引文件损坏，请重建索引。");
            _logger.Warning(ex, "Index file is corrupt for table {TableId}.", tableId);
            return Array.Empty<TableIndexHit>();
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "Index search failed for table {TableId}.", tableId);
            return Array.Empty<TableIndexHit>();
        }
    }

    public Task DeleteIndexAsync(Guid tableId, CancellationToken cancellationToken = default)
    {
        SqliteConnection.ClearAllPools();
        var path = _paths.GetIndexPath(tableId);
        foreach (var candidate in new[] { path, path + "-wal", path + "-shm" })
        {
            TryDelete(candidate);
        }

        Raise(tableId, IndexStatus.NotBuilt, null, null);
        return Task.CompletedTask;
    }

    private int WriteDatabase(
        string path,
        string workbookPath,
        string? sheetName,
        CancellationToken cancellationToken)
    {
        using var workbook = new XLWorkbook(workbookPath, new LoadOptions
        {
            RecalculateAllFormulas = false
        });

        IXLWorksheet worksheet;
        if (!string.IsNullOrWhiteSpace(sheetName))
        {
            if (!workbook.Worksheets.TryGetWorksheet(sheetName.Trim(), out worksheet!))
            {
                throw new InvalidOperationException($"工作表“{sheetName}”不存在。");
            }
        }
        else
        {
            worksheet = workbook.Worksheets.First();
        }

        var used = worksheet.RangeUsed();
        if (used is null)
        {
            return 0;
        }

        var firstRow = used.RangeAddress.FirstAddress.RowNumber;
        var lastRow = used.RangeAddress.LastAddress.RowNumber;
        var firstColumn = used.RangeAddress.FirstAddress.ColumnNumber;
        var lastColumn = used.RangeAddress.LastAddress.ColumnNumber;

        var headers = new string[lastColumn + 1];
        for (var column = firstColumn; column <= lastColumn; column++)
        {
            var header = worksheet.Cell(firstRow, column).GetString().Trim();
            headers[column] = string.IsNullOrWhiteSpace(header) ? $"列{column}" : header;
        }

        var connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Pooling = false
        }.ToString();

        using var connection = new SqliteConnection(connectionString);
        connection.Open();
        using (var pragma = connection.CreateCommand())
        {
            pragma.CommandText = "PRAGMA journal_mode=DELETE;";
            pragma.ExecuteNonQuery();
        }

        using var transaction = connection.BeginTransaction();
        Execute(connection, transaction, """
            CREATE TABLE rows (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                row_index INTEGER NOT NULL,
                column_name TEXT NOT NULL,
                value TEXT NOT NULL
            );
            """);
        Execute(connection, transaction, "CREATE INDEX ix_rows_value ON rows(value);");
        Execute(connection, transaction, "CREATE INDEX ix_rows_column ON rows(column_name, value);");

        var ftsAvailable = true;
        try
        {
            Execute(connection, transaction, """
                CREATE VIRTUAL TABLE rows_fts USING fts5(
                    value,
                    row_index UNINDEXED,
                    column_name UNINDEXED,
                    tokenize='unicode61'
                );
                """);
        }
        catch (SqliteException ex)
        {
            ftsAvailable = false;
            _logger.Warning(ex, "FTS5 is unavailable; index will use LIKE searches.");
        }

        using var insertRows = connection.CreateCommand();
        insertRows.Transaction = transaction;
        insertRows.CommandText =
            "INSERT INTO rows (row_index, column_name, value) VALUES ($row, $column, $value); RETURNING id;";
        var rowParam = insertRows.CreateParameter();
        rowParam.ParameterName = "$row";
        var columnParam = insertRows.CreateParameter();
        columnParam.ParameterName = "$column";
        var valueParam = insertRows.CreateParameter();
        valueParam.ParameterName = "$value";
        insertRows.Parameters.AddRange(new[] { rowParam, columnParam, valueParam });

        using var insertFts = connection.CreateCommand();
        insertFts.Transaction = transaction;
        insertFts.CommandText =
            "INSERT INTO rows_fts (rowid, value, row_index, column_name) VALUES ($id, $value, $row, $column);";
        var ftsId = insertFts.CreateParameter();
        ftsId.ParameterName = "$id";
        var ftsValue = insertFts.CreateParameter();
        ftsValue.ParameterName = "$value";
        var ftsRow = insertFts.CreateParameter();
        ftsRow.ParameterName = "$row";
        var ftsColumn = insertFts.CreateParameter();
        ftsColumn.ParameterName = "$column";
        insertFts.Parameters.AddRange(new[] { ftsId, ftsValue, ftsRow, ftsColumn });

        var cellCount = 0;
        for (var rowIndex = firstRow + 1; rowIndex <= lastRow; rowIndex++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            for (var column = firstColumn; column <= lastColumn; column++)
            {
                var value = worksheet.Cell(rowIndex, column).GetString();
                if (string.IsNullOrWhiteSpace(value))
                {
                    continue;
                }

                rowParam.Value = rowIndex;
                columnParam.Value = headers[column];
                valueParam.Value = value;
                var id = Convert.ToInt64(insertRows.ExecuteScalar());

                if (ftsAvailable)
                {
                    ftsId.Value = id;
                    ftsValue.Value = value;
                    ftsRow.Value = rowIndex;
                    ftsColumn.Value = headers[column];
                    insertFts.ExecuteNonQuery();
                }

                if (++cellCount % 500 == 0)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                }
            }
        }

        transaction.Commit();
        return cellCount;
    }

    private IReadOnlyList<TableIndexHit> SearchCore(
        string path,
        Guid tableId,
        string query,
        string? columnName,
        int limit,
        CancellationToken cancellationToken)
    {
        var connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode = SqliteOpenMode.ReadOnly,
            Pooling = false
        }.ToString();

        using var connection = new SqliteConnection(connectionString);
        connection.Open();

        var hasFts = TableExists(connection, "rows_fts");
        if (hasFts)
        {
            try
            {
                var hits = SearchFts(connection, BuildFtsQuery(query), columnName, limit, cancellationToken);
                return hits;
            }
            catch (SqliteException ex) when (!IsCorruption(ex))
            {
                _logger.Debug(ex, "FTS query failed; falling back to LIKE for table {TableId}.", tableId);
            }
        }

        return SearchLike(connection, query, columnName, limit, cancellationToken);
    }

    private static IReadOnlyList<TableIndexHit> SearchFts(
        SqliteConnection connection,
        string ftsQuery,
        string? columnName,
        int limit,
        CancellationToken cancellationToken)
    {
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT row_index, column_name, value
            FROM rows_fts
            WHERE rows_fts MATCH $query
            """ + (string.IsNullOrWhiteSpace(columnName) ? string.Empty : " AND column_name = $column") + """
            ORDER BY row_index
            LIMIT $limit;
            """;
        command.Parameters.AddWithValue("$query", ftsQuery);
        command.Parameters.AddWithValue("$limit", limit);
        if (!string.IsNullOrWhiteSpace(columnName))
        {
            command.Parameters.AddWithValue("$column", columnName.Trim());
        }

        var hits = new List<TableIndexHit>();
        using var reader = command.ExecuteReader();
        while (reader.Read())
        {
            cancellationToken.ThrowIfCancellationRequested();
            hits.Add(new TableIndexHit(reader.GetInt32(0), reader.GetString(1), reader.GetString(2)));
        }

        return hits;
    }

    private static IReadOnlyList<TableIndexHit> SearchLike(
        SqliteConnection connection,
        string query,
        string? columnName,
        int limit,
        CancellationToken cancellationToken)
    {
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT row_index, column_name, value
            FROM rows
            WHERE value LIKE $pattern ESCAPE '\'
            """ + (string.IsNullOrWhiteSpace(columnName) ? string.Empty : " AND column_name = $column") + """
            ORDER BY row_index
            LIMIT $limit;
            """;
        command.Parameters.AddWithValue("$pattern", "%" + EscapeLike(query) + "%");
        command.Parameters.AddWithValue("$limit", limit);
        if (!string.IsNullOrWhiteSpace(columnName))
        {
            command.Parameters.AddWithValue("$column", columnName.Trim());
        }

        var hits = new List<TableIndexHit>();
        using var reader = command.ExecuteReader();
        while (reader.Read())
        {
            cancellationToken.ThrowIfCancellationRequested();
            hits.Add(new TableIndexHit(reader.GetInt32(0), reader.GetString(1), reader.GetString(2)));
        }

        return hits;
    }

    private static string BuildFtsQuery(string query)
    {
        var tokens = Whitespace.Split(query)
            .Select(token => token.Trim())
            .Where(token => token.Length > 0)
            .Select(token => "\"" + token.Replace("\"", "\"\"") + "\"*");
        return string.Join(" AND ", tokens);
    }

    private static string EscapeLike(string value) =>
        value.Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_");

    private static bool TableExists(SqliteConnection connection, string name)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM sqlite_master WHERE type IN ('table','view') AND name = $name;";
        command.Parameters.AddWithValue("$name", name);
        return Convert.ToInt64(command.ExecuteScalar()) > 0;
    }

    private static bool IsCorruption(SqliteException exception)
    {
        return exception.SqliteErrorCode is 11 or 26;
    }

    private static void Execute(SqliteConnection connection, SqliteTransaction transaction, string sql)
    {
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = sql;
        command.ExecuteNonQuery();
    }

    private void ReplaceDatabase(string tempPath, string finalPath)
    {
        if (!File.Exists(finalPath))
        {
            File.Move(tempPath, finalPath);
            return;
        }

        try
        {
            File.Replace(tempPath, finalPath, destinationBackupFileName: null, ignoreMetadataErrors: true);
        }
        catch (Exception ex) when (ex is IOException or PlatformNotSupportedException or UnauthorizedAccessException)
        {
            File.Move(tempPath, finalPath, overwrite: true);
        }
    }

    private void Raise(Guid tableId, IndexStatus status, DateTimeOffset? indexedAt, string? message)
    {
        StatusChanged?.Invoke(this, new IndexStatusChangedEventArgs(tableId, status, indexedAt, message));
    }

    private void TryDelete(string path)
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
            _logger.Debug(ex, "Failed to remove temporary index file {Path}.", path);
        }
    }

}
