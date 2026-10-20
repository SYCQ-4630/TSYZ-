using System.Collections.ObjectModel;
using System.Linq;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using DingTalkChatExporter.App.Services;
using DingTalkChatExporter.Core.Management;
using DingTalkChatExporter.Core.Models;
using DingTalkChatExporter.Core.Services;
using DingTalkChatExporter.DataSources.Dws;
using DingTalkChatExporter.DataSources.Routing;
using Serilog;

namespace DingTalkChatExporter.App.ViewModels;

public sealed record GroupLookupItem(string Identifier, string Name)
{
    public string Display => string.IsNullOrWhiteSpace(Name) ? Identifier : $"{Name} · {Identifier}";
}

public sealed record RuleLibraryChoice(string Id, string Name);

public sealed record PreviewRow(
    DateTimeOffset SentAt,
    string SenderName,
    string MessageType,
    string Content,
    string FieldsText,
    string LocalTime)
{
    public string ConfidenceHint { get; init; } = string.Empty;
}

/// <summary>
/// Snapshot workbench: source status, optional group discovery, fetch, de-duplicate,
/// rule extraction and per-group preview restoration.
/// </summary>
public sealed partial class SnapshotPageViewModel : PageViewModelBase, IActivatablePage
{
    private readonly WorkbenchService _workbench;
    private readonly SnapshotService _snapshots;
    private readonly SettingsService _settings;
    private readonly RuleEngine _ruleEngine = new();
    private readonly ILogger _logger;

    private CancellationTokenSource? _fetchCts;
    private IReadOnlyList<PreviewMessage> _previewMessages = Array.Empty<PreviewMessage>();
    private string? _previewGroupIdentifier;

    [ObservableProperty]
    private string _groupIdentifier = string.Empty;

    [ObservableProperty]
    private string _groupQuery = string.Empty;

    [ObservableProperty]
    private DateTime? _startDate = DateTime.Today.AddDays(-7);

    [ObservableProperty]
    private DateTime? _endDate = DateTime.Today;

    [ObservableProperty]
    private int _maxResults = 500;

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private bool _useIncremental;

    [ObservableProperty]
    private string _statusMessage = "选择群聊后可抓取原始消息快照。";

    [ObservableProperty]
    private string _sourceSummary = "尚未检查数据源";

    [ObservableProperty]
    private string _dwsStatusText = "未检查";

    [ObservableProperty]
    private string _flaUiStatusText = "未检查";

    [ObservableProperty]
    private string _strategyText = string.Empty;

    [ObservableProperty]
    private int _rawCount;

    [ObservableProperty]
    private int _uniqueCount;

    [ObservableProperty]
    private int _duplicateCount;

    [ObservableProperty]
    private RuleLibraryChoice? _selectedLibrary;

    [ObservableProperty]
    private GroupLookupItem? _selectedLookupResult;

    public SnapshotPageViewModel(
        WorkbenchService workbench,
        SnapshotService snapshots,
        SettingsService settings,
        ILogger logger)
        : base("snapshot", "会话与快照", "选择群聊，抓取并查看原始消息快照。")
    {
        _workbench = workbench;
        _snapshots = snapshots;
        _settings = settings;
        _logger = logger.ForContext<SnapshotPageViewModel>();

        Messages.CollectionChanged += (_, _) => OnPropertyChanged(nameof(HasMessages));
        GroupResults.CollectionChanged += (_, _) => OnPropertyChanged(nameof(HasGroupResults));

        UseIncremental = settings.Features.EnableIncrementalRead;
        RefreshLibraries();
        RestorePreview(snapshots.Current);
        RefreshRecentGroups();
    }

    public void OnActivated()
    {
        RefreshLibraries();
        RefreshRecentGroups();
    }

    public ObservableCollection<GroupLookupItem> GroupResults { get; } = new();

    public ObservableCollection<PreviewRow> Messages { get; } = new();

    public ObservableCollection<RuleLibraryChoice> RuleLibraries { get; } = new();

    public ObservableCollection<RecentGroup> RecentGroups { get; } = new();

    public bool HasMessages => Messages.Count > 0;

    public bool HasGroupResults => GroupResults.Count > 0;

    public string CountSummary =>
        RawCount == 0 ? "尚无快照" : $"原始 {RawCount} 条 · 去重后 {UniqueCount} 条 · 重复 {DuplicateCount} 条";

    partial void OnRawCountChanged(int value) => OnPropertyChanged(nameof(CountSummary));

    partial void OnUniqueCountChanged(int value) => OnPropertyChanged(nameof(CountSummary));

    partial void OnDuplicateCountChanged(int value) => OnPropertyChanged(nameof(CountSummary));

    partial void OnIsBusyChanged(bool value)
    {
        FetchCommand.NotifyCanExecuteChanged();
        CancelCommand.NotifyCanExecuteChanged();
    }

    private bool CanFetch() => !IsBusy;

    private bool CanCancel() => IsBusy;

    [RelayCommand]
    private async Task RefreshStatusAsync()
    {
        if (IsBusy)
        {
            return;
        }

        IsBusy = true;
        StatusMessage = "正在检查数据源…";
        try
        {
            var status = await _workbench.GetStatusAsync(forceRefresh: true, CancellationToken.None);
            ApplyRouterStatus(status);
            StatusMessage = status.Dws.IsAvailable
                ? "dws 已认证，可直接抓取。"
                : "dws 不可用，请查看下方原因；如已启用实验性降级，可尝试 FlaUI。";
        }
        catch (Exception ex)
        {
            StatusMessage = "数据源检查失败：" + ex.Message;
            _logger.Warning(ex, "Failed to refresh source status.");
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task SearchGroupsAsync()
    {
        if (IsBusy)
        {
            return;
        }

        if (string.IsNullOrWhiteSpace(GroupQuery))
        {
            StatusMessage = "请输入群名称或群标识后再搜索。";
            return;
        }

        IsBusy = true;
        StatusMessage = "正在搜索群聊…";
        GroupResults.Clear();
        try
        {
            var groups = await _workbench.SearchGroupsAsync(GroupQuery, CancellationToken.None);
            foreach (var group in groups)
            {
                GroupResults.Add(new GroupLookupItem(group.Identifier, group.Name));
            }

            if (groups.Count == 0)
            {
                StatusMessage = _workbench.LastGroupSearchFailure
                                ?? "没有找到匹配群聊，可直接手动输入群标识。";
            }
            else
            {
                StatusMessage = $"找到 {groups.Count} 个群聊，选择后即可抓取。";
            }
        }
        catch (Exception ex)
        {
            StatusMessage = "群搜索失败，可直接手动输入群标识：" + ex.Message;
            _logger.Warning(ex, "Group search failed.");
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private void UseGroup(GroupLookupItem? item)
    {
        if (item is null)
        {
            return;
        }

        GroupIdentifier = item.Identifier;
        RestorePreviewForGroup(item.Identifier);
        StatusMessage = $"已选择 {item.Display}。";
    }

    [RelayCommand]
    private void UseRecentGroup(RecentGroup? item)
    {
        if (item is null)
        {
            return;
        }

        GroupIdentifier = item.Identifier;
        RestorePreviewForGroup(item.Identifier);
        StatusMessage = $"已选择最近使用的群聊 {item.DisplayName}。";
    }

    [RelayCommand(CanExecute = nameof(CanFetch))]
    private async Task FetchAsync()
    {
        if (string.IsNullOrWhiteSpace(GroupIdentifier))
        {
            StatusMessage = "请先输入群标识或从搜索结果中选择群聊。";
            return;
        }

        _fetchCts?.Cancel();
        _fetchCts?.Dispose();
        _fetchCts = new CancellationTokenSource();
        var token = _fetchCts.Token;
        IsBusy = true;
        StatusMessage = "正在抓取消息…";
        try
        {
            var group = GroupIdentifier.Trim();
            _settings.SaveFeatures(_settings.Features with { EnableIncrementalRead = UseIncremental });
            var start = ToStartOffset(StartDate);
            var usingWatermark = false;
            if (UseIncremental
                && _snapshots.Current.IncrementalStates.TryGetValue(group, out var state)
                && state.LastFetchedAt is not null)
            {
                start = state.LastFetchedAt.Value.AddSeconds(-2);
                usingWatermark = true;
            }

            var request = new ChatFetchRequest(
                group,
                start,
                ToEndOffset(EndDate),
                Math.Clamp(MaxResults, 1, 5000));

            var fetched = await _workbench.FetchAsync(request, token);
            token.ThrowIfCancellationRequested();

            var previous = new List<PreviewMessage>();
            if (UseIncremental)
            {
                if (string.Equals(_previewGroupIdentifier, group, StringComparison.OrdinalIgnoreCase)
                    && _previewMessages.Count > 0)
                {
                    previous = _previewMessages.ToList();
                }
                else if (_snapshots.Current.GetPreview(group) is { } storedPreview)
                {
                    previous = storedPreview.Messages.ToList();
                }
            }
            var deduplicator = new MessageDeduplicator();
            deduplicator.Deduplicate(previous.Select(item => item.Message), remember: true);
            var dedup = deduplicator.Deduplicate(fetched.Messages);

            var rules = _workbench.ResolveRules(SelectedLibrary?.Id);
            var mergedMessages = previous
                .Select(item => item.Message)
                .Concat(dedup.UniqueMessages)
                .OrderBy(message => message.SentAt)
                .ToList();
            var preview = new List<PreviewMessage>(mergedMessages.Count);
            Messages.Clear();
            foreach (var message in mergedMessages)
            {
                var fields = _ruleEngine.Extract(message, rules);
                preview.Add(new PreviewMessage
                {
                    Message = message,
                    ExtractedFields = fields
                });
                Messages.Add(ToRow(message, fields));
            }

            _previewMessages = preview;
            _previewGroupIdentifier = group;
            RawCount = fetched.Messages.Count;
            UniqueCount = mergedMessages.Count;
            DuplicateCount = dedup.DuplicateCount;
            SourceSummary = $"{fetched.SourceName} · {fetched.Summary}";

            PersistPreview(fetched.SourceName);
            StatusMessage = Messages.Count == 0
                ? "抓取完成，但没有解析到消息。请确认群标识、时间范围或 dws 输出格式。"
                : usingWatermark
                    ? $"增量抓取完成（从上次水位起）：本次 {fetched.Messages.Count} 条，合并后 {Messages.Count} 条。"
                    : $"抓取完成：{CountSummary}。";
        }
        catch (OperationCanceledException)
        {
            StatusMessage = "抓取已取消。";
        }
        catch (SourceUnavailableException ex)
        {
            SourceSummary = ex.Selection.Summary;
            StatusMessage = BuildUnavailableMessage(ex.Selection);
            _logger.Warning("No source available: {Detail}", StatusMessage);
        }
        catch (Exception ex)
        {
            StatusMessage = "抓取失败：" + ex.Message;
            _logger.Warning(ex, "Message fetch failed for group {Group}.", GroupIdentifier);
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand(CanExecute = nameof(CanCancel))]
    private void Cancel() => _fetchCts?.Cancel();

    [RelayCommand]
    private void ClearPreview()
    {
        Messages.Clear();
        _previewMessages = Array.Empty<PreviewMessage>();
        _previewGroupIdentifier = null;
        RawCount = 0;
        UniqueCount = 0;
        DuplicateCount = 0;
        StatusMessage = "当前预览已清空，磁盘上的快照文件未被修改。";
    }

    [RelayCommand]
    private void RefreshLibraries()
    {
        var previousId = SelectedLibrary?.Id
                         ?? _snapshots.Current.CurrentRuleLibraryId
                         ?? string.Empty;
        RuleLibraries.Clear();
        RuleLibraries.Add(new RuleLibraryChoice(string.Empty, "不使用规则库（仅原始消息）"));
        foreach (var library in _workbench.RuleLibraries.LoadAll().OrderBy(item => item.Name))
        {
            RuleLibraries.Add(new RuleLibraryChoice(library.Id, library.Name));
        }

        SelectedLibrary = RuleLibraries.FirstOrDefault(item => item.Id == previousId) ?? RuleLibraries[0];
    }

    private void ApplyRouterStatus(RouterStatus status)
    {
        StrategyText = status.Strategy switch
        {
            RouterStrategy.DwsOnly => "仅 dws",
            RouterStrategy.FlaUiOnly => "仅 FlaUI",
            _ => "自动（dws 优先）"
        };
        DwsStatusText = status.Dws.Detail is null
            ? status.Dws.Summary
            : $"{status.Dws.Summary} — {status.Dws.Detail}";
        FlaUiStatusText = status.FlaUiAvailable
            ? "FlaUI 可用（实验性）"
            : status.FlaUiReason ?? "FlaUI 不可用";
        SourceSummary = status.Dws.IsAvailable
            ? "dws 可用"
            : status.FlaUiAvailable
                ? "dws 不可用，FlaUI 可用（实验性）"
                : "无可用数据源";
    }

    private static string BuildUnavailableMessage(SourceSelection selection)
    {
        var reasons = selection.Failures.Count == 0
            ? selection.ErrorMessage
            : string.Join("；", selection.Failures.Select(failure => $"{failure.SourceName}：{failure.Reason}"));
        return "无可用数据源。" + (reasons is null ? string.Empty : " " + reasons);
    }

    private static DateTimeOffset? ToStartOffset(DateTime? date) =>
        date is null ? null : new DateTimeOffset(date.Value.Date, TimeZoneInfo.Local.GetUtcOffset(date.Value.Date));

    private static DateTimeOffset? ToEndOffset(DateTime? date) =>
        date is null
            ? null
            : new DateTimeOffset(
                date.Value.Date.AddDays(1).AddTicks(-1),
                TimeZoneInfo.Local.GetUtcOffset(date.Value.Date));

    private static PreviewRow ToRow(
        RawMessage message,
        IReadOnlyDictionary<string, ExtractionResult> fields)
    {
        var extracted = fields
            .Where(pair => pair.Value.Confidence != Confidence.None && !string.IsNullOrWhiteSpace(pair.Value.Value))
            .Select(pair => $"{pair.Key}={pair.Value.Value}")
            .ToList();
        var lowConfidence = fields.Count(pair => pair.Value.Confidence == Confidence.Low);
        return new PreviewRow(
            message.SentAt,
            message.SenderName,
            message.MessageType,
            message.Content,
            string.Join("；", extracted),
            message.SentAt == DateTimeOffset.MinValue
                ? string.Empty
                : message.SentAt.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss"))
        {
            ConfidenceHint = lowConfidence == 0 ? string.Empty : $"{lowConfidence} 个低置信度"
        };
    }

    private void PersistPreview(string sourceName)
    {
        var group = GroupIdentifier.Trim();
        var now = DateTimeOffset.UtcNow;
        _snapshots.SetPreview(new GroupPreviewState
        {
            GroupIdentifier = group,
            PreviewedAt = now,
            DataSourceName = sourceName,
            RuleLibraryId = SelectedLibrary?.Id,
            Messages = _previewMessages.ToList()
        });
        _snapshots.SetWorkbenchSelection(
            group,
            sourceName,
            _snapshots.Current.CurrentTemplatePath,
            SelectedLibrary?.Id);

        if (UseIncremental && _previewMessages.Count > 0)
        {
            var latest = _previewMessages
                .OrderBy(item => item.Message.SentAt)
                .Last()
                .Message;
            _snapshots.SetIncrementalState(new GroupIncrementalState
            {
                GroupIdentifier = group,
                LastFetchedAt = latest.SentAt == DateTimeOffset.MinValue ? now : latest.SentAt,
                LastFetchedMessageId = latest.MessageId
            });
        }

        _settings.AddRecentGroup(group, group);
        RefreshRecentGroups();
    }

    private void RefreshRecentGroups()
    {
        RecentGroups.Clear();
        foreach (var group in _settings.Ui.RecentGroups)
        {
            RecentGroups.Add(group);
        }
    }

    private void RestorePreview(AppSnapshot snapshot)
    {
        var preview = snapshot.GetPreview(snapshot.CurrentGroupIdentifier);
        if (preview is null)
        {
            return;
        }

        GroupIdentifier = preview.GroupIdentifier;
        ApplyPreview(preview);
    }

    private void RestorePreviewForGroup(string groupIdentifier)
    {
        var preview = _snapshots.Current.GetPreview(groupIdentifier);
        if (preview is null)
        {
            Messages.Clear();
            _previewMessages = Array.Empty<PreviewMessage>();
            _previewGroupIdentifier = null;
        _previewGroupIdentifier = null;
            RawCount = 0;
            UniqueCount = 0;
            DuplicateCount = 0;
            SourceSummary = "尚未抓取该群聊";
            return;
        }

        ApplyPreview(preview);
    }

    private void ApplyPreview(GroupPreviewState preview)
    {
        SourceSummary = preview.DataSourceName ?? SourceSummary;
        _previewMessages = preview.Messages;
        _previewGroupIdentifier = preview.GroupIdentifier;
        Messages.Clear();
        foreach (var item in preview.Messages.OrderBy(entry => entry.Message.SentAt))
        {
            Messages.Add(ToRow(item.Message, item.ExtractedFields));
        }

        RawCount = preview.Messages.Count;
        UniqueCount = preview.Messages.Count;
        DuplicateCount = 0;
        StatusMessage = $"已恢复 {preview.PreviewedAt.ToLocalTime():yyyy-MM-dd HH:mm} 的快照预览。";
    }

}
