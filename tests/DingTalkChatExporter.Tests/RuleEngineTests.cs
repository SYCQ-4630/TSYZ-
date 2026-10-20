using DingTalkChatExporter.Core.Models;
using DingTalkChatExporter.Core.Services;

namespace DingTalkChatExporter.Tests;

public class RuleEngineTests
{
    private readonly RuleEngine _engine = new();

    private static RawMessage Message(string content) =>
        new("id", "张三", DateTimeOffset.UtcNow, content, "text");

    [Fact]
    public void RegexMatch_ReturnsHighConfidenceCapturedGroup()
    {
        var rules = new[]
        {
            new ExtractionRule("OrderNo", "regex", @"订单\s*([A-Z]-\d+)", 1)
        };

        var result = _engine.Extract(Message("订单 A-1024 已发货"), rules);

        Assert.Equal(Confidence.High, result["OrderNo"].Confidence);
        Assert.Equal("A-1024", result["OrderNo"].Value);
    }

    [Fact]
    public void KeywordMatch_ReturnsLowConfidenceMatchedFragment()
    {
        var rules = new[]
        {
            new ExtractionRule("Amount", "keyword", "已付款", 1)
        };

        var result = _engine.Extract(Message("客户已付款，请发货"), rules);

        Assert.Equal(Confidence.Low, result["Amount"].Confidence);
        Assert.Equal("已付款", result["Amount"].Value);
    }

    [Fact]
    public void NoMatch_ReturnsNoneWithNullValue()
    {
        var rules = new[]
        {
            new ExtractionRule("Phone", "regex", @"1\d{10}", 0)
        };

        var result = _engine.Extract(Message("没有手机号"), rules);

        Assert.Equal(Confidence.None, result["Phone"].Confidence);
        Assert.Null(result["Phone"].Value);
    }

    [Fact]
    public void InvalidRegex_DoesNotThrow_AndReturnsNone()
    {
        var rules = new[]
        {
            new ExtractionRule("Broken", "regex", "([", 1)
        };

        var result = _engine.Extract(Message("anything"), rules);

        Assert.Equal(Confidence.None, result["Broken"].Confidence);
    }

    [Fact]
    public void GroupIndexOutOfRange_FallsBackToWholeMatch()
    {
        var rules = new[]
        {
            new ExtractionRule("Code", "regex", @"A-\d+", 4)
        };

        var result = _engine.Extract(Message("单号 A-2048"), rules);

        Assert.Equal(Confidence.High, result["Code"].Confidence);
        Assert.Equal("A-2048", result["Code"].Value);
    }

    [Fact]
    public void DuplicateFieldName_KeepsHigherConfidence()
    {
        var rules = new[]
        {
            new ExtractionRule("Status", "keyword", "完成", 1),
            new ExtractionRule("Status", "regex", @"状态[:：]\s*(\S+)", 1)
        };

        var result = _engine.Extract(Message("状态：已完成"), rules);

        Assert.Equal(Confidence.High, result["Status"].Confidence);
        Assert.Equal("已完成", result["Status"].Value);
    }

    [Fact]
    public void EmptyPattern_IsTreatedAsSkeleton_AndReturnsNone()
    {
        var rules = TemplateRuleSkeletonBuilder.FromHeaders(new[] { "订单号", "金额", "订单号", " " });

        Assert.Equal(2, rules.Count);
        Assert.All(rules, rule => Assert.Equal(string.Empty, rule.Pattern));

        var result = _engine.Extract(Message("任意内容"), rules);
        Assert.Equal(Confidence.None, result["订单号"].Confidence);
        Assert.Equal(Confidence.None, result["金额"].Confidence);
    }
}
