namespace DingTalkChatExporter.Core.Models;

/// <summary>User-facing availability snapshot for any message source.</summary>
public sealed record SourceStatus(
    string SourceName,
    bool IsAvailable,
    string Summary,
    string? Detail = null,
    DateTimeOffset CheckedAt = default)
{
    public DateTimeOffset CheckedAt { get; init; } = CheckedAt == default ? DateTimeOffset.UtcNow : CheckedAt;
}
