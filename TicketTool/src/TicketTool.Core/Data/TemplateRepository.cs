using System.Text.Json;
using Microsoft.Data.Sqlite;
using TicketTool.Core.Models;

namespace TicketTool.Core.Data;

/// <summary>模板、字段、规则与规则版本的持久化。</summary>
public sealed class TemplateRepository
{
    private readonly Database _database;

    /// <summary>创建仓储。</summary>
    public TemplateRepository(Database database) => _database = database;

    /// <summary>列出全部模板（不含字段）。</summary>
    public Task<List<Template>> ListAsync(CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            var result = new List<Template>();
            await using var command = connection.CreateCommand();
            command.CommandText = """
                SELECT id, name, file_path, header_row, created_at, updated_at
                FROM templates ORDER BY id;
                """;
            await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
            while (await reader.ReadAsync(token).ConfigureAwait(false))
            {
                result.Add(ReadTemplate(reader));
            }
            return result;
        }, cancellationToken);

    /// <summary>读取模板及字段、规则。</summary>
    public Task<Template?> GetAsync(long id, CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            Template? template = null;
            await using (var command = connection.CreateCommand())
            {
                command.CommandText = """
                    SELECT id, name, file_path, header_row, created_at, updated_at
                    FROM templates WHERE id = $id;
                    """;
                command.Parameters.AddWithValue("$id", id);
                await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
                if (await reader.ReadAsync(token).ConfigureAwait(false))
                {
                    template = ReadTemplate(reader);
                }
            }

            if (template is null)
            {
                return null;
            }

            template.Fields = await ReadFieldsAsync(connection, id, token).ConfigureAwait(false);
            var rules = await ReadRulesAsync(connection, id, token).ConfigureAwait(false);
            foreach (var field in template.Fields)
            {
                field.Rules = rules.Where(r => r.TemplateFieldId == field.Id).ToList();
            }
            return template;
        }, cancellationToken);

    /// <summary>创建模板与字段。</summary>
    public Task<long> CreateAsync(Template template, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            await using var transaction = await connection.BeginTransactionAsync(token).ConfigureAwait(false);
            var now = TimeUtil.ToDb(DateTimeOffset.Now);
            long templateId;
            await using (var command = connection.CreateCommand())
            {
                command.Transaction = (SqliteTransaction)transaction;
                command.CommandText = """
                    INSERT INTO templates(name, file_path, header_row, created_at, updated_at)
                    VALUES ($name, $path, $header, $created, $updated);
                    SELECT last_insert_rowid();
                    """;
                command.Parameters.AddWithValue("$name", template.Name);
                command.Parameters.AddWithValue("$path", template.FilePath);
                command.Parameters.AddWithValue("$header", template.HeaderRow);
                command.Parameters.AddWithValue("$created", now);
                command.Parameters.AddWithValue("$updated", now);
                templateId = Convert.ToInt64(await command.ExecuteScalarAsync(token).ConfigureAwait(false), System.Globalization.CultureInfo.InvariantCulture);
            }

            foreach (var field in template.Fields)
            {
                await InsertFieldAsync(connection, (SqliteTransaction)transaction, templateId, field, token).ConfigureAwait(false);
            }

            await transaction.CommitAsync(token).ConfigureAwait(false);
            return templateId;
        }, cancellationToken);

    /// <summary>更新模板信息与字段集合，按字段名保留既有字段主键。</summary>
    public Task UpdateAsync(Template template, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            await using var transaction = await connection.BeginTransactionAsync(token).ConfigureAwait(false);
            await using (var command = connection.CreateCommand())
            {
                command.Transaction = (SqliteTransaction)transaction;
                command.CommandText = """
                    UPDATE templates
                    SET name = $name, file_path = $path, header_row = $header, updated_at = $updated
                    WHERE id = $id;
                    """;
                command.Parameters.AddWithValue("$id", template.Id);
                command.Parameters.AddWithValue("$name", template.Name);
                command.Parameters.AddWithValue("$path", template.FilePath);
                command.Parameters.AddWithValue("$header", template.HeaderRow);
                command.Parameters.AddWithValue("$updated", TimeUtil.ToDb(DateTimeOffset.Now));
                await command.ExecuteNonQueryAsync(token).ConfigureAwait(false);
            }

            var existing = await ReadFieldsAsync(connection, template.Id, token, (SqliteTransaction)transaction).ConfigureAwait(false);
            var incomingNames = template.Fields.Select(f => f.FieldName).ToHashSet(StringComparer.Ordinal);
            foreach (var stale in existing.Where(f => !incomingNames.Contains(f.FieldName)))
            {
                await using var delete = connection.CreateCommand();
                delete.Transaction = (SqliteTransaction)transaction;
                delete.CommandText = "DELETE FROM template_fields WHERE id = $id;";
                delete.Parameters.AddWithValue("$id", stale.Id);
                await delete.ExecuteNonQueryAsync(token).ConfigureAwait(false);
            }

            foreach (var field in template.Fields)
            {
                var match = existing.FirstOrDefault(f => f.FieldName == field.FieldName);
                if (match is null)
                {
                    await InsertFieldAsync(connection, (SqliteTransaction)transaction, template.Id, field, token).ConfigureAwait(false);
                }
                else
                {
                    await using var update = connection.CreateCommand();
                    update.Transaction = (SqliteTransaction)transaction;
                    update.CommandText = """
                        UPDATE template_fields
                        SET column_index = $column, core_type = $core, sort_order = $sort
                        WHERE id = $id;
                        """;
                    update.Parameters.AddWithValue("$column", field.ColumnIndex);
                    update.Parameters.AddWithValue("$core", EnumText.ToDb(field.CoreType));
                    update.Parameters.AddWithValue("$sort", field.SortOrder);
                    update.Parameters.AddWithValue("$id", match.Id);
                    await update.ExecuteNonQueryAsync(token).ConfigureAwait(false);
                    field.Id = match.Id;
                    field.TemplateId = template.Id;
                }
            }

            await transaction.CommitAsync(token).ConfigureAwait(false);
        }, cancellationToken);

    /// <summary>删除模板及其字段、规则。</summary>
    public Task DeleteAsync(long templateId, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = "DELETE FROM templates WHERE id = $id;";
            command.Parameters.AddWithValue("$id", templateId);
            await command.ExecuteNonQueryAsync(token).ConfigureAwait(false);
        }, cancellationToken);

    /// <summary>新增字段。</summary>
    public Task<long> AddFieldAsync(long templateId, TemplateField field, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            var id = await InsertFieldAsync(connection, null, templateId, field, token).ConfigureAwait(false);
            return id;
        }, cancellationToken);

    /// <summary>新增规则。</summary>
    public Task<long> AddRuleAsync(FieldRule rule, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = """
                INSERT INTO field_rules(template_field_id, rule_type, config_json, priority, enabled, version)
                VALUES ($field, $type, $config, $priority, $enabled, $version);
                SELECT last_insert_rowid();
                """;
            command.Parameters.AddWithValue("$field", rule.TemplateFieldId);
            command.Parameters.AddWithValue("$type", EnumText.ToDb(rule.RuleType));
            command.Parameters.AddWithValue("$config", rule.ConfigJson);
            command.Parameters.AddWithValue("$priority", rule.Priority);
            command.Parameters.AddWithValue("$enabled", rule.Enabled ? 1 : 0);
            command.Parameters.AddWithValue("$version", rule.Version);
            return Convert.ToInt64(await command.ExecuteScalarAsync(token).ConfigureAwait(false), System.Globalization.CultureInfo.InvariantCulture);
        }, cancellationToken);

    /// <summary>更新规则。</summary>
    public Task UpdateRuleAsync(FieldRule rule, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = """
                UPDATE field_rules
                SET rule_type = $type, config_json = $config, priority = $priority,
                    enabled = $enabled, version = version + 1
                WHERE id = $id;
                """;
            command.Parameters.AddWithValue("$id", rule.Id);
            command.Parameters.AddWithValue("$type", EnumText.ToDb(rule.RuleType));
            command.Parameters.AddWithValue("$config", rule.ConfigJson);
            command.Parameters.AddWithValue("$priority", rule.Priority);
            command.Parameters.AddWithValue("$enabled", rule.Enabled ? 1 : 0);
            await command.ExecuteNonQueryAsync(token).ConfigureAwait(false);
        }, cancellationToken);

    /// <summary>删除规则。</summary>
    public Task DeleteRuleAsync(long ruleId, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = "DELETE FROM field_rules WHERE id = $id;";
            command.Parameters.AddWithValue("$id", ruleId);
            await command.ExecuteNonQueryAsync(token).ConfigureAwait(false);
        }, cancellationToken);

    /// <summary>替换某模板的全部规则（用于回退版本）。</summary>
    public Task ReplaceRulesAsync(long templateId, IReadOnlyList<RuleVersionField> fields, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            await using var transaction = await connection.BeginTransactionAsync(token).ConfigureAwait(false);
            var dbFields = await ReadFieldsAsync(connection, templateId, token, (SqliteTransaction)transaction).ConfigureAwait(false);
            foreach (var field in fields)
            {
                var target = dbFields.FirstOrDefault(f => f.FieldName == field.FieldName);
                if (target is null)
                {
                    continue;
                }

                await using (var delete = connection.CreateCommand())
                {
                    delete.Transaction = (SqliteTransaction)transaction;
                    delete.CommandText = "DELETE FROM field_rules WHERE template_field_id = $field;";
                    delete.Parameters.AddWithValue("$field", target.Id);
                    await delete.ExecuteNonQueryAsync(token).ConfigureAwait(false);
                }

                foreach (var rule in field.Rules)
                {
                    await using var insert = connection.CreateCommand();
                    insert.Transaction = (SqliteTransaction)transaction;
                    insert.CommandText = """
                        INSERT INTO field_rules(template_field_id, rule_type, config_json, priority, enabled, version)
                        VALUES ($field, $type, $config, $priority, $enabled, 1);
                        """;
                    insert.Parameters.AddWithValue("$field", target.Id);
                    insert.Parameters.AddWithValue("$type", EnumText.ToDb(rule.RuleType));
                    insert.Parameters.AddWithValue("$config", rule.ConfigJson);
                    insert.Parameters.AddWithValue("$priority", rule.Priority);
                    insert.Parameters.AddWithValue("$enabled", rule.Enabled ? 1 : 0);
                    await insert.ExecuteNonQueryAsync(token).ConfigureAwait(false);
                }
            }
            await transaction.CommitAsync(token).ConfigureAwait(false);
        }, cancellationToken);

    /// <summary>保存规则版本快照。</summary>
    public Task SaveRuleVersionAsync(long templateId, RuleVersionPayload payload, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            var bytes = JsonSerializer.SerializeToUtf8Bytes(payload, JsonDefaults.Options);
            await using var command = connection.CreateCommand();
            command.CommandText = """
                INSERT INTO rule_versions(template_id, payload_zstd, created_at)
                VALUES ($template, $payload, $created);
                """;
            command.Parameters.AddWithValue("$template", templateId);
            command.Parameters.AddWithValue("$payload", CompressionService.CompressBytes(bytes));
            command.Parameters.AddWithValue("$created", TimeUtil.ToDb(DateTimeOffset.Now));
            await command.ExecuteNonQueryAsync(token).ConfigureAwait(false);
        }, cancellationToken);

    /// <summary>列出规则版本。</summary>
    public Task<List<RuleVersionSummary>> ListRuleVersionsAsync(long templateId, CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            var result = new List<RuleVersionSummary>();
            await using var command = connection.CreateCommand();
            command.CommandText = """
                SELECT id, template_id, created_at FROM rule_versions
                WHERE template_id = $template ORDER BY id DESC;
                """;
            command.Parameters.AddWithValue("$template", templateId);
            await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
            while (await reader.ReadAsync(token).ConfigureAwait(false))
            {
                result.Add(new RuleVersionSummary
                {
                    Id = reader.GetInt64(0),
                    TemplateId = reader.GetInt64(1),
                    CreatedAt = TimeUtil.FromDb(reader.GetString(2))
                });
            }
            return result;
        }, cancellationToken);

    /// <summary>读取指定规则版本内容。</summary>
    public Task<RuleVersionPayload?> GetRuleVersionAsync(long versionId, CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            await using var command = connection.CreateCommand();
            command.CommandText = "SELECT payload_zstd FROM rule_versions WHERE id = $id;";
            command.Parameters.AddWithValue("$id", versionId);
            var value = await command.ExecuteScalarAsync(token).ConfigureAwait(false);
            if (value is not byte[] bytes)
            {
                return null;
            }
            var json = CompressionService.DecompressBytes(bytes);
            return JsonSerializer.Deserialize<RuleVersionPayload>(json, JsonDefaults.Options);
        }, cancellationToken);

    /// <summary>复制源模板的规则到目标模板，按字段名匹配。</summary>
    public Task<int> CopyRulesAsync(long sourceTemplateId, long targetTemplateId, CancellationToken cancellationToken = default)
        => _database.WriteAsync(async (connection, token) =>
        {
            var source = await ReadFieldsAsync(connection, sourceTemplateId, token).ConfigureAwait(false);
            var target = await ReadFieldsAsync(connection, targetTemplateId, token).ConfigureAwait(false);
            var sourceRules = await ReadRulesAsync(connection, sourceTemplateId, token).ConfigureAwait(false);
            var copied = 0;
            await using var transaction = await connection.BeginTransactionAsync(token).ConfigureAwait(false);
            foreach (var targetField in target)
            {
                var sourceField = source.FirstOrDefault(f => f.FieldName == targetField.FieldName);
                if (sourceField is null)
                {
                    continue;
                }

                await using (var delete = connection.CreateCommand())
                {
                    delete.Transaction = (SqliteTransaction)transaction;
                    delete.CommandText = "DELETE FROM field_rules WHERE template_field_id = $field;";
                    delete.Parameters.AddWithValue("$field", targetField.Id);
                    await delete.ExecuteNonQueryAsync(token).ConfigureAwait(false);
                }

                foreach (var rule in sourceRules.Where(r => r.TemplateFieldId == sourceField.Id))
                {
                    await using var insert = connection.CreateCommand();
                    insert.Transaction = (SqliteTransaction)transaction;
                    insert.CommandText = """
                        INSERT INTO field_rules(template_field_id, rule_type, config_json, priority, enabled, version)
                        VALUES ($field, $type, $config, $priority, $enabled, 1);
                        """;
                    insert.Parameters.AddWithValue("$field", targetField.Id);
                    insert.Parameters.AddWithValue("$type", EnumText.ToDb(rule.RuleType));
                    insert.Parameters.AddWithValue("$config", rule.ConfigJson);
                    insert.Parameters.AddWithValue("$priority", rule.Priority);
                    insert.Parameters.AddWithValue("$enabled", rule.Enabled ? 1 : 0);
                    await insert.ExecuteNonQueryAsync(token).ConfigureAwait(false);
                    copied++;
                }
            }
            await transaction.CommitAsync(token).ConfigureAwait(false);
            return copied;
        }, cancellationToken);

    private static Template ReadTemplate(SqliteDataReader reader) => new()
    {
        Id = reader.GetInt64(0),
        Name = reader.GetString(1),
        FilePath = reader.GetString(2),
        HeaderRow = reader.GetInt32(3),
        CreatedAt = TimeUtil.FromDb(reader.GetString(4)),
        UpdatedAt = TimeUtil.FromDb(reader.GetString(5))
    };

    private static async Task<List<TemplateField>> ReadFieldsAsync(
        SqliteConnection connection,
        long templateId,
        CancellationToken token,
        SqliteTransaction? transaction = null)
    {
        var result = new List<TemplateField>();
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            SELECT id, template_id, field_name, column_index, core_type, sort_order
            FROM template_fields WHERE template_id = $id ORDER BY sort_order, id;
            """;
        command.Parameters.AddWithValue("$id", templateId);
        await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
        while (await reader.ReadAsync(token).ConfigureAwait(false))
        {
            result.Add(new TemplateField
            {
                Id = reader.GetInt64(0),
                TemplateId = reader.GetInt64(1),
                FieldName = reader.GetString(2),
                ColumnIndex = reader.GetInt32(3),
                CoreType = EnumText.CoreFromDb(reader.IsDBNull(4) ? null : reader.GetString(4)),
                SortOrder = reader.GetInt32(5)
            });
        }
        return result;
    }

    private static async Task<List<FieldRule>> ReadRulesAsync(
        SqliteConnection connection,
        long templateId,
        CancellationToken token)
    {
        var result = new List<FieldRule>();
        await using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT r.id, r.template_field_id, r.rule_type, r.config_json, r.priority, r.enabled, r.version
            FROM field_rules r
            JOIN template_fields f ON f.id = r.template_field_id
            WHERE f.template_id = $template
            ORDER BY r.priority DESC, r.id;
            """;
        command.Parameters.AddWithValue("$template", templateId);
        await using var reader = await command.ExecuteReaderAsync(token).ConfigureAwait(false);
        while (await reader.ReadAsync(token).ConfigureAwait(false))
        {
            result.Add(new FieldRule
            {
                Id = reader.GetInt64(0),
                TemplateFieldId = reader.GetInt64(1),
                RuleType = EnumText.RuleFromDb(reader.GetString(2)),
                ConfigJson = reader.GetString(3),
                Priority = reader.GetInt32(4),
                Enabled = reader.GetInt32(5) != 0,
                Version = reader.GetInt32(6)
            });
        }
        return result;
    }

    private static async Task<long> InsertFieldAsync(
        SqliteConnection connection,
        SqliteTransaction? transaction,
        long templateId,
        TemplateField field,
        CancellationToken token)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            INSERT INTO template_fields(template_id, field_name, column_index, core_type, sort_order)
            VALUES ($template, $name, $column, $core, $sort);
            SELECT last_insert_rowid();
            """;
        command.Parameters.AddWithValue("$template", templateId);
        command.Parameters.AddWithValue("$name", field.FieldName);
        command.Parameters.AddWithValue("$column", field.ColumnIndex);
        command.Parameters.AddWithValue("$core", EnumText.ToDb(field.CoreType));
        command.Parameters.AddWithValue("$sort", field.SortOrder);
        var id = Convert.ToInt64(await command.ExecuteScalarAsync(token).ConfigureAwait(false), System.Globalization.CultureInfo.InvariantCulture);
        field.Id = id;
        field.TemplateId = templateId;
        return id;
    }
}
