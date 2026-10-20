namespace DingTalkChatExporter.Core.Management;

public enum IndexStatus
{
    NotBuilt,
    Building,
    Ready,
    Stale,
    Unavailable
}

public sealed record ManagedTable
{
    public Guid Id { get; init; } = Guid.NewGuid();
    public string DisplayName { get; init; } = string.Empty;
    public string FilePath { get; init; } = string.Empty;
    public bool IsActive { get; init; }
    public string? BoundRuleLibraryId { get; init; }
    public IndexStatus IndexStatus { get; init; } = IndexStatus.NotBuilt;
    public DateTimeOffset? IndexedAt { get; init; }
    public DateTimeOffset AddedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset? LastUsedAt { get; init; }
}
