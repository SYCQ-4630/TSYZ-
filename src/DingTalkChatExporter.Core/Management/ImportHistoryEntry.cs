namespace DingTalkChatExporter.Core.Management;

public sealed record ImportHistoryEntry
{
    public Guid Id { get; init; } = Guid.NewGuid();
    public Guid? TableId { get; init; }
    public DateTimeOffset ImportedAt { get; init; } = DateTimeOffset.UtcNow;
    public string FilePath { get; init; } = string.Empty;
    public string DisplayName { get; init; } = string.Empty;
    public string? BoundRuleLibraryId { get; init; }
}
