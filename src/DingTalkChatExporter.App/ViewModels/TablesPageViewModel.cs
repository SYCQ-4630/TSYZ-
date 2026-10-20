using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using ClosedXML.Excel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using DingTalkChatExporter.App.Services;
using DingTalkChatExporter.Core.Abstractions;
using DingTalkChatExporter.Core.Management;
using Serilog;

namespace DingTalkChatExporter.App.ViewModels;

public sealed record TableCellViewModel(ManagedTable Table)
{
    public Guid Id => Table.Id;
    public string DisplayName => Table.DisplayName;
    public string Title => IsActive ? "[当前] " + DisplayName : DisplayName;
    public string FilePath => Table.FilePath;
    public bool IsActive => Table.IsActive;
    public string BoundRuleName { get; init; } = "未绑定规则库";
    public string IndexStatusText => Table.IndexStatus switch
    {
        IndexStatus.Ready => "索引就绪",
        IndexStatus.Building => "索引建立中",
        IndexStatus.Stale => "索引已过期",
        IndexStatus.Unavailable => "索引不可用",
        _ => "未建立索引"
    };
    public string IndexedAtText => Table.IndexedAt is null
        ? string.Empty
        : "最近索引 " + Table.IndexedAt.Value.ToLocalTime().ToString("yyyy-MM-dd HH:mm");
    public string Subtitle => $"{IndexStatusText} · {BoundRuleName}";
}

public sealed partial class TablesPageViewModel : PageViewModelBase, IActivatablePage
{
    public const string AllColumnsLabel = "（全部列）";

    private readonly TableManager _tables;
    private readonly ITableIndexService _indexService;
    private readonly WorkbenchService _workbench;
    private readonly IDialogService _dialogs;
    private readonly ILogger _logger;

    [ObservableProperty]
    private TableCellViewModel? _selectedTable;

    [ObservableProperty]
    private string _renameText = string.Empty;

    [ObservableProperty]
    private RuleLibraryChoice? _boundLibrary;

    [ObservableProperty]
    private string _searchQuery = string.Empty;

    [ObservableProperty]
    private string? _searchColumn;

    [ObservableProperty]
    private string _statusMessage = "导入 Excel 表格后可建立本地索引并支持列级搜索。";

    [ObservableProperty]
    private bool _isBusy;

    public TablesPageViewModel(
        TableManager tables,
        ITableIndexService indexService,
        WorkbenchService workbench,
        IDialogService dialogs,
        ILogger logger)
        : base("tables", "表格管理", "管理 Excel 表格、搜索索引与绑定规则库。")
    {
        _tables = tables;
        _indexService = indexService;
        _workbench = workbench;
        _dialogs = dialogs;
        _logger = logger.ForContext<TablesPageViewModel>();

        _tables.Changed += OnTablesChanged;
        RefreshFromManager(null);
    }

    public ObservableCollection<TableCellViewModel> Tables { get; } = new();

    public ObservableCollection<RuleLibraryChoice> RuleLibraries { get; } = new();

    public ObservableCollection<string> Columns { get; } = new();

    public ObservableCollection<TableIndexHit> SearchResults { get; } = new();

    public bool HasSelection => SelectedTable is not null;

    public string SearchScope => string.IsNullOrWhiteSpace(SearchColumn) ? "全部列" : SearchColumn;

    public void OnActivated() => RefreshFromManager(SelectedTable?.Id);

    partial void OnSelectedTableChanged(TableCellViewModel? value)
    {
        OnPropertyChanged(nameof(HasSelection));
        if (value is null)
        {
            RenameText = string.Empty;
            Columns.Clear();
            SearchResults.Clear();
            return;
        }

        RenameText = value.DisplayName;
        LoadColumns(value.Table.FilePath);
        SearchResults.Clear();
        StatusMessage = value.IsActive
            ? $"“{value.DisplayName}”是当前表格。"
            : $"已选择“{value.DisplayName}”。";
        SyncBoundLibrary(value.Table.BoundRuleLibraryId);
    }

    partial void OnIsBusyChanged(bool value)
    {
        BuildIndexCommand.NotifyCanExecuteChanged();
        SearchIndexCommand.NotifyCanExecuteChanged();
    }

    private bool CanRun() => !IsBusy;

    [RelayCommand]
    private void ImportTable()
    {
        var path = _dialogs.PickOpenFile(
            "选择要导入的 Excel 表格",
            "Excel 工作簿 (*.xlsx;*.xlsm)|*.xlsx;*.xlsm|所有文件 (*.*)|*.*");
        if (path is null)
        {
            return;
        }

        try
        {
            var table = _tables.Import(path);
            RefreshFromManager(table.Id);
            StatusMessage = $"已导入“{table.DisplayName}”。建议建立索引后使用搜索。";
        }
        catch (Exception ex)
        {
            StatusMessage = "导入失败：" + ex.Message;
            _dialogs.ShowError("导入表格失败", ex.Message);
            _logger.Warning(ex, "Failed to import table {Path}.", path);
        }
    }

    [RelayCommand]
    private void SetActive()
    {
        if (SelectedTable is null)
        {
            return;
        }

        _tables.SetActive(SelectedTable.Id);
        StatusMessage = $"已将“{SelectedTable.DisplayName}”设为当前表格。";
    }

    [RelayCommand]
    private void Rename()
    {
        if (SelectedTable is null)
        {
            return;
        }

        try
        {
            _tables.Rename(SelectedTable.Id, RenameText);
            StatusMessage = "已重命名表格显示名称，磁盘文件未改名。";
        }
        catch (Exception ex)
        {
            StatusMessage = "重命名失败：" + ex.Message;
        }
    }

    [RelayCommand]
    private async Task RemoveAsync()
    {
        if (SelectedTable is null)
        {
            return;
        }

        var name = SelectedTable.DisplayName;
        await _tables.RemoveAsync(SelectedTable.Id).ConfigureAwait(true);
        StatusMessage = $"已将“{name}”从列表移除；磁盘上的 Excel 与历史记录均保留。";
    }

    [RelayCommand]
    private async Task DeletePhysicallyAsync()
    {
        if (SelectedTable is null)
        {
            return;
        }

        var table = SelectedTable.Table;
        if (!_dialogs.Confirm(
                "确认物理删除",
                $"将从磁盘删除以下文件，且无法通过应用撤销：\n\n{table.FilePath}\n\n" +
                "只删除该 Excel 文件与本地索引；不会修改钉钉数据。是否继续？"))
        {
            return;
        }

        await _tables.DeletePhysicallyAsync(table.Id).ConfigureAwait(true);
        _dialogs.ShowInfo("已删除", "Excel 文件和本地索引已删除。");
        StatusMessage = "已物理删除表格文件与索引。";
    }

    [RelayCommand(CanExecute = nameof(CanRun))]
    private async Task BuildIndexAsync()
    {
        if (SelectedTable is null)
        {
            return;
        }

        IsBusy = true;
        StatusMessage = "正在建立索引，请勿关闭应用…";
        try
        {
            await _tables.BuildIndexAsync(SelectedTable.Id).ConfigureAwait(true);
            StatusMessage = $"索引已建立：{SelectedTable.DisplayName}。";
        }
        catch (Exception ex)
        {
            StatusMessage = "建立索引失败：" + ex.Message;
            _logger.Warning(ex, "Failed to build index for table {TableId}.", SelectedTable.Id);
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand(CanExecute = nameof(CanRun))]
    private async Task SearchIndexAsync()
    {
        if (SelectedTable is null || string.IsNullOrWhiteSpace(SearchQuery))
        {
            StatusMessage = "请选择表格并输入搜索关键词。";
            return;
        }

        if (!_indexService.IndexExists(SelectedTable.Id))
        {
            StatusMessage = "该表格尚未建立索引，请先点击“建立索引”。";
            return;
        }

        IsBusy = true;
        try
        {
            var hits = await _indexService
                .SearchAsync(
                    SelectedTable.Id,
                    SearchQuery,
                    string.IsNullOrWhiteSpace(SearchColumn) || SearchColumn == AllColumnsLabel
                        ? null
                        : SearchColumn,
                    300,
                    CancellationToken.None)
                .ConfigureAwait(true);
            SearchResults.Clear();
            foreach (var hit in hits)
            {
                SearchResults.Add(hit);
            }

            StatusMessage = hits.Count == 0
                ? "没有匹配结果。"
                : $"找到 {hits.Count} 个匹配单元格。";
        }
        catch (Exception ex)
        {
            StatusMessage = "搜索失败：" + ex.Message;
            _logger.Warning(ex, "Index search failed for table {TableId}.", SelectedTable.Id);
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private void ClearSearch()
    {
        SearchQuery = string.Empty;
        SearchResults.Clear();
        StatusMessage = "已清空搜索结果。";
    }

    [RelayCommand]
    private void ApplyBinding()
    {
        if (SelectedTable is null)
        {
            return;
        }

        _tables.BindRuleLibrary(SelectedTable.Id, BoundLibrary?.Id);
        StatusMessage = BoundLibrary is null || BoundLibrary.Id.Length == 0
            ? "已解除规则库绑定。"
            : $"已绑定规则库“{BoundLibrary.Name}”。";
    }

    [RelayCommand]
    private void Refresh()
    {
        RefreshFromManager(SelectedTable?.Id);
        StatusMessage = "已刷新表格列表。";
    }

    private void OnTablesChanged(object? sender, EventArgs e)
    {
        var dispatcher = System.Windows.Application.Current?.Dispatcher;
        if (dispatcher is not null && !dispatcher.CheckAccess())
        {
            dispatcher.Invoke(() => RefreshFromManager(SelectedTable?.Id));
            return;
        }

        RefreshFromManager(SelectedTable?.Id);
    }

    private void RefreshFromManager(Guid? selectId)
    {
        var previous = selectId ?? SelectedTable?.Id;
        var ruleNames = _workbench.RuleLibraries.LoadAll()
            .ToDictionary(library => library.Id, library => library.Name, StringComparer.Ordinal);

        Tables.Clear();
        foreach (var table in _tables.Tables.OrderByDescending(item => item.IsActive).ThenBy(item => item.DisplayName))
        {
            Tables.Add(new TableCellViewModel(table)
            {
                BoundRuleName = table.BoundRuleLibraryId is not null
                                && ruleNames.TryGetValue(table.BoundRuleLibraryId, out var name)
                    ? name
                    : "未绑定规则库"
            });
        }

        SelectedTable = Tables.FirstOrDefault(item => item.Id == previous) ?? Tables.FirstOrDefault();

        RuleLibraries.Clear();
        RuleLibraries.Add(new RuleLibraryChoice(string.Empty, "不绑定规则库"));
        foreach (var library in _workbench.RuleLibraries.LoadAll().OrderByDescending(item => item.IsDefault).ThenBy(item => item.Name))
        {
            RuleLibraries.Add(new RuleLibraryChoice(library.Id, library.Name));
        }

        SyncBoundLibrary(SelectedTable?.Table.BoundRuleLibraryId);
        if (_tables.LastLoadWarning is not null)
        {
            StatusMessage = _tables.LastLoadWarning;
        }
    }

    private void SyncBoundLibrary(string? libraryId)
    {
        var id = libraryId ?? string.Empty;
        var match = RuleLibraries.FirstOrDefault(item => item.Id == id);
        if (match is null && id.Length > 0)
        {
            match = new RuleLibraryChoice(id, "已删除的规则库（" + id[..Math.Min(6, id.Length)] + "…）");
            RuleLibraries.Add(match);
        }

        BoundLibrary = match ?? RuleLibraries.FirstOrDefault();
    }

    private void LoadColumns(string filePath)
    {
        Columns.Clear();
        Columns.Add(AllColumnsLabel);
        try
        {
            if (!File.Exists(filePath))
            {
                return;
            }

            using var workbook = new XLWorkbook(filePath, new LoadOptions { RecalculateAllFormulas = false });
            var sheet = workbook.Worksheets.FirstOrDefault();
            var headerRow = sheet?.FirstRowUsed()?.RowNumber();
            if (sheet is null || headerRow is null)
            {
                return;
            }

            foreach (var cell in sheet.Row(headerRow.Value).CellsUsed())
            {
                var value = cell.GetString().Trim();
                if (value.Length > 0 && !Columns.Contains(value))
                {
                    Columns.Add(value);
                }
            }
        }
        catch (Exception ex)
        {
            _logger.Debug(ex, "Failed to read header columns for {FilePath}.", filePath);
        }
        finally
        {
            SearchColumn = AllColumnsLabel;
            OnPropertyChanged(nameof(SearchScope));
        }
    }
}
