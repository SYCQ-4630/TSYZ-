using DingTalkChatExporter.DataSources.Dws;

namespace DingTalkChatExporter.Tests;

public class DwsJsonParserTests
{
    [Fact]
    public void ParseMessages_HandlesArrayWithFieldAliasesAndNestedContent()
    {
        const string json = """
        [
          {
            "msgId": "m-1",
            "sender": { "nick": "王五" },
            "createTime": 1760832000000,
            "content": { "text": "订单 1024 已付款" },
            "messageType": "text"
          },
          {
            "message_id": "m-2",
            "senderName": "赵六",
            "sent_at": "2026-10-19T10:30:00+08:00",
            "text": "金额 88.00",
            "type": "text"
          }
        ]
        """;

        var messages = DwsJsonParser.ParseMessages(json);

        Assert.Equal(2, messages.Count);
        Assert.Equal("m-1", messages[0].MessageId);
        Assert.Equal("王五", messages[0].SenderName);
        Assert.Equal("订单 1024 已付款", messages[0].Content);
        Assert.Equal(2025, messages[0].SentAt.Year);
        Assert.Equal("m-2", messages[1].MessageId);
        Assert.Equal("赵六", messages[1].SenderName);
        Assert.Equal(2026, messages[1].SentAt.Year);
        Assert.Equal(TimeSpan.FromHours(8), messages[1].SentAt.Offset);
    }

    [Fact]
    public void ParseMessages_HandlesEnvelopeAndSkipsNonMessageObjects()
    {
        const string json = """
        {
          "code": 0,
          "data": {
            "total": 1,
            "messages": [
              { "messageId": "m-3", "senderName": "张三", "createTime": "2026-10-19T09:00:00+08:00", "content": "hello" }
            ]
          }
        }
        """;

        var messages = DwsJsonParser.ParseMessages(json);

        var message = Assert.Single(messages);
        Assert.Equal("m-3", message.MessageId);
        Assert.Equal("hello", message.Content);
    }

    [Fact]
    public void ParseMessages_EmptyInputReturnsEmpty()
    {
        Assert.Empty(DwsJsonParser.ParseMessages("{}"));
        Assert.Empty(DwsJsonParser.ParseMessages("[]"));
        Assert.Empty(DwsJsonParser.ParseMessages(string.Empty));
    }

    [Fact]
    public void ParseGroups_UsesGroupAliasesAndDeduplicates()
    {
        const string json = """
        {
          "data": {
            "groups": [
              { "conversationId": "cid-1", "title": "项目 A 群" },
              { "groupId": "cid-2", "name": "项目 B 群" },
              { "conversationId": "cid-1", "title": "重复项" }
            ]
          }
        }
        """;

        var groups = DwsJsonParser.ParseGroups(json);

        Assert.Equal(2, groups.Count);
        Assert.Equal("cid-1", groups[0].Identifier);
        Assert.Equal("项目 A 群", groups[0].Name);
        Assert.Equal("项目 B 群", groups[1].Name);
    }
}

public class DwsAvailabilityCheckerTests
{
    [Fact]
    public async Task CheckAsync_Authenticated_And_CachesForThirtySeconds()
    {
        var runner = new FakeRunner(
            new ProcessResult(0, "dws 1.2.3\n", string.Empty, false),
            new ProcessResult(0, """{"authenticated":true,"token_valid":true,"expires_at":"2026-11-01T00:00:00Z"}""", string.Empty, false));

        var checker = new DwsAvailabilityChecker(new DwsOptions(), runner);

        var first = await checker.CheckAsync(false, CancellationToken.None);
        var second = await checker.CheckAsync(false, CancellationToken.None);

        Assert.True(first.IsAvailable);
        Assert.True(second.IsAvailable);
        Assert.Equal("dws 1.2.3", first.Version);
        Assert.Equal(2, runner.CallCount);
    }

    [Fact]
    public async Task CheckAsync_NotInstalled_ReportsReasonAndDoesNotCrash()
    {
        var runner = new FakeRunner(_ => throw new DwsNotInstalledException("dws"));
        var checker = new DwsAvailabilityChecker(new DwsOptions(), runner);

        var result = await checker.CheckAsync(false, CancellationToken.None);

        Assert.False(result.Installed);
        Assert.False(result.IsAvailable);
        Assert.Contains("未安装", result.Summary);
    }

    [Fact]
    public async Task CheckAsync_TokenInvalid_IsUnavailable()
    {
        var runner = new FakeRunner(
            new ProcessResult(0, "1.0.0", string.Empty, false),
            new ProcessResult(0, """{"authenticated":true,"token_valid":false}""", string.Empty, false));

        var checker = new DwsAvailabilityChecker(new DwsOptions(), runner);
        var result = await checker.CheckAsync(true, CancellationToken.None);

        Assert.True(result.Installed);
        Assert.True(result.Authenticated);
        Assert.False(result.TokenValid);
        Assert.False(result.IsAvailable);
        Assert.Contains("Token", result.Summary);
    }

    [Fact]
    public void AuthFailureClassifier_DetectsUnauthorizedStderr()
    {
        Assert.True(DwsErrorClassifier.IsAuthFailure("Error: not authenticated, run dws auth login", 1));
        Assert.True(DwsErrorClassifier.IsAuthFailure("HTTP 401 Unauthorized", 2));
        Assert.True(DwsErrorClassifier.IsAuthFailure("当前未认证", 1));
        Assert.False(DwsErrorClassifier.IsAuthFailure("network timeout", 1));
        Assert.False(DwsErrorClassifier.IsAuthFailure("not authenticated", 0));
    }

    private sealed class FakeRunner : IProcessRunner
    {
        private readonly Queue<ProcessResult> _results;
        private readonly Func<string, ProcessResult>? _factory;

        public FakeRunner(params ProcessResult[] results)
        {
            _results = new Queue<ProcessResult>(results);
        }

        public FakeRunner(Func<string, ProcessResult> factory)
        {
            _factory = factory;
            _results = new Queue<ProcessResult>();
        }

        public int CallCount { get; private set; }

        public Task<ProcessResult> RunAsync(
            string fileName,
            IReadOnlyList<string> arguments,
            TimeSpan timeout,
            CancellationToken cancellationToken)
        {
            CallCount++;
            if (_factory is not null)
            {
                return Task.FromResult(_factory(fileName));
            }

            return Task.FromResult(_results.Dequeue());
        }
    }
}
