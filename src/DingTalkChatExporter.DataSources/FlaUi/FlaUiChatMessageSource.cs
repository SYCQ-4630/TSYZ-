#if WINDOWS
using System.Diagnostics;
using DingTalkChatExporter.Core.Abstractions;
using DingTalkChatExporter.Core.Models;
using DingTalkChatExporter.Core.Services;
using FlaUI.Core;
using FlaUI.Core.AutomationElements;
using FlaUI.Core.Definitions;
using FlaUI.Core.Patterns;
using FlaUI.UIA3;
using Serilog;

namespace DingTalkChatExporter.DataSources.FlaUi;

/// <summary>
/// Experimental fallback source. Enable only when dws is unavailable: DingTalk's
/// desktop client is CEF/self-rendered, so the UIA tree usually exposes only a large
/// group node for the message area. This implementation reads text controls only and
/// never writes to DingTalk.
/// </summary>
public sealed class FlaUiChatMessageSource : IChatMessageSource
{
    public const string ExperimentalName = "FlaUI (实验性)";

    private static readonly string[] MessageContainerKeywords =
    {
        "message", "msg", "chat", "消息", "聊天", "会话"
    };

    private readonly FlaUiOptions _options;
    private readonly Action<bool>? _setOwnWindowVisible;
    private readonly ILogger _logger;
    private readonly HashSet<string> _sessionSeenKeys = new(StringComparer.Ordinal);

    public FlaUiChatMessageSource(
        FlaUiOptions options,
        Action<bool>? setOwnWindowVisible = null,
        ILogger? logger = null)
    {
        _options = options;
        _setOwnWindowVisible = setOwnWindowVisible;
        _logger = (logger ?? Serilog.Log.Logger).ForContext<FlaUiChatMessageSource>();
    }

    public string SourceName => ExperimentalName;

    public bool IsAvailable => FindDingTalkProcess() is not null;

    public string? LastUnavailableReason { get; private set; }

    public Task<IReadOnlyList<RawMessage>> FetchAsync(ChatFetchRequest request, CancellationToken ct)
    {
        return Task.Run(() => FetchCoreAsync(request, ct), ct);
    }

    private Task<IReadOnlyList<RawMessage>> FetchCoreAsync(ChatFetchRequest request, CancellationToken ct)
    {
        var process = FindDingTalkProcess();
        if (process is null)
        {
            LastUnavailableReason = $"未找到钉钉进程“{_options.ProcessName}”，FlaUI 不可用。";
            _logger.Warning("FlaUI unavailable: DingTalk process {ProcessName} was not found.", _options.ProcessName);
            return Task.FromResult<IReadOnlyList<RawMessage>>(Array.Empty<RawMessage>());
        }

        LastUnavailableReason = null;
        var hideOwnWindow = _options.HideOwnWindow && _setOwnWindowVisible is not null;
        if (hideOwnWindow)
        {
            _setOwnWindowVisible!(false);
        }

        try
        {
            using var application = Application.Attach(process);
            using var automation = new UIA3Automation();
            var mainWindow = application.GetMainWindow(automation, TimeSpan.FromSeconds(5));
            if (mainWindow is null)
            {
                LastUnavailableReason = "已找到钉钉进程，但无法获取钉钉主窗口。";
                return Task.FromResult<IReadOnlyList<RawMessage>>(Array.Empty<RawMessage>());
            }

            var container = FindMessageContainer(mainWindow, automation) ?? mainWindow;
            var messages = new List<RawMessage>();
            var seenThisRun = new HashSet<string>(StringComparer.Ordinal);
            var noNewScreenCount = 0;

            var firstBatch = ExtractAndAppend(container, messages, seenThisRun, request.MaxResults, ct);
            if (_options.CaptureMode == FlaUiCaptureMode.VisibleOnly)
            {
                _logger.Information(
                    "FlaUI visible-only capture returned {MessageCount} text blocks (added {Added}).",
                    messages.Count + firstBatch.Duplicates,
                    firstBatch.Added);
                return Task.FromResult<IReadOnlyList<RawMessage>>(messages);
            }

            var scrollPattern = FindScrollPattern(container);
            if (scrollPattern is null)
            {
                _logger.Warning("FlaUI scroll mode requested but no ScrollPattern is available; returning the visible batch.");
                return Task.FromResult<IReadOnlyList<RawMessage>>(messages);
            }

            try
            {
                scrollPattern.SetScrollPercent(ScrollPatternConstants.NoScroll, 0);
            }
            catch (Exception ex)
            {
                _logger.Debug(ex, "FlaUI could not reset the message list scroll position.");
            }

            var maxScreens = Math.Clamp(_options.MaxScrollScreens, 1, 100);
            var interval = Math.Clamp(_options.ScrollIntervalMs, 50, 10_000);

            for (var screen = 0; screen < maxScreens; screen++)
            {
                ct.ThrowIfCancellationRequested();

                var batch = ExtractAndAppend(container, messages, seenThisRun, request.MaxResults, ct);
                if (batch.Added == 0)
                {
                    noNewScreenCount++;
                }
                else
                {
                    noNewScreenCount = 0;
                }

                if (noNewScreenCount >= 2)
                {
                    _logger.Information("FlaUI scrolling stopped after two screens without new content.");
                    break;
                }

                if (messages.Count >= request.MaxResults)
                {
                    break;
                }

                double? percent = null;
                try
                {
                    percent = scrollPattern.VerticalScrollPercent.Value;
                }
                catch (Exception ex)
                {
                    _logger.Debug(ex, "FlaUI could not read the vertical scroll position.");
                }

                if (percent is >= 99.5)
                {
                    break;
                }

                scrollPattern.Scroll(ScrollAmount.NoAmount, ScrollAmount.LargeIncrement);
                Thread.Sleep(interval);
            }

            _logger.Information(
                "FlaUI scroll capture returned {MessageCount} unique text blocks over {Screens} screens.",
                messages.Count,
                maxScreens);

            return Task.FromResult<IReadOnlyList<RawMessage>>(messages);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            LastUnavailableReason = "FlaUI 采集失败：" + ex.Message;
            _logger.Warning(ex, "FlaUI capture failed.");
            return Task.FromResult<IReadOnlyList<RawMessage>>(Array.Empty<RawMessage>());
        }
        finally
        {
            if (hideOwnWindow)
            {
                _setOwnWindowVisible!(true);
            }
        }
    }

    private BatchResult ExtractAndAppend(
        AutomationElement container,
        List<RawMessage> messages,
        HashSet<string> seenThisRun,
        int maxResults,
        CancellationToken ct)
    {
        var added = 0;
        var duplicates = 0;

        AutomationElement[] texts;
        try
        {
            texts = container.FindAllDescendants(cf => cf.ByControlType(ControlType.Text));
        }
        catch (Exception ex)
        {
            _logger.Debug(ex, "FlaUI failed to enumerate text controls.");
            return new BatchResult(0, 0);
        }

        foreach (var element in texts)
        {
            ct.ThrowIfCancellationRequested();

            if (messages.Count >= maxResults)
            {
                break;
            }

            try
            {
                if (_options.CaptureMode == FlaUiCaptureMode.VisibleOnly && element.IsOffscreen)
                {
                    continue;
                }

                var content = element.Name?.Trim();
                if (string.IsNullOrWhiteSpace(content) || content.Length < 1)
                {
                    continue;
                }

                if (ShouldIgnoreChrome(content))
                {
                    continue;
                }

                var timeText = TryFindTimeText(element, out var time) ? time : string.Empty;
                var sender = "未知";

                var key = MessageDeduplicator.ComputeFallbackKey(sender, timeText, content);
                if (!seenThisRun.Add(key) || !_sessionSeenKeys.Add(key))
                {
                    duplicates++;
                    continue;
                }

                messages.Add(new RawMessage(
                    null,
                    sender,
                    DateTimeOffset.MinValue,
                    content,
                    "flaui-text"));
                added++;
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                _logger.Debug(ex, "Skipped one FlaUI text element that could not be read.");
            }
        }

        return new BatchResult(added, duplicates);
    }

    private static bool TryFindTimeText(AutomationElement element, out string timeText)
    {
        timeText = string.Empty;
        try
        {
            var parent = element.Parent;
            for (var depth = 0; depth < 3 && parent is not null; depth++)
            {
                var texts = parent.FindAllDescendants(cf => cf.ByControlType(ControlType.Text));
                foreach (var sibling in texts)
                {
                    var value = sibling.Name?.Trim();
                    if (value is null || value.Length < 4 || value.Length > 24)
                    {
                        continue;
                    }

                    if (LooksLikeTime(value))
                    {
                        timeText = value;
                        return true;
                    }
                }

                parent = parent.Parent;
            }
        }
        catch (Exception)
        {
            // Time text is best-effort only. Sender/time stay unknown when unavailable.
        }

        return false;
    }

    private static bool LooksLikeTime(string value)
    {
        return value.Contains(':') &&
               (value.Contains("上午", StringComparison.Ordinal) ||
                value.Contains("下午", StringComparison.Ordinal) ||
                value.Contains("昨天", StringComparison.Ordinal) ||
                value.Any(char.IsDigit));
    }

    private static bool ShouldIgnoreChrome(string content)
    {
        return content is "发送" or "发送(S)" or "表情" or "文件" or "图片" or "更多" or "聊天信息"
            or "搜索" or "通讯录" or "工作台" or "消息" or "DingTalk" or "钉钉";
    }

    private AutomationElement? FindMessageContainer(AutomationElement mainWindow, UIA3Automation automation)
    {
        try
        {
            var candidates = new List<AutomationElement>();
            foreach (var controlType in new[] { ControlType.List, ControlType.DataGrid, ControlType.Group, ControlType.Pane })
            {
                try
                {
                    var found = mainWindow.FindAllDescendants(cf => cf.ByControlType(controlType));
                    candidates.AddRange(found);
                }
                catch (Exception ex)
                {
                    _logger.Debug(ex, "FlaUI failed to enumerate control type {ControlType}.", controlType);
                }

                if (candidates.Count > 400)
                {
                    break;
                }
            }

            var keywordMatch = candidates.FirstOrDefault(element =>
            {
                try
                {
                    var identity = (element.AutomationId ?? string.Empty) + " " + (element.Name ?? string.Empty);
                    return MessageContainerKeywords.Any(keyword =>
                        identity.Contains(keyword, StringComparison.OrdinalIgnoreCase));
                }
                catch
                {
                    return false;
                }
            });

            if (keywordMatch is not null)
            {
                return keywordMatch;
            }

            return candidates
                .Select(element => (Element: element, Area: SafeArea(element)))
                .Where(pair => pair.Area > 0)
                .OrderByDescending(pair => pair.Area)
                .Select(pair => pair.Element)
                .FirstOrDefault();
        }
        catch (Exception ex)
        {
            _logger.Debug(ex, "FlaUI message container discovery failed; using the main window.");
            return mainWindow;
        }
    }

    private static double SafeArea(AutomationElement element)
    {
        try
        {
            var rectangle = element.BoundingRectangle;
            return rectangle.IsEmpty ? 0 : rectangle.Width * rectangle.Height;
        }
        catch
        {
            return 0;
        }
    }

    private static IScrollPattern? FindScrollPattern(AutomationElement element)
    {
        try
        {
            var current = element;
            for (var depth = 0; depth < 5 && current is not null; depth++)
            {
                var pattern = current.Patterns.Scroll.PatternOrDefault;
                if (pattern is not null)
                {
                    return pattern;
                }

                current = current.Parent;
            }
        }
        catch
        {
            // Missing scroll support is handled by the caller.
        }

        return null;
    }

    private Process? FindDingTalkProcess()
    {
        var processName = string.IsNullOrWhiteSpace(_options.ProcessName)
            ? "dingding"
            : _options.ProcessName.Trim();

        try
        {
            return Process.GetProcessesByName(processName)
                .FirstOrDefault(process =>
                {
                    try
                    {
                        return !process.HasExited && process.MainWindowHandle != IntPtr.Zero;
                    }
                    catch
                    {
                        return false;
                    }
                });
        }
        catch (Exception ex)
        {
            LastUnavailableReason = "检查钉钉进程失败：" + ex.Message;
            _logger.Debug(ex, "FlaUI process discovery failed.");
            return null;
        }
    }

    private sealed record BatchResult(int Added, int Duplicates);
}
#endif
