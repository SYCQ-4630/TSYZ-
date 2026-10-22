using System.Diagnostics;
using System.Text;
using Microsoft.Data.Sqlite;
using Serilog;
using TicketTool.Core.Data;
using TicketTool.Core.Models;
using TicketTool.Core.Templates;

namespace TicketTool.Core.Search;

/// <summary>
/// 三个核心字段的精确搜索。每个字段使用独立表达式索引，SQL 全部参数化。
/// </summary>
public sealed class SearchService
{
    private readonly Database _database;
    private readonly TemplateRepository _templates;
    private readonly ILogger _logger;

    /// <summary>创建搜索服务。</summary>
    public SearchService(Database database, TemplateRepository templates, ILogger? logger = null)
    {
        _database = database;
        _templates = templates;
        _logger = logger ?? Log.ForContext<SearchService>();
    }

    /// <summary>执行搜索。</summary>
    public async Task<TicketSearchPage> SearchAsync(TicketSearchRequest request, CancellationToken cancellationToken = default)
    {
        var stopwatch = Stopwatch.StartNew();
        var classification = QueryClassifier.Classify(request.Query, request.Domain);
        if (classification.NormalizedValue.Length == 0)
        {
            return new TicketSearchPage { EffectiveDomain = classification.Domain, Elapsed = stopwatch.Elapsed };
        }

        var templates = await LoadTemplatesAsync(request.TemplateId, cancellationToken).ConfigureAwait(false);
        var fields = templates
            .SelectMany(template => template.Fields
                .Where(field => field.CoreType != CoreFieldType.None
                                && ToDomain(field.CoreType) == classification.Domain)
                .Select(field => (Template: template, Field: field)))
            .ToList();

        if (fields.Count == 0)
        {
            return new TicketSearchPage { EffectiveDomain = classification.Domain, Elapsed = stopwatch.Elapsed };
        }

        var pageSize = Math.Clamp(request.PageSize, 1, 200);
        var pageIndex = Math.Max(0, request.PageIndex);
        var result = await _database.ReadAsync(async (connection, token) =>
        {
            var (sql, parameters) = BuildSearchSql(fields, classification.NormalizedValue, pageSize, pageIndex);
            var items = new List<TicketSearchResult>();
            await using (var command = connection.CreateCommand())
            {
                command.CommandText = sql;
                foreach (var parameter in parameters)
                {
                    command.Parameters.AddWithValue(parameter.Key, parameter.Value);
                }
                await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
                while (await reader.ReadAsync(token).ConfigureAwait(false))
                {
                    var dataJson = reader.GetString(3);
                    var fieldName = reader.GetString(4);
                    var values = TicketRepository.ParseDataJson(dataJson);
                    items.Add(new TicketSearchResult
                    {
                        TicketId = reader.GetInt64(0),
                        TemplateId = reader.GetInt64(1),
                        CreatedAt = TimeUtil.FromDb(reader.GetString(2)),
                        DisplayValue = values.TryGetValue(fieldName, out var value) ? value ?? string.Empty : string.Empty,
                        FieldName = fieldName,
                        TemplateName = templates.FirstOrDefault(t => t.Id == reader.GetInt64(1))?.Name ?? string.Empty
                    });
                }
            }

            var countSql = BuildCountSql(fields, classification.NormalizedValue, parameters);
            await using var countCommand = connection.CreateCommand();
            countCommand.CommandText = countSql;
            foreach (var parameter in parameters.Where(p => !p.Key.StartsWith("$limit", StringComparison.Ordinal) && !p.Key.StartsWith("$offset", StringComparison.Ordinal)))
            {
                countCommand.Parameters.AddWithValue(parameter.Key, parameter.Value);
            }
            var total = Convert.ToInt32(await countCommand.ExecuteScalarAsync(token).ConfigureAwait(false), System.Globalization.CultureInfo.InvariantCulture);
            return (Items: items, Total: total);
        }, cancellationToken).ConfigureAwait(false);

        stopwatch.Stop();
        _logger.Information(
            "SearchCompleted {Domain} {KeywordType} {Hits} {ElapsedMs}",
            classification.Domain,
            classification.Domain,
            result.Total,
            stopwatch.Elapsed.TotalMilliseconds);

        return new TicketSearchPage
        {
            Items = result.Items,
            TotalCount = result.Total,
            EffectiveDomain = classification.Domain,
            Elapsed = stopwatch.Elapsed
        };
    }

    private async Task<List<Template>> LoadTemplatesAsync(long? templateId, CancellationToken cancellationToken)
    {
        if (templateId is not null)
        {
            var template = await _templates.GetAsync(templateId.Value, cancellationToken).ConfigureAwait(false);
            return template is null ? new List<Template>() : new List<Template> { template };
        }

        var list = await _templates.ListAsync(cancellationToken).ConfigureAwait(false);
        var result = new List<Template>(list.Count);
        foreach (var item in list)
        {
            var full = await _templates.GetAsync(item.Id, cancellationToken).ConfigureAwait(false);
            if (full is not null)
            {
                result.Add(full);
            }
        }
        return result;
    }

    private static (string Sql, Dictionary<string, object> Parameters) BuildSearchSql(
        List<(Template Template, TemplateField Field)> fields,
        string normalized,
        int pageSize,
        int pageIndex)
    {
        var (arms, parameters) = BuildArms(fields, normalized);
        var sql = new StringBuilder();
        sql.AppendLine("SELECT ticket_id, template_id, created_at, data_json, field_name FROM (");
        sql.AppendLine(string.Join("\nUNION ALL\n", arms));
        sql.AppendLine(") ORDER BY created_at DESC, ticket_id DESC LIMIT $limit OFFSET $offset;");
        parameters["$limit"] = pageSize;
        parameters["$offset"] = pageIndex * pageSize;
        return (sql.ToString(), parameters);
    }

    private static string BuildCountSql(
        List<(Template Template, TemplateField Field)> fields,
        string normalized,
        Dictionary<string, object> existing)
    {
        var (arms, parameters) = BuildArms(fields, normalized);
        _ = existing;
        var sql = new StringBuilder();
        sql.AppendLine("SELECT COUNT(*) FROM (");
        sql.AppendLine(string.Join("\nUNION ALL\n", arms));
        sql.AppendLine(");");
        foreach (var parameter in parameters)
        {
            _ = parameter;
        }
        return sql.ToString();
    }

    private static (List<string> Arms, Dictionary<string, object> Parameters) BuildArms(
        List<(Template Template, TemplateField Field)> fields,
        string normalized)
    {
        var arms = new List<string>(fields.Count);
        var parameters = new Dictionary<string, object>(StringComparer.Ordinal) { ["$q"] = normalized };
        for (var i = 0; i < fields.Count; i++)
        {
            var (template, field) = fields[i];
            var expression = ExpressionIndexManager.BuildExpression(field);
            var templateParameter = "$tpl" + i.ToString(System.Globalization.CultureInfo.InvariantCulture);
            var fieldParameter = "$fn" + i.ToString(System.Globalization.CultureInfo.InvariantCulture);
            parameters[templateParameter] = template.Id;
            parameters[fieldParameter] = field.FieldName;
            arms.Add($"""
                SELECT id AS ticket_id, template_id, created_at, data_json, {fieldParameter} AS field_name
                FROM tickets
                WHERE template_id = {templateParameter} AND {expression} = $q
                """);
        }
        return (arms, parameters);
    }

    private static SearchDomain ToDomain(CoreFieldType type) => type switch
    {
        CoreFieldType.TicketNo => SearchDomain.TicketNo,
        CoreFieldType.Phone => SearchDomain.Phone,
        _ => SearchDomain.Address
    };
}
