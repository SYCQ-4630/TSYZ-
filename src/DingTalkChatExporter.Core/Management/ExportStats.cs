namespace DingTalkChatExporter.Core.Management;

public sealed record ExportStats
{
    public string RunId { get; init; } = string.Empty;
    public DateTimeOffset ExportedAt { get; init; } = DateTimeOffset.UtcNow;
    public string TemplatePath { get; init; } = string.Empty;
    public string OutputPath { get; init; } = string.Empty;
    public string SourceName { get; init; } = string.Empty;
    public int RawCount { get; init; }
    public int DedupedCount { get; init; }
    public int ExportedCount { get; init; }
    public int LowConfidenceCount { get; init; }
    public string Mode { get; init; } = string.Empty;
    public int StartRow { get; init; }
    public long DurationMs { get; init; }
}
