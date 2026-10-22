using Microsoft.Data.Sqlite;

namespace TicketTool.Core.Data;

/// <summary>负责首次建库、结构迁移与版本记录。</summary>
public sealed class DatabaseInitializer
{
    private const int CurrentSchemaVersion = 1;
    private readonly Database _database;

    /// <summary>创建初始化器。</summary>
    public DatabaseInitializer(Database database) => _database = database;

    /// <summary>建库并执行迁移。</summary>
    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        await _database.WriteAsync(async (connection, token) =>
        {
            await using (var command = connection.CreateCommand())
            {
                command.CommandText = SchemaSql;
                await command.ExecuteNonQueryAsync(token).ConfigureAwait(false);
            }

            var version = await GetSchemaVersionAsync(connection, token).ConfigureAwait(false);
            if (version < CurrentSchemaVersion)
            {
                await using var update = connection.CreateCommand();
                update.CommandText = """
                    INSERT INTO app_config(k, v) VALUES ('schema_version', $version)
                    ON CONFLICT(k) DO UPDATE SET v = excluded.v;
                    """;
                update.Parameters.AddWithValue("$version", CurrentSchemaVersion.ToString(System.Globalization.CultureInfo.InvariantCulture));
                await update.ExecuteNonQueryAsync(token).ConfigureAwait(false);
            }
        }, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>读取当前库结构版本。</summary>
    public Task<int> GetSchemaVersionAsync(CancellationToken cancellationToken = default)
        => _database.ReadAsync(async (connection, token) =>
        {
            var version = await GetSchemaVersionAsync(connection, token).ConfigureAwait(false);
            return version <= 0 ? CurrentSchemaVersion : version;
        }, cancellationToken);

    private static async Task<int> GetSchemaVersionAsync(SqliteConnection connection, CancellationToken token)
    {
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT v FROM app_config WHERE k = 'schema_version' LIMIT 1;";
        var result = await command.ExecuteScalarAsync(token).ConfigureAwait(false);
        return result is string text && int.TryParse(text, out var version) ? version : 0;
    }

    private const string SchemaSql = """
        PRAGMA journal_mode = WAL;
        PRAGMA synchronous = NORMAL;
        PRAGMA foreign_keys = ON;

        CREATE TABLE IF NOT EXISTS app_config (
            k TEXT PRIMARY KEY,
            v TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS templates (
            id          INTEGER PRIMARY KEY,
            name        TEXT NOT NULL UNIQUE,
            file_path   TEXT NOT NULL,
            header_row  INTEGER NOT NULL DEFAULT 1,
            created_at  TEXT NOT NULL,
            updated_at  TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS template_fields (
            id           INTEGER PRIMARY KEY,
            template_id  INTEGER NOT NULL REFERENCES templates(id) ON DELETE CASCADE,
            field_name   TEXT NOT NULL,
            column_index INTEGER NOT NULL,
            core_type    TEXT,
            sort_order   INTEGER NOT NULL,
            UNIQUE(template_id, field_name)
        );

        CREATE TABLE IF NOT EXISTS field_rules (
            id                INTEGER PRIMARY KEY,
            template_field_id INTEGER NOT NULL REFERENCES template_fields(id) ON DELETE CASCADE,
            rule_type         TEXT NOT NULL,
            config_json       TEXT NOT NULL,
            priority          INTEGER NOT NULL DEFAULT 0,
            enabled           INTEGER NOT NULL DEFAULT 1,
            version           INTEGER NOT NULL DEFAULT 1
        );

        CREATE TABLE IF NOT EXISTS rule_versions (
            id           INTEGER PRIMARY KEY,
            template_id  INTEGER NOT NULL,
            payload_zstd BLOB NOT NULL,
            created_at   TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS tickets (
            id            INTEGER PRIMARY KEY,
            template_id   INTEGER NOT NULL REFERENCES templates(id),
            data_json     TEXT NOT NULL,
            raw_text_zstd BLOB NOT NULL,
            created_at    TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_tickets_tpl_time ON tickets(template_id, created_at);

        CREATE TABLE IF NOT EXISTS field_spans (
            ticket_id  INTEGER NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
            field_name TEXT NOT NULL,
            start_off  INTEGER NOT NULL,
            end_off    INTEGER NOT NULL,
            PRIMARY KEY(ticket_id, field_name)
        );

        CREATE TABLE IF NOT EXISTS sync_queue (
            id         INTEGER PRIMARY KEY,
            ticket_id  INTEGER NOT NULL REFERENCES tickets(id),
            status     TEXT NOT NULL,
            retry      INTEGER NOT NULL DEFAULT 0,
            last_error TEXT,
            updated_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_sync_status ON sync_queue(status);

        CREATE TABLE IF NOT EXISTS app_snapshots (
            id           INTEGER PRIMARY KEY,
            payload_zstd BLOB NOT NULL,
            clean_exit   INTEGER NOT NULL DEFAULT 0,
            created_at   TEXT NOT NULL
        );
        """;
}
