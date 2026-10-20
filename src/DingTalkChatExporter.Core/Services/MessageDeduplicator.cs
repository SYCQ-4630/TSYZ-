using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using DingTalkChatExporter.Core.Models;

namespace DingTalkChatExporter.Core.Services;

public sealed record DeduplicationResult(IReadOnlyList<RawMessage> UniqueMessages, int DuplicateCount);

/// <summary>
/// Stable de-duplication for both dws and FlaUI batches.
/// Message IDs win when present; otherwise a hash of sender + time + content is used.
/// The instance keeps a session-wide seen set so FlaUI scrolling can add batches.
/// </summary>
public sealed class MessageDeduplicator
{
    private static readonly Regex WhitespaceRegex = new(@"\s+", RegexOptions.Compiled);

    private readonly HashSet<string> _seen;

    public MessageDeduplicator()
    {
        _seen = new HashSet<string>(StringComparer.Ordinal);
    }

    public int SeenCount => _seen.Count;

    public DeduplicationResult Deduplicate(IEnumerable<RawMessage> messages, bool remember = true)
    {
        var unique = new List<RawMessage>();
        var duplicates = 0;

        foreach (var message in messages)
        {
            var key = ComputeKey(message);
            if (_seen.Contains(key))
            {
                duplicates++;
                continue;
            }

            if (remember)
            {
                _seen.Add(key);
            }

            unique.Add(message);
        }

        return new DeduplicationResult(unique, duplicates);
    }

    public bool TryAdd(RawMessage message)
    {
        return _seen.Add(ComputeKey(message));
    }

    public void Clear() => _seen.Clear();

    public static string ComputeKey(RawMessage message)
    {
        if (!string.IsNullOrWhiteSpace(message.MessageId))
        {
            return "id:" + message.MessageId.Trim();
        }

        var timeText = message.SentAt == DateTimeOffset.MinValue
            ? string.Empty
            : message.SentAt.ToString("O");

        return ComputeFallbackKey(message.SenderName, timeText, message.Content);
    }

    public static string ComputeFallbackKey(string senderName, string timeText, string content)
    {
        var normalized = string.Join(
            "\u001f",
            Normalize(senderName),
            Normalize(timeText),
            Normalize(content));

        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(normalized));
        return "h:" + Convert.ToHexString(hash);
    }

    private static string Normalize(string? value) =>
        string.IsNullOrWhiteSpace(value)
            ? string.Empty
            : WhitespaceRegex.Replace(value.Trim(), " ");
}
