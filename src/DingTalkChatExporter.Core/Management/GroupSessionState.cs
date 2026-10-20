using DingTalkChatExporter.Core.Models;

namespace DingTalkChatExporter.Core.Management;

/// <summary>Per-group preview kept in the snapshot so switching A/B/A restores A instantly.</summary>
public sealed record GroupPreviewState
{
    public string GroupIdentifier { get; init; } = string.Empty;
    public DateTimeOffset PreviewedAt { get; init; } = DateTimeOffset.UtcNow;
    public string? DataSourceName { get; init; }
    public string? TemplatePath { get; init; }
    public string? RuleLibraryId { get; init; }
    public List<PreviewMessage> Messages { get; init; } = new();
}

public sealed record PreviewMessage
{
    public RawMessage Message { get; init; } = new(null, string.Empty, DateTimeOffset.MinValue, string.Empty, string.Empty);
    public Dictionary<string, ExtractionResult> ExtractedFields { get; init; } = new(StringComparer.Ordinal);
}

/// <summary>Lightweight incremental-read watermark for a single group.</summary>
public sealed record GroupIncrementalState
{
    public string GroupIdentifier { get; init; } = string.Empty;
    public string? LastFetchedMessageId { get; init; }
    public DateTimeOffset? LastFetchedAt { get; init; }
}
