using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Text.RegularExpressions;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using DingTalkChatExporter.App.Services;
using DingTalkChatExporter.Core.Management;
using DingTalkChatExporter.Core.Models;
using DingTalkChatExporter.Core.Services;
using Serilog;

namespace DingTalkChatExporter.App.ViewModels;

public sealed partial class RuleRowViewModel : ObservableObject
{
    [ObservableProperty]
    private string _fieldName = string.Empty;

    [ObservableProperty]
    private string _matchType = "regex";

    [ObservableProperty]
    private string _pattern = string.Empty;

    [ObservableProperty]
    private int _groupIndex = 1;

    public RuleRowViewModel()
    {
    }

    public RuleRowViewModel(ExtractionRule rule)
    {
        FieldName = rule.FieldName;
        MatchType = rule.MatchType;
        Pattern = rule.Pattern;
        GroupIndex = rule.GroupIndex;
    }

    public ExtractionRule ToRule() => new(FieldName.Trim(), MatchType.Trim(), Pattern, GroupIndex);
}

public sealed record RuleLibrarySummary(
    string Id,
    string Name,
    bool IsDefault,
    int RuleCount,
    DateTimeOffset UpdatedAt)
{
    public string Display => IsDefault ? Name + "（默认）" : Name;
    public string Subtitle => $"{RuleCount} 条规则 · {UpdatedAt.ToLocalTime():yyyy-MM-dd HH:mm}";
}

public sealed record RuleTestResult(string FieldName, string MatchType, string Value, string ConfidenceText);

public sealed record RuleVersionRow(string VersionId, string SavedAt, string Summary);

/// <summary>
/// Rule library editor. Rules remain deterministic (regex/keyword); the page never
/// invents values and only previews what the rule engine returns for sample text.
/// </summary>
public sealed partial class RulesPageViewModel : PageViewModelBase
{
    private static readonly string[] SupportedMatchTypes = { "regex", "keyword" };

    private readonly RuleLibraryManager _manager;
    private readonly RuleEngine _engine = new();
    private readonly ILogger _logger;
    private bool _suppressSelection;
    private bool _loading;
    private string? _editingId;

    [ObservableProperty]
    private RuleLibrarySummary? _selectedLibrary;

    [ObservableProperty]
    private string _editingName = string.Empty;

    [ObservableProperty]
    private RuleRowViewModel? _selectedRule;

    [ObservableProperty]
    private string _testText = "订单号 A-100，金额 100 元，客户张三";

    [ObservableProperty]
    private string _statusMessage = "创建或选择规则库后开始编辑。";

    [ObservableProperty]
    private bool _isDirty;

    [ObservableProperty]
    private bool _isDefault;

    public RulesPageViewModel(WorkbenchService workbench, ILogger logger)
        : base("rules", "规则库", "配置字段提取规则、模板与低置信度处理。")
    {
        _manager = workbench.RuleLibraries;
        _logger = logger.ForContext<RulesPageViewModel>();
        RefreshLibraries();
    }

    public ObservableCollection<RuleLibrarySummary> Libraries { get; } = new();

    public ObservableCollection<RuleRowViewModel> Rules { get; } = new();

    public ObservableCollection<RuleTestResult> TestResults { get; } = new();

    public ObservableCollection<RuleVersionRow> Versions { get; } = new();

    public IReadOnlyList<string> MatchTypes => SupportedMatchTypes;

    public bool HasSelection => SelectedLibrary is not null;

    partial void OnSelectedLibraryChanged(RuleLibrarySummary? value)
    {
        if (_suppressSelection)
        {
            return;
        }

        OnPropertyChanged(nameof(HasSelection));
        LoadLibrary(value?.Id);
    }

    partial void OnEditingNameChanged(string value) => MarkDirty();

    partial void OnIsDefaultChanged(bool value) => MarkDirty();

    [RelayCommand]
    private void RefreshLibraries(string? selectId = null)
    {
        var previous = selectId ?? SelectedLibrary?.Id;
        _suppressSelection = true;
        try
        {
            Libraries.Clear();
            foreach (var library in _manager.LoadAll().OrderByDescending(item => item.IsDefault).ThenBy(item => item.Name))
            {
                Libraries.Add(new RuleLibrarySummary(
                    library.Id,
                    library.Name,
                    library.IsDefault,
                    library.Rules.Count,
                    library.UpdatedAt));
            }

            SelectedLibrary = Libraries.FirstOrDefault(item => item.Id == previous) ?? Libraries.FirstOrDefault();
        }
        finally
        {
            _suppressSelection = false;
        }

        LoadLibrary(SelectedLibrary?.Id);
    }

    [RelayCommand]
    private void CreateLibrary()
    {
        try
        {
            var name = "新建规则库 " + DateTime.Now.ToString("MM-dd HH:mm");
            var library = _manager.Create(name);
            RefreshLibraries(library.Id);
            StatusMessage = "已创建规则库，保存前请补充字段规则。";
        }
        catch (Exception ex)
        {
            StatusMessage = "创建失败：" + ex.Message;
            _logger.Warning(ex, "Failed to create rule library.");
        }
    }

    [RelayCommand]
    private void DuplicateLibrary()
    {
        if (SelectedLibrary is null)
        {
            StatusMessage = "请先选择要复制的规则库。";
            return;
        }

        try
        {
            var copy = _manager.Duplicate(SelectedLibrary.Id);
            RefreshLibraries(copy.Id);
            StatusMessage = "已复制规则库：" + copy.Name;
        }
        catch (Exception ex)
        {
            StatusMessage = "复制失败：" + ex.Message;
            _logger.Warning(ex, "Failed to duplicate rule library.");
        }
    }

    [RelayCommand]
    private void DeleteLibrary()
    {
        if (SelectedLibrary is null)
        {
            StatusMessage = "请先选择要删除的规则库。";
            return;
        }

        try
        {
            var name = SelectedLibrary.Name;
            _manager.Delete(SelectedLibrary.Id);
            RefreshLibraries();
            StatusMessage = $"规则库“{name}”已移入 rule-libraries/.trash，可从磁盘手动恢复。";
        }
        catch (Exception ex)
        {
            StatusMessage = "删除失败：" + ex.Message;
            _logger.Warning(ex, "Failed to remove rule library.");
        }
    }

    [RelayCommand]
    private void SetDefault()
    {
        if (SelectedLibrary is null)
        {
            return;
        }

        try
        {
            _manager.SetDefault(SelectedLibrary.Id);
            RefreshLibraries(SelectedLibrary.Id);
            StatusMessage = "已设为默认规则库。";
        }
        catch (Exception ex)
        {
            StatusMessage = "设置默认规则库失败：" + ex.Message;
            _logger.Warning(ex, "Failed to set default rule library.");
        }
    }

    [RelayCommand]
    private void AddRule()
    {
        if (SelectedLibrary is null)
        {
            return;
        }

        var row = new RuleRowViewModel
        {
            FieldName = "新字段",
            MatchType = "regex",
            Pattern = string.Empty,
            GroupIndex = 1
        };
        Attach(row);
        Rules.Add(row);
        SelectedRule = row;
        MarkDirty();
    }

    [RelayCommand]
    private void RemoveRule(RuleRowViewModel? row)
    {
        row ??= SelectedRule;
        if (row is null)
        {
            return;
        }

        row.PropertyChanged -= OnRuleChanged;
        Rules.Remove(row);
        SelectedRule = Rules.LastOrDefault();
        MarkDirty();
    }

    [RelayCommand]
    private void SaveLibrary()
    {
        if (SelectedLibrary is null)
        {
            StatusMessage = "没有可保存的规则库。";
            return;
        }

        var name = EditingName.Trim();
        if (name.Length == 0)
        {
            StatusMessage = "规则库名称不能为空。";
            return;
        }

        var invalid = Rules.FirstOrDefault(row =>
            string.IsNullOrWhiteSpace(row.FieldName)
            || string.IsNullOrWhiteSpace(row.Pattern)
            || !SupportedMatchTypes.Contains(row.MatchType, StringComparer.OrdinalIgnoreCase));
        if (invalid is not null)
        {
            StatusMessage = $"规则“{invalid.FieldName}”不完整：字段名、匹配类型和模式都必须填写。";
            return;
        }

        var regexError = Rules
            .Where(row => string.Equals(row.MatchType, "regex", StringComparison.OrdinalIgnoreCase))
            .Select(row => (Row: row, Error: ValidateRegex(row.Pattern)))
            .FirstOrDefault(item => item.Error is not null);
        if (regexError.Row is not null)
        {
            StatusMessage = $"规则“{regexError.Row.FieldName}”正则无效：{regexError.Error}";
            return;
        }

        try
        {
            var existing = _manager.Load(SelectedLibrary.Id)
                            ?? throw new InvalidOperationException("规则库文件已不存在。");
            var updated = existing with
            {
                Name = name,
                IsDefault = IsDefault,
                Rules = Rules.Select(row => row.ToRule()).ToList()
            };
            _manager.Save(updated);
            RefreshLibraries(updated.Id);
            IsDirty = false;
            StatusMessage = $"已保存规则库“{name}”，上一个版本已进入版本历史。";
        }
        catch (Exception ex)
        {
            StatusMessage = "保存失败：" + ex.Message;
            _logger.Warning(ex, "Failed to save rule library {LibraryId}.", SelectedLibrary.Id);
        }
    }

    [RelayCommand]
    private void TestRules()
    {
        TestResults.Clear();
        if (string.IsNullOrWhiteSpace(TestText))
        {
            StatusMessage = "请输入测试消息内容。";
            return;
        }

        foreach (var row in Rules)
        {
            var result = _engine.Evaluate(TestText, row.ToRule());
            TestResults.Add(new RuleTestResult(
                string.IsNullOrWhiteSpace(row.FieldName) ? "（未命名字段）" : row.FieldName,
                row.MatchType,
                result.Value ?? "（未匹配）",
                result.Confidence switch
                {
                    Confidence.High => "高",
                    Confidence.Low => "低",
                    _ => "无"
                }));
        }

        StatusMessage = TestResults.Count == 0
            ? "当前规则库没有规则可测试。"
            : $"已对 {TestResults.Count} 条规则完成本地测试。";
    }

    [RelayCommand]
    private void RestoreVersion(RuleVersionRow? version)
    {
        if (SelectedLibrary is null || version is null)
        {
            return;
        }

        try
        {
            var restored = _manager.RestoreVersion(SelectedLibrary.Id, version.VersionId);
            if (restored is null)
            {
                StatusMessage = "找不到该历史版本。";
                return;
            }

            RefreshLibraries(restored.Id);
            StatusMessage = $"已恢复 {version.SavedAt} 的版本。";
        }
        catch (Exception ex)
        {
            StatusMessage = "恢复版本失败：" + ex.Message;
            _logger.Warning(ex, "Failed to restore rule library version.");
        }
    }

    private void LoadLibrary(string? id)
    {
        _loading = true;
        try
        {
            foreach (var row in Rules)
            {
                row.PropertyChanged -= OnRuleChanged;
            }

            Rules.Clear();
            Versions.Clear();
            TestResults.Clear();
            _editingId = id;
            if (string.IsNullOrWhiteSpace(id))
            {
                EditingName = string.Empty;
                IsDefault = false;
                IsDirty = false;
                StatusMessage = "还没有规则库，点击“新建”开始配置。";
                return;
            }

            var library = _manager.Load(id);
            if (library is null)
            {
                StatusMessage = "规则库文件无法读取，已保留列表项但不加载内容。";
                return;
            }

            EditingName = library.Name;
            IsDefault = library.IsDefault;
            foreach (var rule in library.Rules)
            {
                var row = new RuleRowViewModel(rule);
                Attach(row);
                Rules.Add(row);
            }

            foreach (var version in library.Versions.OrderByDescending(item => item.SavedAt))
            {
                Versions.Add(new RuleVersionRow(
                    version.VersionId,
                    version.SavedAt.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss"),
                    $"{version.Rules.Count} 条规则 · {version.Note ?? "历史版本"}"));
            }

            IsDirty = false;
            StatusMessage = $"已加载规则库“{library.Name}”。";
        }
        catch (Exception ex)
        {
            StatusMessage = "读取规则库失败：" + ex.Message;
            _logger.Warning(ex, "Failed to load rule library {LibraryId}.", id);
        }
        finally
        {
            _loading = false;
        }
    }

    private void Attach(RuleRowViewModel row) => row.PropertyChanged += OnRuleChanged;

    private void OnRuleChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (!_loading)
        {
            MarkDirty();
        }
    }

    private void MarkDirty()
    {
        if (!_loading && _editingId is not null)
        {
            IsDirty = true;
        }
    }

    private static string? ValidateRegex(string pattern)
    {
        try
        {
            _ = new Regex(pattern, RegexOptions.IgnoreCase, TimeSpan.FromSeconds(1));
            return null;
        }
        catch (ArgumentException ex)
        {
            return ex.Message;
        }
    }
}
