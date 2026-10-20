using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using DingTalkChatExporter.App.Services;
using DingTalkChatExporter.Core.Management;
using DingTalkChatExporter.Core.Models;
using DingTalkChatExporter.Core.Services;
using DingTalkChatExporter.Export;
using Serilog;

namespace DingTalkChatExporter.App.ViewModels;

public sealed record ExportModeChoice(ExportWriteMode Mode, string Name);

public sealed record ExportPreviewRow(
    string LocalTime,
    string SenderName,
    string Content,
    string FieldsText,
    string ConfidenceHint);

/// <summary>
/// Template-based export page. Values are always re-extracted from the restored
/// snapshot with the selected rule library; nothing is written back to DingTalk.
/// </summary>
public sealed partial class ExportPageViewModel : PageViewModelBase, IActivatablePage
{
    private readonly SnapshotService _snapshots;
    private readonly SettingsService _settings;
    private readonly WorkbenchService _workbench;
    private readonly IDialogService _dialogs;
    private readonly ExcelTemplateExporter _exporter = new();
    private readonly RuleEngine _engine = new();
    private readonly ILogger _logger;

    private GroupPreviewState? _preview;
    private IReadOnlyList<ExportRow> _rows = Array.Empty<ExportRow>();

    [ObservableProperty]
    private string _templatePath = string.Empty;

    [ObservableProperty]
    private string _outputPath = string.Empty;

    [ObservableProperty]
    private string _sheetName = string.Empty;

    [ObservableProperty]
    private ExportModeChoice? _selectedMode;

    [ObservableProperty]
    private RuleLibraryChoice? _selectedLibrary;

    [ObservableProperty]
    private bool _backupExisting = true;

    [ObservableProperty]
    private bool _highlightLowConfidence = true;

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private string _statusMessage = "从快照读取数据，选择模板后即可预检并导出。";

    [ObservableProperty]
    private string _sourceSummary = "尚未加载快照";

    [ObservableProperty]
    private string _preflightText = "尚未预检";

    [ObservableProperty]
    private string _lastResultPath = string.Empty;

    public ExportPageViewModel(
        SnapshotService snapshots,
        SettingsService settings,
        WorkbenchService workbench,
        IDialogService dialogs,
        ILogger logger)
        : base("export", "导出", "按模板将快照导出为 Excel，并查看预览与统计。")
    {
        _snapshots = snapshots;
        _settings = settings;
        _workbench = workbench;
        _dialogs = dialogs;
        _logger = logger.ForContext<ExportPageViewModel>();

        Modes =
        [
            new ExportModeChoice(ExportWriteMode.Append, "追加到现有数据之后"),
            new ExportModeChoice(ExportWriteMode.Overwrite, "从表头下方覆盖写入")
        ];
        SelectedMode = Modes[0];

        RefreshLibraries();
        TemplatePath = snapshots.Current.CurrentTemplatePath ?? string.Empty;
        BackupExisting = settings.Features.BackupBeforeExport;
        HighlightLowConfidence = settings.Features.HighlightLowConfidenceInExport;
        LoadSnapshot();
    }

    public ObservableCollection<ExportModeChoice> Modes { get; }

    public ObservableCollection<RuleLibraryChoice> RuleLibraries { get; } = new();

    public void OnActivated()
    {
        RefreshLibraries();
        LoadSnapshot();
    }

    public ObservableCollection<ExportPreviewRow> PreviewRows { get; } = new();

    public bool HasRows => PreviewRows.Count > 0;

    public string RowSummary => PreviewRows.Count == 0
        ? "没有可导出的快照行"
        : $"{PreviewRows.Count} 行 · {(_preview?.DataSourceName ?? "未知来源")}";

    partial void OnIsBusyChanged(bool value)
    {
        ExportCommand.NotifyCanExecuteChanged();
        PreflightCommand.NotifyCanExecuteChanged();
    }

    private bool CanRun() => !IsBusy;

    partial void OnSelectedLibraryChanged(RuleLibraryChoice? value)
    {
        if (_preview is not null)
        {
            LoadSnapshot();
        }
    }

    [RelayCommand]
    private void PickTemplate()
    {
        var path = _dialogs.PickOpenFile(
            "选择 Excel 模板",
            "Excel 模板 (*.xlsx;*.xlsm)|*.xlsx;*.xlsm|所有文件 (*.*)|*.*");
        if (path is null)
        {
            return;
        }

        TemplatePath = path;
        PreflightText = "模板已选择，点击“预检”查看写入位置。";
        try
        {
            _snapshots.SetWorkbenchSelection(
                _snapshots.Current.CurrentGroupIdentifier,
                _snapshots.Current.CurrentDataSource,
                path,
                SelectedLibrary?.Id ?? _snapshots.Current.CurrentRuleLibraryId);
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "Failed to persist template selection.");
        }
    }

    [RelayCommand]
    private void PickOutput()
    {
        var name = string.IsNullOrWhiteSpace(_snapshots.Current.CurrentGroupIdentifier)
            ? "聊天记录导出-" + DateTime.Now.ToString("yyyyMMdd-HHmm") + ".xlsx"
            : _snapshots.Current.CurrentGroupIdentifier + "-" + DateTime.Now.ToString("yyyyMMdd-HHmm") + ".xlsx";
        var path = _dialogs.PickSaveFile(
            "选择导出文件",
            "Excel 工作簿 (*.xlsx)|*.xlsx",
            name);
        if (path is null)
        {
            return;
        }

        OutputPath = path;
    }

    [RelayCommand]
    private void RefreshSnapshot()
    {
        LoadSnapshot();
        StatusMessage = _preview is null
            ? "当前快照中没有预览数据，请先在“会话与快照”页面抓取。"
            : "已重新加载当前群聊的快照预览。";
    }

    [RelayCommand]
    private void RefreshLibraries()
    {
        var previous = SelectedLibrary?.Id ?? _snapshots.Current.CurrentRuleLibraryId ?? string.Empty;
        RuleLibraries.Clear();
        RuleLibraries.Add(new RuleLibraryChoice(string.Empty, "使用快照中的字段（不重新提取）"));
        foreach (var library in _workbench.RuleLibraries.LoadAll().OrderBy(item => item.Name))
        {
            RuleLibraries.Add(new RuleLibraryChoice(library.Id, library.Name));
        }

        SelectedLibrary = RuleLibraries.FirstOrDefault(item => item.Id == previous) ?? RuleLibraries[0];
    }

    [RelayCommand(CanExecute = nameof(CanRun))]
    private void Preflight()
    {
        try
        {
            var options = BuildOptions();
            _rows = BuildRows();
            var result = _exporter.Preflight(options, _rows);
            PreflightText =
                $"工作表：{result.SheetName}\n" +
                $"写入起点：第 {result.StartRow} 行\n" +
                $"将写入：{result.RowsToWrite} 行；覆盖已有：{result.ExistingRowsToOverwrite} 行\n" +
                (result.UnmappedFields.Count == 0
                    ? "所有规则字段都能在模板中找到列。"
                    : "未匹配字段：" + string.Join("、", result.UnmappedFields));
            StatusMessage = "预检完成。请确认写入位置后再导出。";
        }
        catch (Exception ex)
        {
            PreflightText = "预检失败";
            StatusMessage = "预检失败：" + ex.Message;
        }
    }

    [RelayCommand(CanExecute = nameof(CanRun))]
    private async Task ExportAsync()
    {
        if (_preview is null)
        {
            StatusMessage = "没有快照数据可导出。";
            return;
        }

        ExportOptions options;
        try
        {
            options = BuildOptions();
            _rows = BuildRows();
            var preflight = _exporter.Preflight(options, _rows);
            var confirmText =
                $"模板：{options.TemplatePath}\n" +
                $"输出：{options.OutputPath}\n" +
                $"工作表：{preflight.SheetName}\n" +
                $"写入起点：第 {preflight.StartRow} 行，共 {preflight.RowsToWrite} 行\n" +
                (options.BackupExisting && File.Exists(options.OutputPath)
                    ? "输出文件已存在，将先创建 .bak 备份。\n"
                    : string.Empty) +
                "\n是否开始导出？";
            if (!_dialogs.Confirm("确认导出", confirmText))
            {
                StatusMessage = "已取消导出。";
                return;
            }
        }
        catch (Exception ex)
        {
            StatusMessage = "导出参数有误：" + ex.Message;
            return;
        }

        IsBusy = true;
        StatusMessage = "正在写入 Excel…";
        try
        {
            var result = await Task.Run(() => _exporter.Export(options, _rows)).ConfigureAwait(true);
            LastResultPath = result.OutputPath;
            _settings.RecordExport(new ExportStats
            {
                RunId = options.RunId,
                ExportedAt = DateTimeOffset.UtcNow,
                TemplatePath = options.TemplatePath,
                OutputPath = result.OutputPath,
                SourceName = options.SourceName,
                RawCount = options.RawCount,
                DedupedCount = options.DedupedCount,
                ExportedCount = result.WrittenRows,
                LowConfidenceCount = result.LowConfidenceCells,
                Mode = options.Mode.ToString(),
                StartRow = result.StartRow,
                DurationMs = (long)result.Duration.TotalMilliseconds
            });

            StatusMessage = result.BackupPath is null
                ? $"导出完成：{result.WrittenRows} 行写入第 {result.StartRow} 行起。"
                : $"导出完成，原文件已备份到 {result.BackupPath}。";
            _dialogs.ShowInfo(
                "导出完成",
                $"已写入 {result.WrittenRows} 行。\n\n{result.OutputPath}");
        }
        catch (Exception ex)
        {
            StatusMessage = "导出失败：" + ex.Message;
            _dialogs.ShowError("导出失败", ex.Message);
            _logger.Warning(ex, "Excel export failed.");
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private void UseLastResultFolder()
    {
        if (string.IsNullOrWhiteSpace(LastResultPath))
        {
            return;
        }

        try
        {
            var argument = File.Exists(LastResultPath)
                ? $"/select,\"{LastResultPath}\""
                : $"\"{Path.GetDirectoryName(LastResultPath)}\"";
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = "explorer.exe",
                Arguments = argument,
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            StatusMessage = "无法打开文件夹：" + ex.Message;
        }
    }

    private ExportOptions BuildOptions()
    {
        if (string.IsNullOrWhiteSpace(TemplatePath))
        {
            throw new InvalidOperationException("请先选择 Excel 模板。");
        }

        if (!File.Exists(TemplatePath))
        {
            throw new FileNotFoundException("模板文件不存在。", TemplatePath);
        }

        if (string.IsNullOrWhiteSpace(OutputPath))
        {
            throw new InvalidOperationException("请先选择导出文件位置。");
        }

        var fieldNames = ResolveFieldNames();
        if (fieldNames.Count == 0)
        {
            throw new InvalidOperationException("没有可导出的字段。请先配置规则库，或在快照中保留提取字段。");
        }

        return new ExportOptions(
            TemplatePath: TemplatePath,
            OutputPath: OutputPath,
            SheetName: string.IsNullOrWhiteSpace(SheetName) ? null : SheetName.Trim(),
            Mode: SelectedMode?.Mode ?? ExportWriteMode.Append,
            FieldNames: fieldNames,
            HighlightLowConfidence: HighlightLowConfidence,
            BackupExisting: BackupExisting,
            SourceName: _preview?.GroupIdentifier ?? "未知群聊",
            FetchedAt: _preview?.PreviewedAt,
            RawCount: _preview?.Messages.Count ?? 0,
            DedupedCount: _rows.Count,
            RunId: Guid.NewGuid().ToString("N"));
    }

    private IReadOnlyList<ExportRow> BuildRows()
    {
        if (_preview is null)
        {
            return Array.Empty<ExportRow>();
        }

        var rules = SelectedLibrary is { Id.Length: > 0 }
            ? _workbench.ResolveRules(SelectedLibrary.Id)
            : Array.Empty<ExtractionRule>();

        var rows = new List<ExportRow>(_preview.Messages.Count);
        foreach (var item in _preview.Messages.OrderBy(message => message.Message.SentAt))
        {
            var fields = rules.Count > 0
                ? _engine.Extract(item.Message, rules)
                : item.ExtractedFields;
            rows.Add(new ExportRow(item.Message, fields));
        }

        return rows;
    }

    private List<string> ResolveFieldNames()
    {
        if (SelectedLibrary is { Id.Length: > 0 })
        {
            var rules = _workbench.ResolveRules(SelectedLibrary.Id);
            if (rules.Count > 0)
            {
                return rules
                    .Select(rule => rule.FieldName)
                    .Where(name => !string.IsNullOrWhiteSpace(name))
                    .Distinct(StringComparer.Ordinal)
                    .ToList();
            }
        }

        return (_preview?.Messages ?? new List<PreviewMessage>())
            .SelectMany(item => item.ExtractedFields.Keys)
            .Distinct(StringComparer.Ordinal)
            .ToList();
    }

    private void LoadSnapshot()
    {
        _preview = _snapshots.Current.GetPreview(_snapshots.Current.CurrentGroupIdentifier);
        PreviewRows.Clear();
        if (_preview is null)
        {
            SourceSummary = "当前快照中没有预览数据";
            OnPropertyChanged(nameof(HasRows));
            OnPropertyChanged(nameof(RowSummary));
            return;
        }

        var rules = SelectedLibrary is { Id.Length: > 0 }
            ? _workbench.ResolveRules(SelectedLibrary.Id)
            : Array.Empty<ExtractionRule>();
        foreach (var item in _preview.Messages.OrderBy(message => message.Message.SentAt))
        {
            var fields = rules.Count > 0
                ? _engine.Extract(item.Message, rules)
                : item.ExtractedFields;
            var text = fields
                .Where(pair => !string.IsNullOrWhiteSpace(pair.Value.Value))
                .Select(pair => $"{pair.Key}={pair.Value.Value}")
                .ToList();
            var low = fields.Count(pair => pair.Value.Confidence == Confidence.Low);
            PreviewRows.Add(new ExportPreviewRow(
                item.Message.SentAt == DateTimeOffset.MinValue
                    ? string.Empty
                    : item.Message.SentAt.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss"),
                item.Message.SenderName,
                item.Message.Content,
                string.Join("；", text),
                low == 0 ? string.Empty : $"{low} 个低置信度"));
        }

        SourceSummary = $"{_preview.GroupIdentifier} · {PreviewRows.Count} 行 · {_preview.DataSourceName}";
        OnPropertyChanged(nameof(HasRows));
        OnPropertyChanged(nameof(RowSummary));
        StatusMessage = PreviewRows.Count == 0
            ? "快照存在但没有消息，请重新抓取。"
            : "快照已加载，可预检后导出。";
    }
}
