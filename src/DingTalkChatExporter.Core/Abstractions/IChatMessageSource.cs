using DingTalkChatExporter.Core.Models;

namespace DingTalkChatExporter.Core.Abstractions;

public interface IChatMessageSource
{
    string SourceName { get; }

    bool IsAvailable { get; }

    Task<IReadOnlyList<RawMessage>> FetchAsync(ChatFetchRequest request, CancellationToken ct);
}
