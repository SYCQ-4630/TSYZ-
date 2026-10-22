using System.Text.Json;
using Microsoft.Data.Sqlite;
using TicketTool.Core.Models;
using TicketTool.Core.Parsing;

namespace TicketTool.Core.Data;

/// <summary>工单、原文与字段溯源区间的持久化。</summary>
public sealed class TicketRepository
{
    private readonly Database _database;

    /// <summary>创建仓储。</summary>
    public TicketRepository(Database database) => _database = database;

    /// <summary>在单个事务中写入工单、溯源区间与同步队列。</summary>
    public Task<long> InsertAsync(TicketInsert ticket, Template template, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            await using var transaction = await connection.BeginTransactionAsync(token).ConfigureAwait(false);
            var createdAt = TimeUtil.ToDb(ticket.CreatedAt);
            var data = BuildDataJson(ticket, template);

            long ticketId;
            await using (var command = connection.CreateCommand())
            {
                command.Transaction = (SqliteTransaction)transaction;
                command.CommandText = """
                    INSERT INTO tickets(template_id, data_json, raw_text_zstd, created_at)
                    VALUES ($template, $data, $raw, $created);
                    SELECT last_insert_rowid();
                    """;
                command.Parameters.AddWithValue("$template", ticket.TemplateId);
                command.Parameters.AddWithValue("$data", data);
                command.Parameters.AddWithValue("$raw", CompressionService.CompressText(ticket.RawText));
                command.Parameters.AddWithValue("$created", createdAt);
                ticketId = Convert.ToInt64(await command.ExecuteScalarAsync(token).ConfigureAwait(false), System.Globalization.CultureInfo.InvariantCulture);
            }

            foreach (var span in ticket.Spans)
            {
                await using var spanCommand = connection.CreateCommand();
                spanCommand.Transaction = (SqliteTransaction)transaction;
                spanCommand.CommandText = """
                    INSERT INTO field_spans(ticket_id, field_name, start_off, end_off)
                    VALUES ($ticket, $field, $start, $end);
                    """;
                spanCommand.Parameters.AddWithValue("$ticket", ticketId);
                spanCommand.Parameters.AddWithValue("$field", span.FieldName);
                spanCommand.Parameters.AddWithValue("$start", span.StartOffset);
                spanCommand.Parameters.AddWithValue("$end", span.EndOffset);
                await spanCommand.ExecuteNonQueryAsync(token).ConfigureAwait(false);
            }

            await using (var queueCommand = connection.CreateCommand())
            {
                queueCommand.Transaction = (SqliteTransaction)transaction;
                queueCommand.CommandText = """
                    INSERT INTO sync_queue(ticket_id, status, retry, updated_at)
                    VALUES ($ticket, 'pending', 0, $updated);
                    """;
                queueCommand.Parameters.AddWithValue("$ticket", ticketId);
                queueCommand.Parameters.AddWithValue("$updated", TimeUtil.ToDb(DateTimeOffset.Now));
                await queueCommand.ExecuteNonQueryAsync(token).ConfigureAwait(false);
            }

            await transaction.CommitAsync(token).ConfigureAwait(false);
            return ticketId;
        }, cancellationToken);

    /// <summary>统计模板下的工单数量。</summary>
    public Task<long> CountByTemplateAsync(long templateId, CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = "SELECT COUNT(*) FROM tickets WHERE template_id = $template;";
            command.Parameters.AddWithValue("$template", templateId);
            var result = await command.ExecuteScalarAsync(token).ConfigureAwait(false);
            return Convert.ToInt64(result, System.Globalization.CultureInfo.InvariantCulture);
        }, cancellationToken);

    /// <summary>读取工单字段值，用于同步。</summary>
    public Task<(long TemplateId, string DataJson)?> GetDataAsync(long ticketId, CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = "SELECT template_id, data_json FROM tickets WHERE id = $id;";
            command.Parameters.AddWithValue("$id", ticketId);
            await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
            if (!await reader.ReadAsync(token).ConfigureAwait(false))
            {
                return ((long, string)?)null;
            }
            return (reader.GetInt64(0), reader.GetString(1));
        }, cancellationToken);

    /// <summary>读取工单原文。</summary>
    public Task<(string RawText, List<FieldSpan> Spans)?> GetRawAsync(long ticketId, CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            byte[]? payload = null;
            await using (var command = connection.CreateCommand())
            {
                command.CommandText = "SELECT raw_text_zstd FROM tickets WHERE id = $id;";
                command.Parameters.AddWithValue("$id", ticketId);
                payload = await command.ExecuteScalarAsync(token).ConfigureAwait(false) as byte[];
            }

            if (payload is null)
            {
                return ((string RawText, List<FieldSpan> Spans)?)null;
            }

            var spans = new List<FieldSpan>();
            await using (var command = connection.CreateCommand())
            {
                command.CommandText = "SELECT field_name, start_off, end_off FROM field_spans WHERE ticket_id = $id;";
                command.Parameters.AddWithValue("$id", ticketId);
                await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
                while (await reader.ReadAsync(token).ConfigureAwait(false))
                {
                    spans.Add(new FieldSpan
                    {
                        FieldName = reader.GetString(0),
                        StartOffset = reader.GetInt32(1),
                        EndOffset = reader.GetInt32(2)
                    });
                }
            }

            return (CompressionService.DecompressText(payload), spans);
        }, cancellationToken);

    /// <summary>读取某模板的工单字段值，用于导出。</summary>
    public Task<List<(long Id, string DataJson)>> ListForExportAsync(
        long templateId,
        int limit,
        CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            var result = new List<(long, string)>();
            await using var command = connection.CreateCommand();
            command.CommandText = """
                SELECT id, data_json FROM tickets
                WHERE template_id = $template ORDER BY id LIMIT $limit;
                """;
            command.Parameters.AddWithValue("$template", templateId);
            command.Parameters.AddWithValue("$limit", Math.Clamp(limit, 1, 500000));
            await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
            while (await reader.ReadAsync(token).ConfigureAwait(false))
            {
                result.Add((reader.GetInt64(0), reader.GetString(1)));
            }
            return result;
        }, cancellationToken);

    /// <summary>生成写入 data_json 的对象，并附加三个核心类型的规范化值。</summary>
    public static string BuildDataJson(TicketInsert ticket, Template template)
    {
        var data = new Dictionary<string, string?>(StringComparer.Ordinal);
        foreach (var field in template.Fields.OrderBy(f => f.SortOrder).ThenBy(f => f.Id))
        {
            data[field.FieldName] = ticket.Values.TryGetValue(field.FieldName, out var value) ? value : null;
            if (field.CoreType != CoreFieldType.None)
            {
                data[TextNormalizer.CoreKey(field.CoreType, field.Id)] =
                    TextNormalizer.Normalize(field.CoreType, data[field.FieldName]);
            }
        }

        return JsonSerializer.Serialize(data, JsonDefaults.Options);
    }

    /// <summary>将字段值字典序列化，供预览等场景使用。</summary>
    public static Dictionary<string, string?> ParseDataJson(string json)
    {
        try
        {
            return JsonSerializer.Deserialize<Dictionary<string, string?>>(json, JsonDefaults.Options)
                   ?? new Dictionary<string, string?>();
        }
        catch (JsonException)
        {
            return new Dictionary<string, string?>();
        }
    }
}
