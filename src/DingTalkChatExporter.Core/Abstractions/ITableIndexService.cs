using DingTalkChatExporter.Core.Management;

namespace DingTalkChatExporter.Core.Abstractions;

public sealed record TableIndexHit(int RowIndex, string ColumnName, string Value);

public interface ITableIndexService
{
    Task BuildIndexAsync(ManagedTable table, string? sheetName, CancellationToken cancellationToken);

    Task<IReadOnlyList<TableIndexHit>> SearchAsync(
        Guid tableId,
        string query,
        string? columnName = null,
        int limit = 200,
        CancellationToken cancellationToken = default);

    Task DeleteIndexAsync(Guid tableId, CancellationToken cancellationToken = default);

    bool IndexExists(Guid tableId);
}
