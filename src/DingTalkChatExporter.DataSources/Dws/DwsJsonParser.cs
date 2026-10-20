using System.Globalization;
using System.Text.Json;
using DingTalkChatExporter.Core.Models;

namespace DingTalkChatExporter.DataSources.Dws;

public sealed record DwsChatGroup(string Identifier, string Name);

/// <summary>
/// Defensive parser for dws JSON output. dws output has evolved across releases,
/// so known envelopes (messages/data/items/result/list) and common field aliases
/// are accepted. Unknown shapes return empty lists instead of fabricated messages.
/// </summary>
public static class DwsJsonParser
{
    private static readonly string[] MessageArrayNames =
    {
        "messages", "messageList", "message_list", "records", "items", "list", "data", "result"
    };

    private static readonly string[] GroupArrayNames =
    {
        "groups", "conversations", "chats", "sessions", "items", "list", "data", "result"
    };

    public static IReadOnlyList<RawMessage> ParseMessages(string json)
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            return Array.Empty<RawMessage>();
        }

        using var document = JsonDocument.Parse(json, new JsonDocumentOptions
        {
            AllowTrailingCommas = true,
            CommentHandling = JsonCommentHandling.Skip
        });

        var root = document.RootElement;
        var messages = new List<RawMessage>();

        if (root.ValueKind == JsonValueKind.Array)
        {
            AppendMessages(root, messages);
        }
        else if (root.ValueKind == JsonValueKind.Object)
        {
            if (IsMessageLike(root))
            {
                TryAppendMessage(root, messages);
            }
            else
            {
                CollectMessageArrays(root, MessageArrayNames, messages, depth: 0);
                if (messages.Count == 0)
                {
                    CollectFirstMessageLikeArray(root, messages);
                }
            }
        }

        return messages;
    }

    public static IReadOnlyList<DwsChatGroup> ParseGroups(string json)
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            return Array.Empty<DwsChatGroup>();
        }

        using var document = JsonDocument.Parse(json, new JsonDocumentOptions
        {
            AllowTrailingCommas = true,
            CommentHandling = JsonCommentHandling.Skip
        });

        var groups = new List<DwsChatGroup>();
        var seen = new HashSet<string>(StringComparer.Ordinal);

        if (document.RootElement.ValueKind == JsonValueKind.Array)
        {
            AppendGroups(document.RootElement, groups, seen);
        }
        else if (document.RootElement.ValueKind == JsonValueKind.Object)
        {
            CollectGroupArrays(document.RootElement, GroupArrayNames, groups, seen, depth: 0);
        }

        return groups;
    }

    private static void CollectMessageArrays(
        JsonElement element,
        string[] names,
        List<RawMessage> messages,
        int depth)
    {
        if (depth > 6 || element.ValueKind != JsonValueKind.Object)
        {
            return;
        }

        foreach (var property in element.EnumerateObject())
        {
            if (names.Any(name => string.Equals(name, property.Name, StringComparison.OrdinalIgnoreCase)))
            {
                if (property.Value.ValueKind == JsonValueKind.Array)
                {
                    AppendMessages(property.Value, messages);
                }
                else if (property.Value.ValueKind == JsonValueKind.Object)
                {
                    CollectMessageArrays(property.Value, names, messages, depth + 1);
                }
            }
        }

        if (messages.Count > 0)
        {
            return;
        }

        foreach (var property in element.EnumerateObject())
        {
            if (property.Value.ValueKind == JsonValueKind.Object)
            {
                CollectMessageArrays(property.Value, names, messages, depth + 1);
            }
        }
    }

    private static void CollectFirstMessageLikeArray(JsonElement element, List<RawMessage> messages)
    {
        if (element.ValueKind == JsonValueKind.Array)
        {
            var objects = element.EnumerateArray().Where(x => x.ValueKind == JsonValueKind.Object).ToList();
            if (objects.Count > 0 && objects.Count(x => IsMessageLike(x)) >= Math.Max(1, objects.Count / 2))
            {
                AppendMessages(element, messages);
                return;
            }

            foreach (var item in element.EnumerateArray())
            {
                CollectFirstMessageLikeArray(item, messages);
                if (messages.Count > 0)
                {
                    return;
                }
            }

            return;
        }

        if (element.ValueKind != JsonValueKind.Object)
        {
            return;
        }

        foreach (var property in element.EnumerateObject())
        {
            CollectFirstMessageLikeArray(property.Value, messages);
            if (messages.Count > 0)
            {
                return;
            }
        }
    }

    private static void AppendMessages(JsonElement array, List<RawMessage> messages)
    {
        foreach (var item in array.EnumerateArray())
        {
            if (item.ValueKind == JsonValueKind.Object)
            {
                TryAppendMessage(item, messages);
            }
        }
    }

    private static bool TryAppendMessage(JsonElement item, List<RawMessage> messages)
    {
        if (!IsMessageLike(item))
        {
            return false;
        }

        var messageId = ReadString(item, "messageId", "msgId", "msg_id", "message_id", "openMessageId", "id");
        var sender = ReadNestedName(item, "senderName", "sender", "senderNick", "sender_nick", "from", "senderInfo", "author")
                     ?? string.Empty;
        var sentAt = ReadTimestamp(item, "sentAt", "sent_at", "createTime", "createdAt", "created_at", "timestamp", "sendTime", "time")
                     ?? DateTimeOffset.MinValue;
        var content = ReadContent(item) ?? string.Empty;
        var messageType = ReadString(item, "messageType", "msgType", "msg_type", "type") ?? (content.Length > 0 ? "text" : "unknown");

        if (string.IsNullOrWhiteSpace(messageId) && content.Length == 0)
        {
            return false;
        }

        messages.Add(new RawMessage(messageId, sender, sentAt, content, messageType));
        return true;
    }

    private static bool IsMessageLike(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object)
        {
            return false;
        }

        var keys = element.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var hasContent = keys.Overlaps(new[] { "content", "text", "body", "messageContent", "richText" });
        var hasIdentity = keys.Overlaps(new[] { "messageId", "msgId", "msg_id", "message_id", "openMessageId" });
        var hasSender = keys.Overlaps(new[] { "senderName", "sender", "senderNick", "sender_nick", "from", "senderInfo", "author" });
        var hasTime = keys.Overlaps(new[] { "sentAt", "sent_at", "createTime", "createdAt", "created_at", "timestamp", "sendTime", "time" });
        return hasContent || hasIdentity || (hasSender && hasTime);
    }

    private static string? ReadContent(JsonElement element)
    {
        if (!TryGetProperty(element, out var value, "content", "text", "body", "messageContent", "richText"))
        {
            return null;
        }

        if (value.ValueKind == JsonValueKind.String)
        {
            return value.GetString();
        }

        if (value.ValueKind == JsonValueKind.Object)
        {
            if (TryGetProperty(value, out var text, "text", "plainText", "plain_text", "content", "body") &&
                text.ValueKind == JsonValueKind.String)
            {
                return text.GetString();
            }

            return value.GetRawText();
        }

        if (value.ValueKind == JsonValueKind.Array)
        {
            return value.GetRawText();
        }

        return null;
    }

    private static string? ReadNestedName(JsonElement element, params string[] names)
    {
        if (!TryGetProperty(element, out var value, names))
        {
            return null;
        }

        if (value.ValueKind == JsonValueKind.String)
        {
            return value.GetString();
        }

        if (value.ValueKind == JsonValueKind.Object)
        {
            return ReadString(value, "name", "nick", "nickName", "nick_name", "displayName", "display_name", "title")
                   ?? value.GetRawText();
        }

        return null;
    }

    private static string? ReadString(JsonElement element, params string[] names)
    {
        if (!TryGetProperty(element, out var value, names))
        {
            return null;
        }

        return value.ValueKind switch
        {
            JsonValueKind.String => value.GetString(),
            JsonValueKind.Number => value.GetRawText(),
            JsonValueKind.True => "true",
            JsonValueKind.False => "false",
            _ => null
        };
    }

    private static DateTimeOffset? ReadTimestamp(JsonElement element, params string[] names)
    {
        if (!TryGetProperty(element, out var value, names))
        {
            return null;
        }

        if (value.ValueKind == JsonValueKind.Number)
        {
            return value.TryGetInt64(out var numericValue) ? FromUnix(numericValue) : null;
        }

        if (value.ValueKind == JsonValueKind.String)
        {
            var text = value.GetString();
            if (string.IsNullOrWhiteSpace(text))
            {
                return null;
            }

            if (long.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var numeric))
            {
                return FromUnix(numeric);
            }

            if (DateTimeOffset.TryParse(
                    text,
                    CultureInfo.InvariantCulture,
                    DateTimeStyles.AllowWhiteSpaces | DateTimeStyles.AssumeLocal,
                    out var parsed))
            {
                return parsed;
            }
        }

        return null;
    }

    private static DateTimeOffset FromUnix(long value)
    {
        try
        {
            if (value > 100_000_000_000)
            {
                return DateTimeOffset.FromUnixTimeMilliseconds(value);
            }

            if (value > 1_000_000_000)
            {
                return DateTimeOffset.FromUnixTimeSeconds(value);
            }

            return DateTimeOffset.FromUnixTimeMilliseconds(value);
        }
        catch (ArgumentOutOfRangeException)
        {
            return DateTimeOffset.MinValue;
        }
    }

    private static bool TryGetProperty(JsonElement element, out JsonElement value, params string[] names)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            foreach (var property in element.EnumerateObject())
            {
                if (names.Any(name => string.Equals(name, property.Name, StringComparison.OrdinalIgnoreCase)))
                {
                    value = property.Value;
                    return true;
                }
            }
        }

        value = default;
        return false;
    }

    private static void CollectGroupArrays(
        JsonElement element,
        string[] names,
        List<DwsChatGroup> groups,
        HashSet<string> seen,
        int depth)
    {
        if (depth > 6 || element.ValueKind != JsonValueKind.Object)
        {
            return;
        }

        foreach (var property in element.EnumerateObject())
        {
            if (!names.Any(name => string.Equals(name, property.Name, StringComparison.OrdinalIgnoreCase)))
            {
                continue;
            }

            if (property.Value.ValueKind == JsonValueKind.Array)
            {
                AppendGroups(property.Value, groups, seen);
            }
            else if (property.Value.ValueKind == JsonValueKind.Object)
            {
                CollectGroupArrays(property.Value, names, groups, seen, depth + 1);
            }
        }

        if (groups.Count > 0)
        {
            return;
        }

        foreach (var property in element.EnumerateObject())
        {
            if (property.Value.ValueKind == JsonValueKind.Object)
            {
                CollectGroupArrays(property.Value, names, groups, seen, depth + 1);
            }
        }
    }

    private static void AppendGroups(JsonElement array, List<DwsChatGroup> groups, HashSet<string> seen)
    {
        foreach (var item in array.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object)
            {
                continue;
            }

            var identifier = ReadString(
                item,
                "groupId", "group_id", "conversationId", "conversation_id", "chatId", "chat_id", "openConversationId", "id");
            if (string.IsNullOrWhiteSpace(identifier) || !seen.Add(identifier))
            {
                continue;
            }

            var name = ReadString(item, "name", "title", "groupName", "group_name", "conversationTitle", "conversation_title")
                       ?? identifier;
            groups.Add(new DwsChatGroup(identifier, name));
        }
    }
}
