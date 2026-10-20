namespace DingTalkChatExporter.Core.Management;

/// <summary>
/// A table removed from the active list. The Excel file and local index are retained;
/// the entry exists so the user can restore the table without searching the disk.
/// </summary>
public sealed record RemovedTableEntry
{
    public Guid Id { get; init; } = Guid.NewGuid();
    public Guid TableId { get; init; }
    public string DisplayName { get; init; } = string.Empty;
    public string FilePath { get; init; } = string.Empty;
    public DateTimeOffset RemovedAt { get; init; } = DateTimeOffset.UtcNow;
    public string? BoundRuleLibraryId { get; init; }
}
