using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Text.Json;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Serilog;
using TicketTool.App.Services;
using TicketTool.Core.Data;
using TicketTool.Core.Diagnostics;
using TicketTool.Core.Models;

namespace TicketTool.App.ViewModels;

/// <summary>诊断面板：日志过滤、完整性检查、备份与诊断包。</summary>
public sealed partial class DiagnosticsViewModel : ObservableObject
{
    private readonly LogReader _logReader;
    private readonly DiagnosticsDataService _data;
    private readonly BackupService _backup;
    private readonly DiagnosticBundleService _bundles;
    private readonly SyncQueueRepository _queue;
    private readonly IDialogService _dialogs;
    private readonly ILogger _logger;

    [ObservableProperty]
    private string? _levelFilter = "全部";

    [ObservableProperty]
    private string _moduleFilter = string.Empty;

    [ObservableProperty]
    private string _keywordFilter = string.Empty;

    [ObservableProperty]
    private string _summary = "正在加载诊断信息…";

    [ObservableProperty]
    private string _integrityText = "尚未检查";

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private SyncQueueItem? _selectedSyncItem;

    public DiagnosticsViewModel(
        LogReader logReader,
        DiagnosticsDataService data,
        BackupService backup,
        DiagnosticBundleService bundles,
        SyncQueueRepository queue,
        IDialogService dialogs,
        ILogger? logger = null)
    {
        _logReader = logReader;
        _data = data;
        _backup = backup;
        _bundles = bundles;
        _queue = queue;
        _dialogs = dialogs;
        _logger = logger ?? Log.ForContext<DiagnosticsViewModel>();
        LevelOptions = new[] { "全部", "Information", "Warning", "Error", "Fatal" };
    }

    public ObservableCollection<LogEntry> Logs { get; } = new();

    public ObservableCollection<SyncQueueItem> SyncItems { get; } = new();

    public IReadOnlyList<string> LevelOptions { get; }

    public string LogsDirectory => _logReader.LogsDirectory;

    public string DatabasePath => _data.DatabasePath;

    /// <summary>首次加载。</summary>
    public async Task InitializeAsync()
    {
        await RefreshAsync();
    }

    [RelayCommand]
    private async Task RefreshAsync()
    {
        if (IsBusy)
        {
            return;
        }
        IsBusy = true;
        try
        {
            var filter = new LogFilter
            {
                Level = LevelFilter == "全部" ? null : LevelFilter,
                Module = ModuleFilter,
                Keyword = KeywordFilter,
                Limit = 2000
            };
            var entries = await _logReader.ReadAsync(filter).ConfigureAwait(true);
            Logs.Clear();
            foreach (var entry in entries)
            {
                Logs.Add(entry);
            }

            Summary = await _data.BuildSummaryAsync().ConfigureAwait(true);
            var items = await _queue.GetCandidatesAsync(500, true).ConfigureAwait(true);
            SyncItems.Clear();
            foreach (var item in items.Where(i => i.Status != SyncStatus.Synced))
            {
                SyncItems.Add(item);
            }
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "DiagnosticsRefreshFailed");
            _dialogs.Error("诊断信息加载失败", ex.Message);
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task QuickCheckAsync()
    {
        var result = await _backup.QuickCheckAsync().ConfigureAwait(true);
        IntegrityText = result.Ok
            ? $"检查通过（{result.CheckedAt:HH:mm:ss}）"
            : $"发现异常：{result.Message}";
        if (!result.Ok)
        {
            _dialogs.Error("数据完整性检查", "数据库完整性检查未通过。请先导出诊断包，并联系维护人员从备份恢复。\n\n" + result.Message);
        }
        else
        {
            _dialogs.Info("数据完整性检查", "数据库完整性检查通过。");
        }
    }

    [RelayCommand]
    private async Task BackupNowAsync()
    {
        try
        {
            IsBusy = true;
            var path = await _backup.BackupAsync().ConfigureAwait(true);
            _dialogs.Info("备份完成", "已完成数据库备份：\n" + path);
            IntegrityText = "备份完成";
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "ManualBackupFailed");
            _dialogs.Error("备份失败", ex.Message);
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task ExportBundleAsync()
    {
        var target = _dialogs.SaveFile(
            "导出诊断包",
            "压缩包 (*.zip)|*.zip",
            $"TicketTool-diagnostics-{DateTime.Now:yyyyMMdd-HHmmss}.zip");
        if (target is null)
        {
            return;
        }

        try
        {
            IsBusy = true;
            var result = await _bundles.ExportAsync(target).ConfigureAwait(true);
            _dialogs.Info("诊断包已导出", $"路径：\n{result.FilePath}\n\n共包含 {result.FileCount} 个文件。");
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "DiagnosticBundleFailed");
            _dialogs.Error("诊断包导出失败", ex.Message);
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task RetrySyncAsync(SyncQueueItem? item)
    {
        if (item is null)
        {
            return;
        }

        try
        {
            await _queue.RetryFailedAsync(item.Id).ConfigureAwait(true);
            _dialogs.Info("已重新排队", "该工单已重新加入同步队列，关闭占用的表格后会自动写入。");
            await RefreshAsync().ConfigureAwait(true);
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "RetrySyncFailed");
            _dialogs.Error("重试失败", ex.Message);
        }
    }

    [RelayCommand]
    private async Task RetrySelectedAsync()
    {
        if (SelectedSyncItem is not null)
        {
            await RetrySyncAsync(SelectedSyncItem).ConfigureAwait(true);
        }
    }

    [RelayCommand]
    private void OpenLogsFolder()
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = "explorer.exe",
                Arguments = $"\"{_logReader.LogsDirectory}\"",
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "OpenLogsFolderFailed");
        }
    }
}
