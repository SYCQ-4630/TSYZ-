using System.Collections.ObjectModel;
using System.IO;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Serilog;
using TicketTool.App.Services;
using TicketTool.Core.Models;
using TicketTool.Core.Templates;
using TicketTool.Core.Xlsx;

namespace TicketTool.App.ViewModels;

/// <summary>表头草案的可编辑行。</summary>
public sealed partial class TemplateFieldDraftRow : ObservableObject
{
    [ObservableProperty]
    private string _fieldName = string.Empty;

    public int ColumnIndex { get; init; }

    public int SortOrder { get; init; }

    public CoreFieldType CoreType { get; init; }

    public string ColumnName => HeaderReader.GetColumnName(ColumnIndex);

    public string CoreTypeText => CoreType switch
    {
        CoreFieldType.TicketNo => "工单号",
        CoreFieldType.Phone => "联系方式",
        CoreFieldType.Address => "投诉地址",
        _ => "普通字段"
    };
}

/// <summary>新建或编辑模板。</summary>
public sealed partial class NewTemplateViewModel : ObservableObject
{
    private readonly TemplateService _templates;
    private readonly IDialogService _dialogs;
    private readonly ILogger _logger;
    private readonly Template? _existing;

    [ObservableProperty]
    private string _name = string.Empty;

    [ObservableProperty]
    private string _filePath = string.Empty;

    [ObservableProperty]
    private int _headerRow = 1;

    [ObservableProperty]
    private string _headerSummary = "请选择表格文件，然后检测表头。";

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private bool _hasFields;

    [ObservableProperty]
    private string _windowTitle = "新建模板";

    /// <summary>创建视图模型。</summary>
    public NewTemplateViewModel(
        TemplateService templates,
        IDialogService dialogs,
        Template? existing = null,
        ILogger? logger = null)
    {
        _templates = templates;
        _dialogs = dialogs;
        _existing = existing;
        _logger = logger ?? Log.ForContext<NewTemplateViewModel>();
        if (existing is not null)
        {
            WindowTitle = "编辑模板";
            Name = existing.Name;
            FilePath = existing.FilePath;
            HeaderRow = existing.HeaderRow;
            foreach (var field in existing.Fields.OrderBy(f => f.SortOrder).ThenBy(f => f.Id))
            {
                Fields.Add(new TemplateFieldDraftRow
                {
                    FieldName = field.FieldName,
                    ColumnIndex = field.ColumnIndex,
                    SortOrder = field.SortOrder,
                    CoreType = field.CoreType
                });
            }
            HasFields = Fields.Count > 0;
            HeaderSummary = $"当前模板共 {Fields.Count} 个字段。可重新检测表头以同步最新列。";
        }
    }

    /// <summary>表头字段。</summary>
    public ObservableCollection<TemplateFieldDraftRow> Fields { get; } = new();

    /// <summary>保存成功后的模板。</summary>
    public Template? SavedTemplate { get; private set; }

    /// <summary>请求关闭窗口，参数表示是否保存成功。</summary>
    public event EventHandler<bool>? CloseRequested;

    [RelayCommand]
    private async Task BrowseAsync()
    {
        var path = _dialogs.OpenFile(
            "选择目标表格",
            "Excel 工作簿 (*.xlsx)|*.xlsx",
            string.IsNullOrWhiteSpace(FilePath) ? null : Path.GetDirectoryName(FilePath));
        if (path is null)
        {
            return;
        }

        FilePath = path;
        if (string.IsNullOrWhiteSpace(Name))
        {
            Name = Path.GetFileNameWithoutExtension(path);
        }
        await DetectAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private async Task DetectAsync()
    {
        if (string.IsNullOrWhiteSpace(FilePath))
        {
            _dialogs.Info("请先选择表格", "请选择要写入的 Excel 工作簿。");
            return;
        }

        if (!File.Exists(FilePath))
        {
            _dialogs.Error("文件不存在", "所选表格不存在，请重新选择。");
            return;
        }

        if (HeaderRow < 1)
        {
            _dialogs.Info("表头行号无效", "表头行号从 1 开始，请填写正确的行号。");
            return;
        }

        IsBusy = true;
        try
        {
            var headers = await HeaderReader.ReadAsync(FilePath, HeaderRow).ConfigureAwait(true);
            if (headers.Fields.Count == 0)
            {
                _dialogs.Info("没有读到表头", "该行没有可用的列名。请确认表头行号是否正确，或先把第一行填写为字段名称。");
                return;
            }

            var current = Fields.ToDictionary(f => f.ColumnIndex, f => f.FieldName);
            Fields.Clear();
            foreach (var field in headers.Fields)
            {
                Fields.Add(new TemplateFieldDraftRow
                {
                    FieldName = current.TryGetValue(field.ColumnIndex, out var saved) && !string.IsNullOrWhiteSpace(saved)
                        ? saved
                        : field.FieldName,
                    ColumnIndex = field.ColumnIndex,
                    SortOrder = field.SortOrder,
                    CoreType = field.CoreType
                });
            }
            HasFields = true;
            HeaderSummary = $"已检测到 {Fields.Count} 个字段（表头第 {HeaderRow} 行）。字段名可双击修改。";
            _logger.Information("HeaderDetected {Path} {HeaderRow} {Fields}", FilePath, HeaderRow, Fields.Count);
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "HeaderDetectFailed");
            _dialogs.Error("检测表头失败", ex.Message);
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task SaveAsync()
    {
        if (IsBusy)
        {
            return;
        }

        if (string.IsNullOrWhiteSpace(Name))
        {
            _dialogs.Info("请填写模板名称", "模板名称用于在列表中区分不同表格。");
            return;
        }

        if (string.IsNullOrWhiteSpace(FilePath) || !File.Exists(FilePath))
        {
            _dialogs.Info("请选择表格文件", "模板必须绑定一个已存在的 Excel 工作簿。");
            return;
        }

        if (Fields.Count == 0)
        {
            _dialogs.Info("没有字段", "请先检测表头，至少保留一个字段。");
            return;
        }

        if (Fields.Any(f => string.IsNullOrWhiteSpace(f.FieldName)))
        {
            _dialogs.Info("字段名不能为空", "请为每个字段填写名称。");
            return;
        }

        IsBusy = true;
        try
        {
            var template = new Template
            {
                Id = _existing?.Id ?? 0,
                Name = Name.Trim(),
                FilePath = FilePath,
                HeaderRow = HeaderRow,
                CreatedAt = _existing?.CreatedAt ?? DateTimeOffset.Now,
                Fields = Fields.Select(row =>
                {
                    var prior = _existing?.Fields.FirstOrDefault(f =>
                        f.ColumnIndex == row.ColumnIndex && f.FieldName == row.FieldName);
                    return new TemplateField
                    {
                        Id = prior?.Id ?? 0,
                        TemplateId = _existing?.Id ?? 0,
                        FieldName = row.FieldName.Trim(),
                        ColumnIndex = row.ColumnIndex,
                        CoreType = row.CoreType,
                        SortOrder = row.SortOrder,
                        Rules = prior?.Rules ?? new List<FieldRule>()
                    };
                }).ToList()
            };

            SavedTemplate = _existing is null
                ? await _templates.CreateAsync(template).ConfigureAwait(true)
                : await _templates.UpdateAsync(template).ConfigureAwait(true);
            _logger.Information("TemplateSavedFromUi {TemplateId}", SavedTemplate.Id);
            CloseRequested?.Invoke(this, true);
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "TemplateSaveFailed");
            _dialogs.Error("保存模板失败", ex.Message);
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private void Cancel() => CloseRequested?.Invoke(this, false);
}
