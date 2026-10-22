using Microsoft.Data.Sqlite;
using Serilog;
using TicketTool.Core.Data;
using TicketTool.Core.Models;
using TicketTool.Core.Parsing;

namespace TicketTool.Core.Templates;

/// <summary>为三个核心字段维护 SQLite 表达式索引。</summary>
public sealed class ExpressionIndexManager
{
    private readonly Database _database;
    private readonly ILogger _logger;

    /// <summary>创建管理器。</summary>
    public ExpressionIndexManager(Database database, ILogger? logger = null)
    {
        _database = database;
        _logger = logger ?? Log.ForContext<ExpressionIndexManager>();
    }

    /// <summary>索引名。</summary>
    public static string IndexName(long templateId, long fieldId)
        => $"ix_core_{templateId.ToString(System.Globalization.CultureInfo.InvariantCulture)}_{fieldId.ToString(System.Globalization.CultureInfo.InvariantCulture)}";

    /// <summary>查询使用的表达式，必须与索引表达式完全一致。</summary>
    public static string BuildExpression(TemplateField field)
    {
        var key = TextNormalizer.CoreKey(field.CoreType, field.Id);
        var path = TextNormalizer.ToJsonPathKey(key).Replace("'", "''", StringComparison.Ordinal);
        return $"json_extract(data_json, '{path}')";
    }

    /// <summary>按模板当前字段重建/清理核心字段索引。</summary>
    public async Task EnsureForTemplateAsync(long templateId, CancellationToken cancellationToken = default)
    {
        var template = await new TemplateRepository(_database).GetAsync(templateId, cancellationToken).ConfigureAwait(false);
        if (template is null)
        {
            return;
        }
        await EnsureForTemplateAsync(template, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>按模板当前字段重建/清理核心字段索引。</summary>
    public async Task EnsureForTemplateAsync(Template template, CancellationToken cancellationToken = default)
    {
        await _database.WriteAsync(async (connection, token) =>
        {
            var prefix = $"ix_core_{template.Id}_";
            var existing = new List<string>();
            await using (var listCommand = connection.CreateCommand())
            {
                listCommand.CommandText = "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'tickets';";
                await using var reader = await listCommand.ExecuteReaderAsync(token).ConfigureAwait(false);
                while (await reader.ReadAsync(token).ConfigureAwait(false))
                {
                    var name = reader.GetString(0);
                    if (name.StartsWith(prefix, StringComparison.Ordinal))
                    {
                        existing.Add(name);
                    }
                }
            }

            var desired = template.Fields
                .Where(f => f.CoreType != CoreFieldType.None)
                .ToDictionary(f => IndexName(template.Id, f.Id), f => f);

            foreach (var stale in existing.Where(name => !desired.ContainsKey(name)))
            {
                await using var drop = connection.CreateCommand();
                drop.CommandText = $"DROP INDEX IF EXISTS \"{stale}\";";
                await drop.ExecuteNonQueryAsync(token).ConfigureAwait(false);
                _logger.Information("CoreIndexDropped {Index}", stale);
            }

            foreach (var (name, field) in desired)
            {
                await using var create = connection.CreateCommand();
                create.CommandText = $"""
                    CREATE INDEX IF NOT EXISTS "{name}"
                    ON tickets(template_id, {BuildExpression(field)});
                    """;
                await create.ExecuteNonQueryAsync(token).ConfigureAwait(false);
                _logger.Information("CoreIndexEnsured {Index} {FieldId} {CoreType}", name, field.Id, field.CoreType);
            }
        }, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>删除某模板的全部核心索引。</summary>
    public Task DropForTemplateAsync(long templateId, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            var prefix = $"ix_core_{templateId}_";
            var names = new List<string>();
            await using (var listCommand = connection.CreateCommand())
            {
                listCommand.CommandText = "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'tickets';";
                await using var reader = await listCommand.ExecuteReaderAsync(token).ConfigureAwait(false);
                while (await reader.ReadAsync(token).ConfigureAwait(false))
                {
                    if (reader.GetString(0).StartsWith(prefix, StringComparison.Ordinal))
                    {
                        names.Add(reader.GetString(0));
                    }
                }
            }

            foreach (var name in names)
            {
                await using var drop = connection.CreateCommand();
                drop.CommandText = $"DROP INDEX IF EXISTS \"{name}\";";
                await drop.ExecuteNonQueryAsync(token).ConfigureAwait(false);
            }
        }, cancellationToken);
}
