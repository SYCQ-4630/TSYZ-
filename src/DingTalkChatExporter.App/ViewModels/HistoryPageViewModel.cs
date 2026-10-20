using System.Collections.ObjectModel;
using System.IO;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using DingTalkChatExporter.App.Services;
using DingTalkChatExporter.Core.Management;
using Serilog;

namespace DingTalkChatExporter.App.ViewModels;

public sealed record ImportHistoryRow(
    string ImportedAt,
    string DisplayName,
    string FilePath,
    string RuleLibraryName);

public sealed record RemovedTableRow(
    Guid EntryId,
    string DisplayName,
    string FilePath,
    string RemovedAt,
    bool FileExists)
{
    public string Availability => FileExists ? "文件存在，可恢复" : "原文件已不存在";
}

public sealed record ExportStatsRow(
    string ExportedAt,
    string SourceName,
    string OutputPath,
    int ExportedCount,
    string Mode,
    long DurationMs,
    int LowConfidenceCount);

public sealed partial class HistoryPageViewModel : PageViewModelBase, IActivatablePage
{
    private const string AllColumnsLabel = "（全部列）";

    private readonly TableManager _tables;
    private readonly SettingsService _settings;
    private readonly WorkbenchService _workbench;
    private readonly IDialogService _dialogs;
    private readonly ILogger _logger;

    [ObservableProperty]
    private string _statusMessage = "导入、移除与导出记录分别保存在独立文件中。";

    public HistoryPageViewModel(
        TableManager tables,
        SettingsService settings,
        WorkbenchService workbench,
        IDialogService dialogs,
        ILogger logger)
        : base("history", "导入与导出历史", "查看表格导入记录、导出统计与回收站。")
    {
        _tables = tables;
        _settings = settings;
        _workbench = workbench;
        _dialogs = dialogs;
        _logger = logger.ForContext<HistoryPageViewModel>();
        Refresh();
    }

    public ObservableCollection<ImportHistoryRow> Imports { get; } = new();

    public ObservableCollection<RemovedTableRow> Removed { get; } = new();

    public ObservableCollection<ExportStatsRow> Exports { get; } = new();

    public void OnActivated() => Refresh();

    [RelayCommand]
    private void Refresh()
    {
        var ruleNames = _workbench.RuleLibraries.LoadAll()
            .ToDictionary(library => library.Id, library => library.Name, StringComparer.Ordinal);

        Imports.Clear();
        foreach (var entry in _tables.ImportHistory)
        {
            Imports.Add(new ImportHistoryRow(
                entry.ImportedAt.ToLocalTime().ToString("yyyy-MM-dd HH:mm"),
                entry.DisplayName,
                entry.FilePath,
                entry.BoundRuleLibraryId is not null
                && ruleNames.TryGetValue(entry.BoundRuleLibraryId, out var name)
                    ? name
                    : "未绑定"));
        }

        Removed.Clear();
        foreach (var entry in _tables.RemovedTables)
        {
            Removed.Add(new RemovedTableRow(
                entry.Id,
                entry.DisplayName,
                entry.FilePath,
                entry.RemovedAt.ToLocalTime().ToString("yyyy-MM-dd HH:mm"),
                File.Exists(entry.FilePath)));
        }

        Exports.Clear();
        foreach (var entry in _settings.ExportStats)
        {
            Exports.Add(new ExportStatsRow(
                entry.ExportedAt.ToLocalTime().ToString("yyyy-MM-dd HH:mm"),
                entry.SourceName,
                entry.OutputPath,
                entry.ExportedCount,
                entry.Mode,
                entry.DurationMs,
                entry.LowConfidenceCount));
        }

        StatusMessage = $"{Imports.Count} 条导入记录 · {Removed.Count} 个已移除表格 · {Exports.Count} 条导出记录";
    }

    [RelayCommand]
    private void RestoreRemoved(RemovedTableRow? row)
    {
        if (row is null)
        {
            return;
        }

        try
        {
            var table = _tables.RestoreRemoved(row.EntryId);
            Refresh();
            StatusMessage = $"已恢复“{table.DisplayName}”，重新加入表格列表。";
        }
        catch (Exception ex)
        {
            StatusMessage = "恢复失败：" + ex.Message;
            _dialogs.ShowError("恢复表格失败", ex.Message);
        }
    }

    [RelayCommand]
    private void DismissRemoved(RemovedTableRow? row)
    {
        if (row is null)
        {
            return;
        }

        if (!_dialogs.Confirm(
                "移除记录",
                "只移除这条回收站记录，不删除磁盘上的 Excel 文件。是否继续？"))
        {
            return;
        }

        _tables.DismissRemoved(row.EntryId);
        Refresh();
    }

    [RelayCommand]
    private void ClearImports()
    {
        if (!_dialogs.Confirm("清空导入记录", "只清空列表记录，不会删除任何 Excel 文件。是否继续？"))
        {
            return;
        }

        _tables.ClearImportHistory();
        Refresh();
    }

    [RelayCommand]
    private void ClearExports()
    {
        if (!_dialogs.Confirm("清空导出统计", "只清空统计记录，不会删除已导出的文件。是否继续？"))
        {
            return;
        }

        _settings.ClearExportStats();
        Refresh();
    }

    [RelayCommand]
    private void OpenFile(RemovedTableRow? row)
    {
        if (row is null || !File.Exists(row.FilePath))
        {
            StatusMessage = "文件已不存在。";
            return;
        }

        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = row.FilePath,
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            StatusMessage = "无法打开文件：" + ex.Message;
        }
    }
}
