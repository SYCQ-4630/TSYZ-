using System.Windows;
using DingTalkChatExporter.Core.Abstractions;
using DingTalkChatExporter.Core.Management;
using DingTalkChatExporter.Core.Models;
using DingTalkChatExporter.Core.Services;
using DingTalkChatExporter.DataSources.Dws;
using DingTalkChatExporter.DataSources.FlaUi;
using DingTalkChatExporter.DataSources.Routing;
using Serilog;

namespace DingTalkChatExporter.App.Services;

public sealed class SourceUnavailableException : Exception
{
    public SourceUnavailableException(SourceSelection selection)
        : base(selection.ErrorMessage ?? selection.Summary)
    {
        Selection = selection;
    }

    public SourceSelection Selection { get; }
}

/// <summary>
/// Composition root for read-only message sources. Settings are re-applied on every
/// fetch, so a changed dws path or strategy takes effect without restarting.
/// </summary>
public sealed class WorkbenchService
{
    private readonly SettingsService _settings;
    private readonly ILogger _logger;
    private readonly DwsOptions _dwsOptions = new();
    private readonly FlaUiOptions _flaUiOptions = new();
    private readonly DwsAvailabilityChecker _availability;
    private readonly DwsGroupDirectory _groupDirectory;
    private readonly ChatMessageSourceRouter _router;
    private readonly IChatMessageSource _dwsSource;

    public WorkbenchService(SettingsService settings, RuleLibraryManager ruleLibraries, ILogger logger)
    {
        _settings = settings;
        _logger = logger.ForContext<WorkbenchService>();
        RuleLibraries = ruleLibraries;

        _availability = new DwsAvailabilityChecker(_dwsOptions, logger: logger);
        _dwsSource = new DwsChatMessageSource(_dwsOptions, _availability, logger: logger);
        var flaUiSource = new FlaUiChatMessageSource(
            _flaUiOptions,
            visible => SetOwnWindowVisible(visible),
            logger);
        _groupDirectory = new DwsGroupDirectory(_dwsOptions, logger: logger);
        _router = new ChatMessageSourceRouter(
            _dwsSource,
            flaUiSource,
            _availability,
            () => _settings.DataSources.RouterStrategy,
            () => _settings.Features.AllowFlaUiFallback,
            logger);

        ApplySettings();
    }

    public RuleLibraryManager RuleLibraries { get; }

    public string SourceName => _dwsSource.SourceName;

    public void ApplySettings()
    {
        var previousPath = _dwsOptions.ExecutablePath;
        _dwsOptions.ExecutablePath = _settings.DataSources.DwsExecutablePath;
        if (!string.Equals(previousPath, _dwsOptions.ExecutablePath, StringComparison.Ordinal))
        {
            _availability.Invalidate();
        }

        var flaUi = _settings.DataSources.FlaUi;
        _flaUiOptions.ProcessName = flaUi.ProcessName;
        _flaUiOptions.CaptureMode = flaUi.CaptureMode == Core.Management.FlaUiCaptureMode.Scroll
            ? DataSources.FlaUi.FlaUiCaptureMode.Scroll
            : DataSources.FlaUi.FlaUiCaptureMode.VisibleOnly;
        _flaUiOptions.MaxScrollScreens = Math.Clamp(flaUi.MaxScrollScreens, 1, 100);
        _flaUiOptions.ScrollIntervalMs = Math.Clamp(flaUi.ScrollIntervalMs, 100, 5000);
        _flaUiOptions.HideOwnWindow = flaUi.HideOwnWindow
                                      && _settings.Features.HideAppDuringFlaUiCapture;
    }

    public Task<RouterStatus> GetStatusAsync(bool forceRefresh, CancellationToken cancellationToken)
    {
        ApplySettings();
        return _router.GetStatusAsync(cancellationToken, forceRefresh);
    }

    public Task<IReadOnlyList<DwsChatGroup>> SearchGroupsAsync(
        string query,
        CancellationToken cancellationToken)
    {
        ApplySettings();
        return _groupDirectory.SearchGroupsAsync(query, cancellationToken);
    }

    public string? LastGroupSearchFailure => _groupDirectory.LastFailureReason;

    public async Task<SourceFetchResult> FetchAsync(
        ChatFetchRequest request,
        CancellationToken cancellationToken)
    {
        ApplySettings();
        var selection = await _router
            .GetPreferredSourceAsync(cancellationToken)
            .ConfigureAwait(false);
        if (selection.Source is null)
        {
            throw new SourceUnavailableException(selection);
        }

        var messages = await selection.Source
            .FetchAsync(request, cancellationToken)
            .ConfigureAwait(false);

        _logger.Information(
            "Fetched {Count} messages for group {Group} from {Source}.",
            messages.Count,
            request.GroupIdentifier,
            selection.Source.SourceName);

        return new SourceFetchResult(selection.Source.SourceName, selection.Summary, messages);
    }

    public IReadOnlyList<ExtractionRule> ResolveRules(string? ruleLibraryId)
    {
        if (!string.IsNullOrWhiteSpace(ruleLibraryId))
        {
            var library = RuleLibraries.Load(ruleLibraryId);
            if (library is not null)
            {
                return library.Rules;
            }
        }

        var defaultLibrary = RuleLibraries.LoadAll()
            .FirstOrDefault(candidate => candidate.IsDefault);
        return defaultLibrary is null ? Array.Empty<ExtractionRule>() : defaultLibrary.Rules;
    }

    private static void SetOwnWindowVisible(bool visible)
    {
        var window = Application.Current?.MainWindow;
        if (window is null)
        {
            return;
        }

        window.Dispatcher.Invoke(() =>
        {
            window.Visibility = visible ? Visibility.Visible : Visibility.Hidden;
            if (visible)
            {
                window.Activate();
            }
        });
    }
}

public sealed record SourceFetchResult(
    string SourceName,
    string Summary,
    IReadOnlyList<RawMessage> Messages);
