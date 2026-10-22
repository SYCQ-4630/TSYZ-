using Microsoft.Data.Sqlite;
using Serilog;
using TicketTool.Core.Models;

namespace TicketTool.Core.Data;

/// <summary>SQLite 备份与完整性检查。禁止直接复制 .db 文件。</summary>
public sealed class BackupService
{
    private readonly Database _database;
    private readonly AppPaths _paths;
    private readonly ILogger _logger;

    /// <summary>创建服务。</summary>
    public BackupService(Database database, AppPaths paths, ILogger? logger = null)
    {
        _database = database;
        _paths = paths;
        _logger = logger ?? Log.ForContext<BackupService>();
    }

    /// <summary>执行 VACUUM INTO 备份。</summary>
    public async Task<string> BackupAsync(CancellationToken cancellationToken = default)
    {
        Directory.CreateDirectory(_paths.BackupsDirectory);
        var fileName = $"ticket-{DateTime.Now:yyyyMMdd-HHmmss}.db";
        var target = Path.GetFullPath(Path.Combine(_paths.BackupsDirectory, fileName));
        await _database.WriteAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = "VACUUM INTO $path;";
            command.Parameters.AddWithValue("$path", target);
            await command.ExecuteNonQueryAsync(token).ConfigureAwait(false);
        }, cancellationToken).ConfigureAwait(false);

        _logger.Information("DatabaseBackup {Path}", target);
        CleanupOldBackups();
        return target;
    }

    /// <summary>当天尚未备份时执行一次。</summary>
    public async Task<string?> EnsureDailyBackupAsync(CancellationToken cancellationToken = default)
    {
        var today = DateTime.Now.ToString("yyyyMMdd", System.Globalization.CultureInfo.InvariantCulture);
        if (Directory.EnumerateFiles(_paths.BackupsDirectory, $"ticket-{today}-*.db").Any())
        {
            return null;
        }
        return await BackupAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>执行 PRAGMA quick_check。</summary>
    public Task<IntegrityCheckResult> QuickCheckAsync(CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = "PRAGMA quick_check;";
            await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
            var messages = new List<string>();
            while (await reader.ReadAsync(token).ConfigureAwait(false))
            {
                messages.Add(reader.GetString(0));
            }

            var ok = messages.Count == 1 && string.Equals(messages[0], "ok", StringComparison.OrdinalIgnoreCase);
            var result = new IntegrityCheckResult { Ok = ok, Message = string.Join("；", messages) };
            if (!ok)
            {
                _logger.Error("QuickCheckFailed {Message}", result.Message);
            }
            return result;
        }, cancellationToken);

    /// <summary>列出备份文件。</summary>
    public IReadOnlyList<FileInfo> ListBackups()
        => Directory.Exists(_paths.BackupsDirectory)
            ? Directory.EnumerateFiles(_paths.BackupsDirectory, "*.db")
                .Select(path => new FileInfo(path))
                .OrderByDescending(file => file.LastWriteTimeUtc)
                .ToList()
            : Array.Empty<FileInfo>();

    private void CleanupOldBackups()
    {
        var files = ListBackups().Skip(30).ToList();
        foreach (var file in files)
        {
            try
            {
                file.Delete();
            }
            catch (IOException ex)
            {
                _logger.Warning(ex, "BackupCleanupFailed {Path}", file.FullName);
            }
        }
    }
}
