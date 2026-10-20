namespace DingTalkChatExporter.Core.Abstractions;

/// <summary>Optional detail for sources that cannot be available for a user-facing reason.</summary>
public interface ISourceAvailabilityReason
{
    string? LastUnavailableReason { get; }
}
