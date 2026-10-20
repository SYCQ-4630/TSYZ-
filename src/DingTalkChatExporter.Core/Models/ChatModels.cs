namespace DingTalkChatExporter.Core.Models;

public record RawMessage(
    string? MessageId,
    string SenderName,
    DateTimeOffset SentAt,
    string Content,
    string MessageType);

public record ChatFetchRequest(
    string GroupIdentifier,
    DateTimeOffset? Start,
    DateTimeOffset? End,
    int MaxResults = 500);

public record ExtractionRule(
    string FieldName,
    string MatchType,
    string Pattern,
    int GroupIndex = 1);

public enum Confidence
{
    High,
    Low,
    None
}

public record ExtractionResult(string? Value, Confidence Confidence);
