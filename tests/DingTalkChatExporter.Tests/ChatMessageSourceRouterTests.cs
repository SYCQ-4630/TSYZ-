using DingTalkChatExporter.Core.Abstractions;
using DingTalkChatExporter.Core.Models;
using DingTalkChatExporter.DataSources.Dws;
using DingTalkChatExporter.DataSources.Routing;

namespace DingTalkChatExporter.Tests;

public class ChatMessageSourceRouterTests
{
    [Fact]
    public async Task Auto_DwsAvailable_SelectsDws_AndNeverTouchesFlaUi()
    {
        var dwsSource = new FakeSource("dws", true);
        var flaUiSource = new FakeSource("FlaUI (实验性)", true, throwOnAvailabilityRead: true);
        var runner = AvailableRunner();
        var router = CreateRouter(dwsSource, flaUiSource, runner, RouterStrategy.Auto, allowFallback: true);

        var selection = await router.GetPreferredSourceAsync(CancellationToken.None);

        Assert.True(selection.HasSource);
        Assert.Same(dwsSource, selection.Source);
        Assert.Empty(selection.Failures);
        Assert.Equal(0, flaUiSource.AvailabilityReads);
    }

    [Fact]
    public async Task DwsOnly_WhenDwsNotAuthenticated_ReturnsExplicitErrorAndDoesNotFallback()
    {
        var dwsSource = new FakeSource("dws", false);
        var flaUiSource = new FakeSource("FlaUI (实验性)", true, throwOnAvailabilityRead: true);
        var runner = UnauthenticatedRunner();
        var router = CreateRouter(dwsSource, flaUiSource, runner, RouterStrategy.DwsOnly, allowFallback: true);

        var selection = await router.GetPreferredSourceAsync(CancellationToken.None);

        Assert.False(selection.HasSource);
        Assert.Contains("仅 dws", selection.ErrorMessage);
        Assert.Contains("dws", selection.Failures[0].Reason);
        Assert.Equal(0, flaUiSource.AvailabilityReads);
    }

    [Fact]
    public async Task Auto_BothUnavailable_ReportsTwoSpecificReasons()
    {
        var dwsSource = new FakeSource("dws", false);
        var flaUiSource = new FakeSource("FlaUI (实验性)", false, reason: "未找到钉钉进程“dingding”。");
        var router = CreateRouter(dwsSource, flaUiSource, UnauthenticatedRunner(), RouterStrategy.Auto, allowFallback: true);

        var selection = await router.GetPreferredSourceAsync(CancellationToken.None);

        Assert.False(selection.HasSource);
        Assert.Equal(2, selection.Failures.Count);
        Assert.Contains(selection.Failures, f => f.SourceName == "dws");
        Assert.Contains(selection.Failures, f => f.Reason.Contains("钉钉进程"));
    }

    [Fact]
    public async Task Auto_FallbackDisabled_DoesNotUseFlaUi()
    {
        var dwsSource = new FakeSource("dws", false);
        var flaUiSource = new FakeSource("FlaUI (实验性)", true, throwOnAvailabilityRead: true);
        var router = CreateRouter(dwsSource, flaUiSource, UnauthenticatedRunner(), RouterStrategy.Auto, allowFallback: false);

        var selection = await router.GetPreferredSourceAsync(CancellationToken.None);

        Assert.False(selection.HasSource);
        Assert.Contains(selection.Failures, f => f.Reason.Contains("禁用"));
        Assert.Equal(0, flaUiSource.AvailabilityReads);
    }

    [Fact]
    public async Task FlaUiOnly_SelectsFlaUiWithoutCheckingDws()
    {
        var dwsSource = new FakeSource("dws", false);
        var flaUiSource = new FakeSource("FlaUI (实验性)", true);
        var runner = UnauthenticatedRunner();
        var router = CreateRouter(dwsSource, flaUiSource, runner, RouterStrategy.FlaUiOnly, allowFallback: true);

        var selection = await router.GetPreferredSourceAsync(CancellationToken.None);

        Assert.Same(flaUiSource, selection.Source);
        Assert.Equal(0, runner.CallCount);
    }

    [Fact]
    public async Task HealthCheck_IsCachedForRepeatedStatusCalls()
    {
        var runner = AvailableRunner();
        var router = CreateRouter(
            new FakeSource("dws", true),
            new FakeSource("FlaUI (实验性)", true),
            runner,
            RouterStrategy.Auto,
            allowFallback: true);

        await router.GetStatusAsync(CancellationToken.None);
        await router.GetStatusAsync(CancellationToken.None);

        Assert.Equal(2, runner.CallCount);
    }

    private static ChatMessageSourceRouter CreateRouter(
        IChatMessageSource dws,
        IChatMessageSource flaUi,
        FakeRunner runner,
        RouterStrategy strategy,
        bool allowFallback)
    {
        var checker = new DwsAvailabilityChecker(new DwsOptions(), runner);
        return new ChatMessageSourceRouter(dws, flaUi, checker, () => strategy, () => allowFallback);
    }

    private static FakeRunner AvailableRunner() => new(
        new ProcessResult(0, "dws 1.0.0", string.Empty, false),
        new ProcessResult(0, """{"authenticated":true,"token_valid":true}""", string.Empty, false));

    private static FakeRunner UnauthenticatedRunner() => new(
        new ProcessResult(0, "dws 1.0.0", string.Empty, false),
        new ProcessResult(0, """{"authenticated":false,"token_valid":false}""", string.Empty, false));

    private sealed class FakeSource : IChatMessageSource, ISourceAvailabilityReason
    {
        private readonly bool _available;
        private readonly bool _throwOnAvailabilityRead;
        private readonly string? _reason;

        public FakeSource(
            string name,
            bool available,
            bool throwOnAvailabilityRead = false,
            string? reason = null)
        {
            SourceName = name;
            _available = available;
            _throwOnAvailabilityRead = throwOnAvailabilityRead;
            _reason = reason;
        }

        public string SourceName { get; }

        public int AvailabilityReads { get; private set; }

        public bool IsAvailable
        {
            get
            {
                AvailabilityReads++;
                if (_throwOnAvailabilityRead)
                {
                    throw new InvalidOperationException("FlaUI must not be probed when dws is available.");
                }

                return _available;
            }
        }

        public string? LastUnavailableReason => _reason;

        public Task<IReadOnlyList<RawMessage>> FetchAsync(ChatFetchRequest request, CancellationToken ct) =>
            throw new InvalidOperationException("Not used by router tests.");
    }

    private sealed class FakeRunner : IProcessRunner
    {
        private readonly Queue<ProcessResult> _results;

        public FakeRunner(params ProcessResult[] results)
        {
            _results = new Queue<ProcessResult>(results);
        }

        public int CallCount { get; private set; }

        public Task<ProcessResult> RunAsync(
            string fileName,
            IReadOnlyList<string> arguments,
            TimeSpan timeout,
            CancellationToken cancellationToken)
        {
            CallCount++;
            return Task.FromResult(_results.Dequeue());
        }
    }
}
