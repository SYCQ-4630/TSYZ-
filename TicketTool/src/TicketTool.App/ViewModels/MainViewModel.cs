using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Serilog;
using TicketTool.App.Services;
using TicketTool.App.Views;
using TicketTool.Core.Data;
using TicketTool.Core.Models;
using TicketTool.Core.Parsing;
using TicketTool.Core.Search;
using TicketTool.Core.Snapshot;
using TicketTool.Core.Sync;
using TicketTool.Core.Templates;
using TicketTool.Core.Xlsx;

namespace TicketTool.App.ViewModels;

/// <summary>主窗口的全部业务流程。</summary>
public sealed partial class MainViewModel : ObservableObject
{
    private readonly Database _database;
    private readonly TemplateService _templates;
    private readonly TicketRepository _tickets;
    private readonly TicketParser _parser;
    private readonly SearchService _search;
    private readonly TicketSyncWorker _syncWorker;
    private readonly SyncQueueRepository _syncQueue;
    private readonly SnapshotService _snapshots;
    private readonly XlsxRowAppender _appender;
    private readonly CsvService _csv;
    private readonly IDialogService _dialogs;
    private readonly IWindowService _windows;
    private readonly AppSession _session;
    private readonly ILogger _logger;

    private CancellationTokenSource? _parseCts;
    private CancellationTokenSource? _searchCts;
    private CancellationTokenSource? _searchDebounceCts;
    private DispatcherTimer? _snapshotTimer;
    private bool _initialized;
    private bool _suppressAutoParse;
    private bool _exiting;
    private long? _loadingSelectionId;

    [ObservableProperty]
    private string _rawText = string.Empty;

    [ObservableProperty]
    private Template? _selectedTemplate;

    [ObservableProperty]
    private bool _isParsing;

    [ObservableProperty]
    private bool _isImporting;

    [ObservableProperty]
    private double _parseProgressValue;

    [ObservableProperty]
    private string _parseProgressText = string.Empty;

    [ObservableProperty]
    private string _statusMessage = "粘贴工单文本后，选择模板即可自动识别。";

    [ObservableProperty]
    private string _rawLengthText = "0 个字符";

    [ObservableProperty]
    private int _selectedTabIndex;

    [ObservableProperty]
    private string _searchQuery = string.Empty;

    [ObservableProperty]
    private OptionItem<SearchDomain> _selectedSearchDomain = new("自动判断", SearchDomain.Auto);

    [ObservableProperty]
    private bool _searchCurrentTemplateOnly;

    [ObservableProperty]
    private bool _isSearching;

    [ObservableProperty]
    private string _searchSummary = "输入工单号、联系方式或投诉地址进行精确查询。";

    [ObservableProperty]
    private string _pageText = string.Empty;

    [ObservableProperty]
    private TicketSearchResult? _selectedSearchResult;

    [ObservableProperty]
    private SyncBadge _syncBadge = new(new SyncStatistics());

    [ObservableProperty]
    private double _searchElapsedMs;

    [ObservableProperty]
    private RuleVersionSummary? _selectedRuleVersion;

    [ObservableProperty]
    private string _templateHint = string.Empty;

    [ObservableProperty]
    private bool _isBusy;

    private int _searchPageIndex;
    private int _searchTotal;

    public MainViewModel(
        Database database,
        TemplateService templates,
        TicketRepository tickets,
        TicketParser parser,
        SearchService search,
        TicketSyncWorker syncWorker,
        SyncQueueRepository syncQueue,
        SnapshotService snapshots,
        XlsxRowAppender appender,
        CsvService csv,
        IDialogService dialogs,
        IWindowService windows,
        DiagnosticsViewModel diagnostics,
        AppSession session,
        ILogger? logger = null)
    {
        _database = database;
        _templates = templates;
        _tickets = tickets;
        _parser = parser;
        _search = search;
        _syncWorker = syncWorker;
        _syncQueue = syncQueue;
        _snapshots = snapshots;
        _appender = appender;
        _csv = csv;
        _dialogs = dialogs;
        _windows = windows;
        _session = session;
        _logger = logger ?? Log.ForContext<MainViewModel>();
        Diagnostics = diagnostics;
        SearchDomainOptions = new[]
        {
            new OptionItem<SearchDomain>("自动判断", SearchDomain.Auto),
            new OptionItem<SearchDomain>("工单号", SearchDomain.TicketNo),
            new OptionItem<SearchDomain>("联系方式", SearchDomain.Phone),
            new OptionItem<SearchDomain>("投诉地址", SearchDomain.Address)
        };
        _syncWorker.StatusChanged += OnSyncStatusChanged;
    }

    public ObservableCollection<Template> Templates { get; } = new();

    public ObservableCollection<FieldPreviewRow> PreviewFields { get; } = new();

    public ObservableCollection<string> MissingFields { get; } = new();

    public ObservableCollection<TicketSearchResult> SearchResults { get; } = new();

    public ObservableCollection<RuleVersionSummary> RuleVersions { get; } = new();

    public IReadOnlyList<OptionItem<SearchDomain>> SearchDomainOptions { get; }

    public DiagnosticsViewModel Diagnostics { get; }

    public string DataFolder => _database.Paths.Root;

    public bool HasTemplates => Templates.Count > 0;

    public bool HasMissingFields => MissingFields.Count > 0;

    public bool IsTemplateFileMissing => SelectedTemplate is not null && !File.Exists(SelectedTemplate.FilePath);

    public string ParsedCountText => $"已识别 {PreviewFields.Count(f => !f.IsMissing)} / {PreviewFields.Count} 个字段";

    /// <summary>窗口加载后初始化。</summary>
    public async Task InitializeAsync()
    {
        if (_initialized)
        {
            return;
        }
        _initialized = true;
        await LoadTemplatesAsync().ConfigureAwait(true);
        await ApplyPendingSnapshotAsync().ConfigureAwait(true);
        await Diagnostics.InitializeAsync().ConfigureAwait(true);
        await RefreshSyncBadgeAsync().ConfigureAwait(true);
        _snapshotTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(30) };
        _snapshotTimer.Tick += async (_, _) => await PeriodicSnapshotAsync().ConfigureAwait(true);
        _snapshotTimer.Start();
    }

    partial void OnRawTextChanged(string value)
    {
        RawLengthText = $"{value.Length:N0} 个字符";
    }

    partial void OnIsParsingChanged(bool value)
    {
        ParseCommand.NotifyCanExecuteChanged();
        ConfirmImportCommand.NotifyCanExecuteChanged();
    }

    partial void OnIsImportingChanged(bool value)
    {
        ConfirmImportCommand.NotifyCanExecuteChanged();
    }

    partial void OnSelectedTemplateChanged(Template? value)
    {
        OnPropertyChanged(nameof(IsTemplateFileMissing));
        ParseCommand.NotifyCanExecuteChanged();
        ConfirmImportCommand.NotifyCanExecuteChanged();
        TemplateHint = value is null
            ? "请先选择或新建模板。"
            : $"{value.Name} · {value.Fields.Count} 个字段 · 表格：{Path.GetFileName(value.FilePath)}";
        _ = RefreshVersionsAsync();
        if (!_suppressAutoParse && !string.IsNullOrWhiteSpace(RawText))
        {
            _ = ParseAsync();
        }
        _ = SaveSnapshotAsync();
    }

    partial void OnSearchQueryChanged(string value)
    {
        _searchDebounceCts?.Cancel();
        var cts = new CancellationTokenSource();
        _searchDebounceCts = cts;
        _ = DebouncedSearchAsync(cts);
    }

    partial void OnSelectedSearchResultChanged(TicketSearchResult? value)
    {
        OpenTraceCommand.NotifyCanExecuteChanged();
    }

    /// <summary>粘贴按钮：只读取一次剪贴板，不常驻监听。</summary>
    [RelayCommand]
    private async Task PasteAsync()
    {
        try
        {
            if (!System.Windows.Clipboard.ContainsText())
            {
                _dialogs.Info("没有可粘贴的文本", "请先在工单系统中复制工单文本。");
                return;
            }

            RawText = System.Windows.Clipboard.GetText();
            StatusMessage = "文本已粘贴，正在识别…";
            await SaveSnapshotAsync().ConfigureAwait(true);
            if (SelectedTemplate is not null)
            {
                await ParseAsync().ConfigureAwait(true);
            }
            else
            {
                StatusMessage = RawText.Length > 0 ? "文本已保存，请选择目标模板。" : "剪贴板中没有有效文本。";
            }
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "PasteFailed");
            _dialogs.Error("粘贴失败", "无法读取剪贴板。请重试，或手动把文本粘贴到编辑区。");
        }
    }

    private bool CanParse => SelectedTemplate is not null && !IsParsing;

    /// <summary>按当前模板解析原文。</summary>
    [RelayCommand(CanExecute = nameof(CanParse))]
    private async Task ParseAsync()
    {
        var template = SelectedTemplate;
        if (template is null)
        {
            return;
        }

        _parseCts?.Cancel();
        _parseCts?.Dispose();
        _parseCts = new CancellationTokenSource();
        var token = _parseCts.Token;
        IsParsing = true;
        ParseProgressValue = 0;
        ParseProgressText = "准备识别…";
        try
        {
            var text = RawText;
            var progress = new Progress<ParseProgress>(p =>
            {
                ParseProgressValue = p.Percent;
                ParseProgressText = p.Message;
            });
            var result = await Task.Run(() => _parser.Parse(text, template, progress, token), token).ConfigureAwait(true);
            if (token.IsCancellationRequested)
            {
                return;
            }

            PreviewFields.Clear();
            foreach (var field in template.Fields.OrderBy(f => f.SortOrder).ThenBy(f => f.Id))
            {
                var parsed = result.Fields.FirstOrDefault(f => f.FieldName == field.FieldName);
                PreviewFields.Add(new FieldPreviewRow(parsed, field));
            }

            MissingFields.Clear();
            foreach (var missing in result.MissingFields)
            {
                MissingFields.Add(missing);
            }

            OnPropertyChanged(nameof(HasMissingFields));
            OnPropertyChanged(nameof(ParsedCountText));
            ConfirmImportCommand.NotifyCanExecuteChanged();
            var recognized = PreviewFields.Count(f => !f.IsMissing);
            StatusMessage = recognized == PreviewFields.Count
                ? $"识别完成：{recognized} 个字段全部找到，请核对后确认导入。"
                : $"识别完成：{recognized} / {PreviewFields.Count} 个字段，未识别项已高亮，请手动补充。";
            ParseProgressValue = 100;
            ParseProgressText = $"识别完成，耗时 {result.Elapsed.TotalMilliseconds:0} 毫秒";
            await SaveSnapshotAsync().ConfigureAwait(true);
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "ParseFailed");
            _dialogs.Error("识别失败", "无法识别该文本，请确认模板文件未损坏。\n\n" + ex.Message);
            StatusMessage = "识别失败，请查看诊断日志。";
        }
        finally
        {
            IsParsing = false;
        }
    }

    private bool CanConfirmImport => SelectedTemplate is not null && PreviewFields.Count > 0 && !IsImporting && !IsParsing;

    /// <summary>确认导入：预览写入位置后，单事务入库并唤醒同步。</summary>
    [RelayCommand(CanExecute = nameof(CanConfirmImport))]
    private async Task ConfirmImportAsync()
    {
        var template = SelectedTemplate;
        if (template is null || PreviewFields.Count == 0)
        {
            return;
        }

        if (!File.Exists(template.FilePath))
        {
            _dialogs.Error("目标表格不存在", "目标表格已被删除或改名。请先在“模板与规则”中重新绑定文件，再导入。");
            return;
        }

        try
        {
            IsImporting = true;
            var values = PreviewFields.ToDictionary(f => f.FieldName, f => (string?)f.Value, StringComparer.Ordinal);
            var cells = template.Fields
                .Select(field => new XlsxCellValue(field.ColumnIndex, values.GetValueOrDefault(field.FieldName)))
                .ToList();

            var previewText = "即将写入目标表格，请确认：\n\n";
            try
            {
                if (!FileLockDetector.IsWritable(template.FilePath))
                {
                    previewText += "目标表格当前正被 Excel 占用。数据会先安全入库，关闭表格后自动同步。\n\n";
                }
                else
                {
                    var preview = await Task.Run(() => _appender.BuildPreviewAsync(
                        template.FilePath, cells, template.Fields).GetAwaiter().GetResult()).ConfigureAwait(true);
                    previewText += $"将写入第 {preview.RowNumber} 行，共 {preview.Cells.Count} 列。\n\n";
                    previewText += string.Join("\n", preview.Cells
                        .Where(c => !string.IsNullOrWhiteSpace(c.Value))
                        .Take(12)
                        .Select(c => $"{HeaderReader.GetColumnName(c.ColumnIndex)} 列 · {c.FieldName} = {TrimForPreview(c.Value)}"));
                    previewText += "\n\n";
                }
            }
            catch (Exception ex) when (ex is XlsxFileLockedException or IOException)
            {
                previewText += "目标表格当前无法写入。数据会先安全入库，稍后自动同步。\n\n";
            }

            if (!_dialogs.Confirm("确认导入工单", previewText + "确认后立即保存到数据库，并开始写入表格。"))
            {
                return;
            }

            var spans = BuildSpans();
            var ticket = new TicketInsert
            {
                TemplateId = template.Id,
                Values = values,
                RawText = RawText,
                Spans = spans,
                CreatedAt = DateTimeOffset.Now
            };
            var ticketId = await _tickets.InsertAsync(ticket, template).ConfigureAwait(true);
            _logger.Information("TicketInserted {TicketId} {TemplateId}", ticketId, template.Id);
            StatusMessage = $"工单已入库（编号 {ticketId}），正在后台同步到表格…";
            _syncWorker.Notify();
            await RefreshSyncBadgeAsync().ConfigureAwait(true);

            RawText = string.Empty;
            PreviewFields.Clear();
            MissingFields.Clear();
            OnPropertyChanged(nameof(HasMissingFields));
            OnPropertyChanged(nameof(ParsedCountText));
            ConfirmImportCommand.NotifyCanExecuteChanged();
            await SaveSnapshotAsync(cleanExit: true).ConfigureAwait(true);
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "ConfirmImportFailed");
            _dialogs.Error("导入失败", "数据没有写入。请导出诊断包并查看日志。\n\n" + ex.Message);
        }
        finally
        {
            IsImporting = false;
        }
    }

    /// <summary>从 CSV/TSV 批量导入。</summary>
    [RelayCommand]
    private async Task ImportCsvAsync()
    {
        var template = SelectedTemplate;
        if (template is null)
        {
            _dialogs.Info("请先选择模板", "批量导入前需要先选择目标模板。");
            return;
        }

        var path = _dialogs.OpenFile("选择要导入的 CSV / TSV 文件", "表格文本 (*.csv;*.tsv;*.txt)|*.csv;*.tsv;*.txt|所有文件 (*.*)|*.*");
        if (path is null)
        {
            return;
        }

        try
        {
            IsBusy = true;
            var preview = await _csv.PreviewAsync(path, template).ConfigureAwait(true);
            if (preview.Rows.Count == 0)
            {
                _dialogs.Info("没有可导入的数据", "文件中没有识别到数据行，或表头与模板字段不匹配。");
                return;
            }

            var message = $"文件共 {preview.Rows.Count} 行，可映射 {preview.MappedFields.Count} 个字段。";
            if (preview.UnmappedHeaders.Count > 0)
            {
                message += $"\n\n以下列将忽略：{string.Join("、", preview.UnmappedHeaders.Take(8))}";
            }
            message += "\n\n导入后会自动进入同步队列。是否继续？";
            if (!_dialogs.Confirm("确认批量导入", message))
            {
                return;
            }

            var imported = 0;
            foreach (var row in preview.Rows)
            {
                var ticket = CsvService.ToTicket(template, row);
                await _tickets.InsertAsync(ticket, template).ConfigureAwait(true);
                imported++;
            }
            _syncWorker.Notify();
            await RefreshSyncBadgeAsync().ConfigureAwait(true);
            _dialogs.Info("导入完成", $"已导入 {imported} 条工单，后台同步已开始。");
            StatusMessage = $"已从文件导入 {imported} 条工单。";
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "CsvImportFailed");
            _dialogs.Error("批量导入失败", ex.Message);
        }
        finally
        {
            IsBusy = false;
        }
    }

    /// <summary>新建模板。</summary>
    [RelayCommand]
    private async Task NewTemplateAsync()
    {
        var template = _windows.ShowTemplateEditor(null);
        if (template is not null)
        {
            await ReloadTemplatesAsync(template.Id).ConfigureAwait(true);
            StatusMessage = $"模板“{template.Name}”已创建。";
        }
    }

    /// <summary>编辑当前模板。</summary>
    [RelayCommand]
    private async Task EditTemplateAsync()
    {
        if (SelectedTemplate is null)
        {
            return;
        }

        Template? reloaded = await _templates.GetAsync(SelectedTemplate.Id).ConfigureAwait(true);
        if (reloaded is null)
        {
            return;
        }
        var edited = _windows.ShowTemplateEditor(reloaded);
        if (edited is not null)
        {
            await ReloadTemplatesAsync(edited.Id).ConfigureAwait(true);
            StatusMessage = $"模板“{edited.Name}”已更新。";
        }
    }

    /// <summary>删除模板。</summary>
    [RelayCommand]
    private async Task DeleteTemplateAsync()
    {
        var template = SelectedTemplate;
        if (template is null)
        {
            return;
        }

        var count = await _tickets.CountByTemplateAsync(template.Id).ConfigureAwait(true);
        if (count > 0)
        {
            _dialogs.Info("不能删除", $"该模板下已有 {count} 条工单。为保护历史数据，模板不能删除。\n\n如需停用，可修改模板名称或保留不动。");
            return;
        }

        if (!_dialogs.ConfirmDanger("删除模板", $"确定删除模板“{template.Name}”吗？\n\n字段规则会一并删除，且无法恢复。"))
        {
            return;
        }

        try
        {
            await _templates.DeleteAsync(template.Id).ConfigureAwait(true);
            _logger.Information("TemplateDeletedFromUi {TemplateId}", template.Id);
            await ReloadTemplatesAsync(null).ConfigureAwait(true);
            StatusMessage = "模板已删除。";
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "TemplateDeleteFailed");
            _dialogs.Error("删除失败", ex.Message);
        }
    }

    /// <summary>编辑规则。</summary>
    [RelayCommand]
    private async Task EditRulesAsync()
    {
        if (SelectedTemplate is null)
        {
            return;
        }

        var template = await _templates.GetAsync(SelectedTemplate.Id).ConfigureAwait(true);
        if (template is null)
        {
            return;
        }

        if (_windows.ShowRuleEditor(template, RawText))
        {
            await ReloadTemplatesAsync(template.Id).ConfigureAwait(true);
            StatusMessage = "规则已保存，旧版本可随时回退。";
            if (!string.IsNullOrWhiteSpace(RawText))
            {
                await ParseAsync().ConfigureAwait(true);
            }
        }
    }

    /// <summary>从其他模板复制规则。</summary>
    [RelayCommand]
    private async Task CopyRulesAsync()
    {
        var target = SelectedTemplate;
        if (target is null)
        {
            return;
        }

        var sources = (await _templates.ListAsync().ConfigureAwait(true))
            .Where(t => t.Id != target.Id)
            .ToList();
        if (sources.Count == 0)
        {
            _dialogs.Info("没有可复制的模板", "至少需要两个模板才能复制规则。");
            return;
        }

        var picker = new TemplatePickerWindow(sources) { Owner = System.Windows.Application.Current.MainWindow };
        if (picker.ShowDialog() != true || picker.SelectedTemplate is null)
        {
            return;
        }

        var copied = await _templates.CopyRulesAsync(picker.SelectedTemplate.Id, target.Id).ConfigureAwait(true);
        await ReloadTemplatesAsync(target.Id).ConfigureAwait(true);
        _dialogs.Info("复制完成", $"已复制 {copied} 条同名字段规则。");
    }

    /// <summary>回退规则到选中版本。</summary>
    [RelayCommand]
    private async Task RollbackRulesAsync()
    {
        var template = SelectedTemplate;
        var version = SelectedRuleVersion;
        if (template is null || version is null)
        {
            _dialogs.Info("请选择版本", "请先在版本列表中选择要回退的版本。");
            return;
        }

        if (!_dialogs.ConfirmDanger("回退规则", $"确定把“{template.Name}”的规则回退到 {version.CreatedAt:yyyy-MM-dd HH:mm:ss} 的版本吗？\n\n当前规则会先保存为新的历史版本。"))
        {
            return;
        }

        await _templates.RollbackAsync(template.Id, version.Id).ConfigureAwait(true);
        await ReloadTemplatesAsync(template.Id).ConfigureAwait(true);
        _dialogs.Info("回退完成", "规则已恢复到所选版本。");
    }

    /// <summary>打开溯源窗口。</summary>
    [RelayCommand(CanExecute = nameof(CanOpenTrace))]
    private void OpenTrace()
    {
        if (SelectedSearchResult is null)
        {
            return;
        }
        _windows.ShowTrace(SelectedSearchResult.TicketId, SelectedSearchResult.FieldName, SelectedSearchResult.DisplayValue);
    }

    private bool CanOpenTrace => SelectedSearchResult is not null;

    /// <summary>立即搜索。</summary>
    [RelayCommand]
    private async Task SearchAsync()
    {
        _searchDebounceCts?.Cancel();
        _searchPageIndex = 0;
        await ExecuteSearchAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private async Task PreviousPageAsync()
    {
        if (_searchPageIndex <= 0)
        {
            return;
        }
        _searchPageIndex--;
        await ExecuteSearchAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private async Task NextPageAsync()
    {
        if ((_searchPageIndex + 1) * 50 >= _searchTotal)
        {
            return;
        }
        _searchPageIndex++;
        await ExecuteSearchAsync().ConfigureAwait(true);
    }

    private async Task DebouncedSearchAsync(CancellationTokenSource debounce)
    {
        try
        {
            await Task.Delay(300, debounce.Token).ConfigureAwait(true);
            if (debounce.IsCancellationRequested)
            {
                return;
            }
            _searchPageIndex = 0;
            await ExecuteSearchAsync().ConfigureAwait(true);
        }
        catch (OperationCanceledException)
        {
        }
    }

    private async Task ExecuteSearchAsync()
    {
        if (string.IsNullOrWhiteSpace(SearchQuery))
        {
            SearchResults.Clear();
            _searchTotal = 0;
            SearchSummary = "输入工单号、联系方式或投诉地址进行精确查询。";
            PageText = string.Empty;
            return;
        }

        _searchCts?.Cancel();
        _searchCts?.Dispose();
        _searchCts = new CancellationTokenSource();
        var token = _searchCts.Token;
        IsSearching = true;
        try
        {
            var request = new TicketSearchRequest
            {
                Query = SearchQuery,
                Domain = SelectedSearchDomain.Value,
                TemplateId = SearchCurrentTemplateOnly ? SelectedTemplate?.Id : null,
                PageIndex = _searchPageIndex,
                PageSize = 50
            };
            var page = await _search.SearchAsync(request, token).ConfigureAwait(true);
            if (token.IsCancellationRequested)
            {
                return;
            }

            SearchResults.Clear();
            foreach (var item in page.Items)
            {
                SearchResults.Add(item);
            }
            _searchTotal = page.TotalCount;
            SearchElapsedMs = page.Elapsed.TotalMilliseconds;
            var totalPages = Math.Max(1, (int)Math.Ceiling(_searchTotal / 50.0));
            PageText = _searchTotal == 0 ? "第 0 / 0 页" : $"第 {_searchPageIndex + 1} / {totalPages} 页";
            SearchSummary = $"共命中 {_searchTotal} 条，耗时 {page.Elapsed.TotalMilliseconds:0} 毫秒 · 搜索域：{DomainText(page.EffectiveDomain)}";
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "SearchFailed");
            SearchSummary = "查询失败，请查看诊断日志。";
        }
        finally
        {
            IsSearching = false;
        }
    }

    /// <summary>导出 CSV。</summary>
    [RelayCommand]
    private Task ExportCsvAsync() => ExportAsync(tabSeparated: false);

    /// <summary>导出 TSV。</summary>
    [RelayCommand]
    private Task ExportTsvAsync() => ExportAsync(tabSeparated: true);

    /// <summary>导出当前模板工单。</summary>
    private async Task ExportAsync(bool tabSeparated)
    {
        var template = SelectedTemplate;
        if (template is null)
        {
            _dialogs.Info("请先选择模板", "导出前需要选择模板。");
            return;
        }

        var extension = tabSeparated ? "tsv" : "csv";
        var path = _dialogs.SaveFile(
            "导出工单",
            tabSeparated ? "TSV 文件 (*.tsv)|*.tsv" : "CSV 文件 (*.csv)|*.csv",
            $"{template.Name}-{DateTime.Now:yyyyMMdd}.{extension}");
        if (path is null)
        {
            return;
        }

        try
        {
            IsBusy = true;
            var records = await _tickets.ListForExportAsync(template.Id, 200000).ConfigureAwait(true);
            var rows = records.Select(r => (IReadOnlyDictionary<string, string?>)TicketRepository.ParseDataJson(r.DataJson)).ToList();
            await _csv.ExportAsync(path, template, rows, tabSeparated).ConfigureAwait(true);
            _logger.Information("TicketsExported {TemplateId} {Rows} {Path}", template.Id, rows.Count, path);
            _dialogs.Info("导出完成", $"已导出 {rows.Count} 条工单到：\n{path}");
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "CsvExportFailed");
            _dialogs.Error("导出失败", ex.Message);
        }
        finally
        {
            IsBusy = false;
        }
    }

    /// <summary>同步状态详情。</summary>
    [RelayCommand]
    private async Task ShowSyncDetailsAsync()
    {
        await RefreshSyncBadgeAsync().ConfigureAwait(true);
        var stats = SyncBadge.Statistics;
        var message = $"{SyncBadge.Text}\n\n待同步：{stats.Pending}\n同步中：{stats.Syncing}\n等待关闭表格：{stats.Blocked}\n失败：{stats.Failed}\n已同步：{stats.Synced}";
        if (stats.Failed > 0 || stats.Blocked > 0)
        {
            message += "\n\n可到“诊断”页查看失败原因并手动重试。";
        }
        _dialogs.Info("同步状态", message);
    }

    /// <summary>打开表格所在目录。</summary>
    [RelayCommand]
    private void OpenTemplateFolder()
    {
        if (SelectedTemplate is null)
        {
            return;
        }
        try
        {
            var directory = Path.GetDirectoryName(SelectedTemplate.FilePath);
            if (Directory.Exists(directory))
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = "explorer.exe",
                    Arguments = $"\"{directory}\"",
                    UseShellExecute = true
                });
            }
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "OpenTemplateFolderFailed");
        }
    }

    /// <summary>创建示例模板与示例文本。</summary>
    [RelayCommand]
    private async Task CreateSampleAsync()
    {
        try
        {
            var baseDir = AppContext.BaseDirectory;
            var sampleXlsx = Path.Combine(baseDir, "samples", "通信工单示例.xlsx");
            var sampleText = Path.Combine(baseDir, "samples", "示例工单.txt");
            if (!File.Exists(sampleXlsx))
            {
                _dialogs.Error("缺少示例文件", "安装目录下未找到示例模板，请重新安装或手动新建模板。");
                return;
            }

            var targetDir = Path.Combine(_database.Paths.Root, "samples");
            Directory.CreateDirectory(targetDir);
            var targetXlsx = Path.Combine(targetDir, "通信工单示例.xlsx");
            File.Copy(sampleXlsx, targetXlsx, true);
            var headers = await HeaderReader.ReadAsync(targetXlsx, 1).ConfigureAwait(true);
            var template = new Template
            {
                Name = $"通信工单（示例）{DateTime.Now:HHmmss}",
                FilePath = targetXlsx,
                HeaderRow = 1,
                Fields = headers.Fields.Select(f => new TemplateField
                {
                    FieldName = f.FieldName,
                    ColumnIndex = f.ColumnIndex,
                    CoreType = f.CoreType,
                    SortOrder = f.SortOrder
                }).ToList()
            };
            var created = await _templates.CreateAsync(template).ConfigureAwait(true);
            await ReloadTemplatesAsync(created.Id).ConfigureAwait(true);
            if (File.Exists(sampleText))
            {
                RawText = await File.ReadAllTextAsync(sampleText).ConfigureAwait(true);
                await ParseAsync().ConfigureAwait(true);
            }
            _dialogs.Info("示例已创建", "示例模板已加入列表，示例工单文本已填入编辑区。你可以直接体验识别、预览和确认导入。");
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "CreateSampleFailed");
            _dialogs.Error("创建示例失败", ex.Message);
        }
    }

    private async Task LoadTemplatesAsync(long? selectId = null)
    {
        _suppressAutoParse = true;
        try
        {
            var list = await _templates.ListAsync().ConfigureAwait(true);
            var full = new List<Template>();
            foreach (var item in list)
            {
                var loaded = await _templates.GetAsync(item.Id).ConfigureAwait(true);
                if (loaded is not null)
                {
                    full.Add(loaded);
                }
            }

            Templates.Clear();
            foreach (var template in full)
            {
                Templates.Add(template);
            }
            OnPropertyChanged(nameof(HasTemplates));

            _loadingSelectionId = selectId
                                  ?? _session.PendingSnapshot?.TemplateId
                                  ?? full.FirstOrDefault()?.Id;
            SelectedTemplate = full.FirstOrDefault(t => t.Id == _loadingSelectionId) ?? full.FirstOrDefault();
        }
        finally
        {
            _suppressAutoParse = false;
        }
    }

    private async Task ReloadTemplatesAsync(long? selectId)
    {
        await LoadTemplatesAsync(selectId).ConfigureAwait(true);
        if (SelectedTemplate is not null && !string.IsNullOrWhiteSpace(RawText))
        {
            await ParseAsync().ConfigureAwait(true);
        }
    }

    private async Task ApplyPendingSnapshotAsync()
    {
        var snapshot = _session.PendingSnapshot;
        if (snapshot is null)
        {
            return;
        }

        var summary = string.IsNullOrWhiteSpace(_session.StartupMessage)
            ? "上次退出时还有未完成的编辑内容。"
            : _session.StartupMessage;
        if (!_dialogs.Confirm("发现未完成的编辑", summary + "\n\n是否恢复这些内容？选择“取消”将丢弃。"))
        {
            await _snapshots.DiscardAsync().ConfigureAwait(true);
            _session.PendingSnapshot = null;
            _session.StartupMessage = null;
            StatusMessage = "已丢弃上次未完成的编辑内容。";
            return;
        }

        _suppressAutoParse = true;
        RawText = snapshot.RawText;
        if (snapshot.TemplateId is not null)
        {
            SelectedTemplate = Templates.FirstOrDefault(t => t.Id == snapshot.TemplateId) ?? SelectedTemplate;
        }
        _suppressAutoParse = false;

        if (SelectedTemplate is not null && !string.IsNullOrWhiteSpace(RawText))
        {
            await ParseAsync().ConfigureAwait(true);
            if (snapshot.PreviewValues.Count > 0)
            {
                foreach (var row in PreviewFields)
                {
                    if (snapshot.PreviewValues.TryGetValue(row.FieldName, out var value) && value is not null)
                    {
                        row.Value = value;
                    }
                }
            }
        }
        await _snapshots.AcknowledgeAsync().ConfigureAwait(true);
        _session.PendingSnapshot = null;
        _session.StartupMessage = null;
        StatusMessage = "已恢复上次未完成的编辑内容。";
    }

    private async Task PeriodicSnapshotAsync()
    {
        if (_exiting)
        {
            return;
        }
        if (string.IsNullOrEmpty(RawText) && PreviewFields.Count == 0)
        {
            return;
        }
        await SaveSnapshotAsync().ConfigureAwait(true);
    }

    private async Task SaveSnapshotAsync(bool cleanExit = false)
    {
        if (_exiting)
        {
            return;
        }
        try
        {
            var payload = BuildSnapshotPayload();
            await Task.Run(() => _snapshots.SaveAsync(payload, cleanExit)).ConfigureAwait(true);
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "SnapshotSaveFailed");
        }
    }

    private SnapshotPayload BuildSnapshotPayload() => new()
    {
        RawText = RawText,
        TemplateId = SelectedTemplate?.Id,
        TemplateName = SelectedTemplate?.Name,
        PreviewValues = PreviewFields.ToDictionary(f => f.FieldName, f => (string?)f.Value, StringComparer.Ordinal),
        Stage = IsParsing ? "识别中" : PreviewFields.Count > 0 ? "预览确认" : "编辑中"
    };

    /// <summary>正常退出时保存最终快照。</summary>
    public async Task SaveFinalSnapshotAsync()
    {
        if (_exiting)
        {
            return;
        }
        _exiting = true;
        _snapshotTimer?.Stop();
        try
        {
            var payload = BuildSnapshotPayload();
            await Task.Run(() => _snapshots.SaveAsync(payload, cleanExit: true, CancellationToken.None)).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "FinalSnapshotFailed");
        }
    }

    private async Task RefreshVersionsAsync()
    {
        if (SelectedTemplate is null)
        {
            RuleVersions.Clear();
            SelectedRuleVersion = null;
            return;
        }
        try
        {
            var versions = await _templates.ListVersionsAsync(SelectedTemplate.Id).ConfigureAwait(true);
            RuleVersions.Clear();
            foreach (var version in versions)
            {
                RuleVersions.Add(version);
            }
            SelectedRuleVersion = RuleVersions.FirstOrDefault();
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "LoadRuleVersionsFailed");
        }
    }

    private async Task RefreshSyncBadgeAsync()
    {
        try
        {
            var stats = await _syncQueue.GetStatisticsAsync().ConfigureAwait(true);
            SyncBadge = new SyncBadge(stats);
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "RefreshSyncBadgeFailed");
        }
    }

    private void OnSyncStatusChanged(object? sender, EventArgs e)
    {
        System.Windows.Application.Current?.Dispatcher.BeginInvoke(async () => await RefreshSyncBadgeAsync().ConfigureAwait(true));
    }

    private List<FieldSpan> BuildSpans()
    {
        var spans = new List<FieldSpan>();
        foreach (var row in PreviewFields)
        {
            if (string.IsNullOrEmpty(row.Value))
            {
                continue;
            }

            if (!row.IsModified && row.EndOffset > row.StartOffset && row.EndOffset <= RawText.Length
                && RawText[row.StartOffset..row.EndOffset] == row.Value)
            {
                spans.Add(new FieldSpan { FieldName = row.FieldName, StartOffset = row.StartOffset, EndOffset = row.EndOffset });
                continue;
            }

            var index = RawText.IndexOf(row.Value, StringComparison.Ordinal);
            if (index >= 0)
            {
                spans.Add(new FieldSpan { FieldName = row.FieldName, StartOffset = index, EndOffset = index + row.Value.Length });
            }
        }
        return spans;
    }

    private static string DomainText(SearchDomain domain) => domain switch
    {
        SearchDomain.TicketNo => "工单号",
        SearchDomain.Phone => "联系方式",
        SearchDomain.Address => "投诉地址",
        _ => "自动判断"
    };

    private static string TrimForPreview(string? value)
    {
        if (string.IsNullOrEmpty(value))
        {
            return string.Empty;
        }
        return value.Length <= 24 ? value : value[..24] + "…";
    }
}
