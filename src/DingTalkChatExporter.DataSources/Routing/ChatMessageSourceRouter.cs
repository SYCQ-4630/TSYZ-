using DingTalkChatExporter.Core.Abstractions;
using DingTalkChatExporter.Core.Models;
using DingTalkChatExporter.DataSources.Dws;
using Serilog;

namespace DingTalkChatExporter.DataSources.Routing;

public sealed record SourceFailure(string SourceName, string Reason);

public sealed record SourceSelection(
    IChatMessageSource? Source,
    IReadOnlyList<SourceFailure> Failures,
    string Summary,
    string? ErrorMessage,
    DwsAvailability? DwsStatus)
{
    public bool HasSource => Source is not null;
}

/// <summary>
/// dws is always preferred. FlaUI is only considered when the strategy is Auto,
/// the "allow FlaUI fallback" feature is on, and dws is unavailable.
/// </summary>
public sealed class ChatMessageSourceRouter
{
    private readonly IChatMessageSource _dwsSource;
    private readonly IChatMessageSource _flaUiSource;
    private readonly DwsAvailabilityChecker _dwsAvailabilityChecker;
    private readonly Func<RouterStrategy> _strategyProvider;
    private readonly Func<bool> _allowFlaUiFallback;
    private readonly ILogger _logger;

    public ChatMessageSourceRouter(
        IChatMessageSource dwsSource,
        IChatMessageSource flaUiSource,
        DwsAvailabilityChecker dwsAvailabilityChecker,
        Func<RouterStrategy> strategyProvider,
        Func<bool> allowFlaUiFallback,
        ILogger? logger = null)
    {
        _dwsSource = dwsSource;
        _flaUiSource = flaUiSource;
        _dwsAvailabilityChecker = dwsAvailabilityChecker;
        _strategyProvider = strategyProvider;
        _allowFlaUiFallback = allowFlaUiFallback;
        _logger = (logger ?? Serilog.Log.Logger).ForContext<ChatMessageSourceRouter>();
    }

    public async Task<SourceSelection> GetPreferredSourceAsync(
        CancellationToken cancellationToken,
        bool forceRefresh = false)
    {
        var strategy = _strategyProvider();

        if (strategy == RouterStrategy.FlaUiOnly)
        {
            var flaUiFailures = new List<SourceFailure>();
            if (TrySelectFlaUi(flaUiFailures, out var flaUiSelection))
            {
                _logger.Information("Router selected FlaUI because the strategy is FlaUiOnly.");
                return flaUiSelection!;
            }

            return new SourceSelection(
                null,
                flaUiFailures,
                "无可用数据源。",
                "路由策略为“仅 FlaUI”，但 FlaUI 不可用：" + string.Join("；", flaUiFailures.Select(f => f.Reason)),
                _dwsAvailabilityChecker.CachedResult);
        }

        var dwsStatus = await _dwsAvailabilityChecker
            .CheckAsync(forceRefresh, cancellationToken)
            .ConfigureAwait(false);

        if (dwsStatus.IsAvailable)
        {
            var summary = "使用 dws（已认证，可用）";
            _logger.Information("Router selected dws.");
            return new SourceSelection(_dwsSource, Array.Empty<SourceFailure>(), summary, null, dwsStatus);
        }

        var dwsFailure = new SourceFailure(
            DwsChatMessageSource.Name,
            dwsStatus.Detail is null ? dwsStatus.Summary : $"{dwsStatus.Summary} {dwsStatus.Detail}");

        if (strategy == RouterStrategy.DwsOnly)
        {
            var failures = new[] { dwsFailure };
            var error = "路由策略为“仅 dws”，dws 当前不可用，已按策略停止，不会降级到 FlaUI。" + dwsFailure.Reason;
            _logger.Warning("Router strategy DwsOnly but dws is unavailable.");
            return new SourceSelection(null, failures, "无可用数据源。", error, dwsStatus);
        }

        var failuresList = new List<SourceFailure> { dwsFailure };

        if (!_allowFlaUiFallback())
        {
            failuresList.Add(new SourceFailure(
                "FlaUI (实验性)",
                "已通过功能开关禁用 FlaUI 降级。"));

            return new SourceSelection(
                null,
                failuresList,
                "无可用数据源。",
                "dws 不可用，且已禁用 FlaUI 降级。",
                dwsStatus);
        }

        if (TrySelectFlaUi(failuresList, out var selection))
        {
            _logger.Information("Router fell back to FlaUI because dws is unavailable.");
            return selection! with { DwsStatus = dwsStatus };
        }

        var bothUnavailable = "dws 与 FlaUI 均不可用。";
        return new SourceSelection(null, failuresList, "无可用数据源。", bothUnavailable, dwsStatus);
    }

    public async Task<RouterStatus> GetStatusAsync(CancellationToken cancellationToken, bool forceRefresh = false)
    {
        var dws = await _dwsAvailabilityChecker
            .CheckAsync(forceRefresh, cancellationToken)
            .ConfigureAwait(false);

        var flaUiAvailable = SafeIsAvailable(_flaUiSource);
        var flaUiReason = flaUiAvailable
            ? null
            : (_flaUiSource as ISourceAvailabilityReason)?.LastUnavailableReason ?? "钉钉进程未运行。";

        return new RouterStatus(dws, flaUiAvailable, flaUiReason, _strategyProvider());
    }

    private bool TrySelectFlaUi(List<SourceFailure> failures, out SourceSelection? selection)
    {
        selection = null;
        if (!SafeIsAvailable(_flaUiSource))
        {
            var reason = (_flaUiSource as ISourceAvailabilityReason)?.LastUnavailableReason
                         ?? "未找到钉钉进程，FlaUI 不可用。";
            failures.Add(new SourceFailure(FlaUiSourceName, reason));
            return false;
        }

        selection = new SourceSelection(
            _flaUiSource,
            failures.ToArray(),
            "使用 FlaUI（实验性降级数据源）。",
            null,
            null);
        return true;
    }

    private const string FlaUiSourceName = "FlaUI (实验性)";

    private static bool SafeIsAvailable(IChatMessageSource source)
    {
        try
        {
            return source.IsAvailable;
        }
        catch
        {
            return false;
        }
    }
}

public sealed record RouterStatus(
    DwsAvailability Dws,
    bool FlaUiAvailable,
    string? FlaUiReason,
    RouterStrategy Strategy);
