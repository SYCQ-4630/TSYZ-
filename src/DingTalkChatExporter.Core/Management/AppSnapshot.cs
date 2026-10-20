using System.Text.Json.Serialization;

namespace DingTalkChatExporter.Core.Management;

/// <summary>
/// Recoverable workbench state. Persisted separately from feature flags, tables,
/// import history and rule libraries; never merged into one large settings file.
/// </summary>
public sealed record AppSnapshot
{
    public const int CurrentSchemaVersion = 1;

    public int SchemaVersion { get; init; } = CurrentSchemaVersion;
    public DateTimeOffset SavedAt { get; init; } = DateTimeOffset.UtcNow;
    public string? CurrentGroupIdentifier { get; init; }
    public string? CurrentDataSource { get; init; }
    public string? CurrentTemplatePath { get; init; }
    public string? CurrentRuleLibraryId { get; init; }
    public string? LastNavigationPage { get; init; }
    public WindowPlacementState? WindowPlacement { get; init; }
    public Dictionary<string, GroupPreviewState> Previews { get; init; } = new(StringComparer.Ordinal);
    /// <summary>Persisted separately to state.json by the snapshot service.</summary>
    [JsonIgnore]
    public Dictionary<string, GroupIncrementalState> IncrementalStates { get; init; } = new(StringComparer.Ordinal);

    public GroupPreviewState? GetPreview(string? groupIdentifier)
    {
        if (string.IsNullOrWhiteSpace(groupIdentifier))
        {
            return null;
        }

        return Previews.TryGetValue(groupIdentifier, out var preview) ? preview : null;
    }
}
