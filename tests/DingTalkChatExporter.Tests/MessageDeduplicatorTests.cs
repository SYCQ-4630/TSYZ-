using DingTalkChatExporter.Core.Models;
using DingTalkChatExporter.Core.Services;

namespace DingTalkChatExporter.Tests;

public class MessageDeduplicatorTests
{
    [Fact]
    public void Deduplicate_UsesMessageIdWhenPresent()
    {
        var deduplicator = new MessageDeduplicator();
        var messages = new[]
        {
            new RawMessage("id-1", "张三", DateTimeOffset.MinValue, "hello", "text"),
            new RawMessage("id-1", "张三", DateTimeOffset.MinValue, "hello", "text"),
            new RawMessage("id-2", "张三", DateTimeOffset.MinValue, "hello", "text")
        };

        var result = deduplicator.Deduplicate(messages);

        Assert.Equal(2, result.UniqueMessages.Count);
        Assert.Equal(1, result.DuplicateCount);
    }

    [Fact]
    public void Deduplicate_FallsBackToSenderTimeContentHash()
    {
        var deduplicator = new MessageDeduplicator();
        var timestamp = new DateTimeOffset(2026, 10, 19, 9, 0, 0, TimeSpan.FromHours(8));
        var messages = new[]
        {
            new RawMessage(null, "未知", DateTimeOffset.MinValue, "同一段文本", "flaui-text"),
            new RawMessage(null, "未知", DateTimeOffset.MinValue, "  同一段文本  ", "flaui-text"),
            new RawMessage(null, "未知", DateTimeOffset.MinValue, "另一段文本", "flaui-text"),
            new RawMessage(null, "李四", timestamp, "同一段文本", "text")
        };

        var result = deduplicator.Deduplicate(messages);

        Assert.Equal(3, result.UniqueMessages.Count);
        Assert.Equal(1, result.DuplicateCount);
    }

    [Fact]
    public void SessionSeenSet_PreventsDuplicatesAcrossBatches()
    {
        var deduplicator = new MessageDeduplicator();
        deduplicator.Deduplicate(new[]
        {
            new RawMessage(null, "未知", DateTimeOffset.MinValue, "A", "flaui-text")
        });

        var second = deduplicator.Deduplicate(new[]
        {
            new RawMessage(null, "未知", DateTimeOffset.MinValue, "A", "flaui-text"),
            new RawMessage(null, "未知", DateTimeOffset.MinValue, "B", "flaui-text")
        });

        Assert.Single(second.UniqueMessages);
        Assert.Equal(1, second.DuplicateCount);
    }

    [Fact]
    public void FallbackKey_IsStableForSameInputs()
    {
        var first = MessageDeduplicator.ComputeFallbackKey("张三", "09:30", "内容\r\n换行");
        var second = MessageDeduplicator.ComputeFallbackKey("张三", "09:30", "内容\r\n换行");
        var different = MessageDeduplicator.ComputeFallbackKey("张三", "09:30", "内容");

        Assert.Equal(first, second);
        Assert.NotEqual(first, different);
        Assert.StartsWith("h:", first);
    }
}
