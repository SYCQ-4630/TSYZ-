using DingTalkChatExporter.Core.Models;

namespace DingTalkChatExporter.Core.Management;

public sealed record RuleLibrary
{
    public string Id { get; init; } = Guid.NewGuid().ToString("N");
    public string Name { get; init; } = string.Empty;
    public bool IsDefault { get; init; }
    public List<ExtractionRule> Rules { get; init; } = new();
    public List<RuleLibraryVersion> Versions { get; init; } = new();
    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; init; } = DateTimeOffset.UtcNow;
}

public sealed record RuleLibraryVersion
{
    public string VersionId { get; init; } = Guid.NewGuid().ToString("N");
    public DateTimeOffset SavedAt { get; init; } = DateTimeOffset.UtcNow;
    public List<ExtractionRule> Rules { get; init; } = new();
    public string? Note { get; init; }
}
