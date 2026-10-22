using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Serilog;
using TicketTool.App.Services;
using TicketTool.Core.Models;
using TicketTool.Core.Parsing;
using TicketTool.Core.Rules;
using TicketTool.Core.Templates;

namespace TicketTool.App.ViewModels;

/// <summary>可编辑的规则行。</summary>
public sealed partial class RuleRow : ObservableObject
{
    [ObservableProperty]
    private RuleType _ruleType;

    [ObservableProperty]
    private string _aliasesText = string.Empty;

    [ObservableProperty]
    private string _valuePattern = string.Empty;

    [ObservableProperty]
    private string _presetKey = string.Empty;

    [ObservableProperty]
    private string _regexPattern = string.Empty;

    [ObservableProperty]
    private string _regexGroup = string.Empty;

    [ObservableProperty]
    private string _separator = " ";

    [ObservableProperty]
    private int _segmentIndex;

    [ObservableProperty]
    private int _priority;

    [ObservableProperty]
    private bool _enabled = true;

    private RuleRow()
    {
    }

    /// <summary>原始规则，用于保留主键。</summary>
    public FieldRule Source { get; private init; } = new();

    public bool IsAnchor => RuleType == RuleType.Anchor;

    public bool IsPreset => RuleType == RuleType.Preset;

    public bool IsRegex => RuleType == RuleType.Regex;

    public bool IsSegment => RuleType == RuleType.Segment;

    public string TypeText => RuleType switch
    {
        RuleType.Preset => "预置字段",
        RuleType.Anchor => "字段名称",
        RuleType.Regex => "高级模式",
        _ => "分段取值"
    };

    public string Description => $"优先级 {Priority} · {TypeText}";

    public bool IsUsable => Enabled;

    /// <summary>从持久化规则创建可编辑行。</summary>
    public static RuleRow FromRule(FieldRule rule, string fieldName)
    {
        var row = new RuleRow
        {
            Source = rule,
            RuleType = rule.RuleType,
            Priority = rule.Priority,
            Enabled = rule.Enabled
        };
        switch (rule.RuleType)
        {
            case RuleType.Preset:
                var preset = RuleConfigSerializer.Parse<PresetRuleConfig>(rule.ConfigJson);
                row.PresetKey = string.IsNullOrWhiteSpace(preset.PresetKey) ? fieldName : preset.PresetKey;
                row.AliasesText = string.Join("、", preset.ExtraAliases);
                break;
            case RuleType.Anchor:
                var anchor = RuleConfigSerializer.Parse<AnchorRuleConfig>(rule.ConfigJson);
                row.AliasesText = string.Join("、", anchor.Aliases);
                row.ValuePattern = anchor.ValuePattern ?? string.Empty;
                break;
            case RuleType.Regex:
                var regex = RuleConfigSerializer.Parse<RegexRuleConfig>(rule.ConfigJson);
                row.RegexPattern = regex.Pattern;
                row.RegexGroup = regex.GroupName ?? string.Empty;
                break;
            default:
                var segment = RuleConfigSerializer.Parse<SegmentRuleConfig>(rule.ConfigJson);
                row.AliasesText = string.Join("、", segment.Aliases);
                row.Separator = segment.Separator;
                row.SegmentIndex = segment.Index;
                break;
        }
        return row;
    }

    /// <summary>创建一条新规则行。</summary>
    public static RuleRow CreateDefault(RuleType type, TemplateField field)
    {
        var baseRule = new FieldRule
        {
            TemplateFieldId = field.Id,
            RuleType = type,
            Priority = 0,
            Enabled = true,
            ConfigJson = "{}"
        };
        var row = FromRule(baseRule, field.FieldName);
        row.RuleType = type;
        switch (type)
        {
            case RuleType.Preset:
                row.PresetKey = PresetCatalog.Guess(field.FieldName)?.Key ?? PresetCatalog.Definitions[0].Key;
                row.AliasesText = field.FieldName;
                break;
            case RuleType.Anchor:
                row.AliasesText = field.FieldName;
                break;
            case RuleType.Segment:
                row.AliasesText = field.FieldName;
                break;
            case RuleType.Regex:
                row.RegexPattern = @"(?<value>.+)";
                break;
        }
        return row;
    }

    /// <summary>转换为持久化规则。</summary>
    public FieldRule ToRule()
    {
        var aliases = SplitAliases(AliasesText);
        var config = RuleType switch
        {
            RuleType.Preset => RuleConfigSerializer.Serialize(new PresetRuleConfig
            {
                PresetKey = PresetKey,
                ExtraAliases = aliases
            }),
            RuleType.Anchor => RuleConfigSerializer.Serialize(new AnchorRuleConfig
            {
                Aliases = aliases,
                ValuePattern = EmptyToNull(ValuePattern)
            }),
            RuleType.Regex => RuleConfigSerializer.Serialize(new RegexRuleConfig
            {
                Pattern = RegexPattern,
                GroupName = EmptyToNull(RegexGroup)
            }),
            _ => RuleConfigSerializer.Serialize(new SegmentRuleConfig
            {
                Aliases = aliases,
                Separator = string.IsNullOrEmpty(Separator) ? " " : Separator,
                Index = SegmentIndex
            })
        };

        return new FieldRule
        {
            Id = Source.Id,
            TemplateFieldId = Source.TemplateFieldId,
            RuleType = RuleType,
            ConfigJson = config,
            Priority = Priority,
            Enabled = Enabled,
            Version = Source.Version
        };
    }

    /// <summary>面向用户的校验说明，null 表示通过。</summary>
    public string? Validate()
    {
        var aliases = SplitAliases(AliasesText);
        return RuleType switch
        {
            RuleType.Preset when string.IsNullOrWhiteSpace(PresetKey) => "预置字段需要选择一个预置类型。",
            RuleType.Anchor when aliases.Count == 0 => "字段名称规则至少需要一个名称。",
            RuleType.Segment when aliases.Count == 0 => "分段取值至少需要一个名称。",
            RuleType.Regex when string.IsNullOrWhiteSpace(RegexPattern) => "高级模式需要填写匹配表达式。",
            RuleType.Regex when CompiledRegexInvalid(RegexPattern) => "高级模式的表达式无法使用，请检查括号和特殊字符。",
            _ => null
        };
    }

    partial void OnRuleTypeChanged(RuleType value)
    {
        OnPropertyChanged(nameof(IsAnchor));
        OnPropertyChanged(nameof(IsPreset));
        OnPropertyChanged(nameof(IsRegex));
        OnPropertyChanged(nameof(IsSegment));
        OnPropertyChanged(nameof(TypeText));
        OnPropertyChanged(nameof(Description));
    }

    partial void OnPriorityChanged(int value) => OnPropertyChanged(nameof(Description));

    private static bool CompiledRegexInvalid(string pattern)
    {
        try
        {
            _ = RuleConfigSerializer.CreateRegex(pattern);
            return false;
        }
        catch (ArgumentException)
        {
            return true;
        }
    }

    internal static List<string> SplitAliases(string text)
        => text.Split(new[] { ',', '，', ';', '；', '、', '\n', '\r' }, StringSplitOptions.RemoveEmptyEntries)
            .Select(item => item.Trim())
            .Where(item => item.Length > 0)
            .Distinct(StringComparer.Ordinal)
            .ToList();

    private static string? EmptyToNull(string? value)
        => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}

/// <summary>字段规则编辑器：试跑、增删规则、保存并生成版本。</summary>
public sealed partial class RuleEditorViewModel : ObservableObject
{
    private readonly TemplateService _templates;
    private readonly ILogger _logger;
    private readonly Template _draft;
    private TemplateField? _currentField;

    [ObservableProperty]
    private TemplateField? _selectedField;

    [ObservableProperty]
    private RuleRow? _selectedRule;

    [ObservableProperty]
    private RuleTrialResult? _trialResult;

    [ObservableProperty]
    private string _trialValue = string.Empty;

    [ObservableProperty]
    private string _trialMessage = "点击“试跑当前字段”，可以用当前原文立即验证规则。";

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private string _windowTitle;

    /// <summary>创建规则编辑器。</summary>
    public RuleEditorViewModel(
        TemplateService templates,
        Template template,
        string rawText,
        ILogger? logger = null)
    {
        _templates = templates;
        _logger = logger ?? Log.ForContext<RuleEditorViewModel>();
        RawText = rawText ?? string.Empty;
        TemplateName = template.Name;
        WindowTitle = $"规则编辑 · {template.Name}";
        _draft = new Template
        {
            Id = template.Id,
            Name = template.Name,
            FilePath = template.FilePath,
            HeaderRow = template.HeaderRow,
            CreatedAt = template.CreatedAt,
            UpdatedAt = template.UpdatedAt,
            Fields = template.Fields.Select(field => new TemplateField
            {
                Id = field.Id,
                TemplateId = field.TemplateId,
                FieldName = field.FieldName,
                ColumnIndex = field.ColumnIndex,
                CoreType = field.CoreType,
                SortOrder = field.SortOrder,
                Rules = field.Rules.Select(rule => new FieldRule
                {
                    Id = rule.Id,
                    TemplateFieldId = rule.TemplateFieldId,
                    RuleType = rule.RuleType,
                    ConfigJson = rule.ConfigJson,
                    Priority = rule.Priority,
                    Enabled = rule.Enabled,
                    Version = rule.Version
                }).ToList()
            }).ToList()
        };

        foreach (var field in _draft.Fields.OrderBy(f => f.SortOrder).ThenBy(f => f.Id))
        {
            Fields.Add(field);
        }

        RuleTypeOptions = new[]
        {
            new OptionItem<RuleType>("字段名称锚点", RuleType.Anchor),
            new OptionItem<RuleType>("预置领域字段", RuleType.Preset),
            new OptionItem<RuleType>("分段取值", RuleType.Segment),
            new OptionItem<RuleType>("高级模式", RuleType.Regex)
        };
        SelectedField = Fields.FirstOrDefault();
    }

    public string TemplateName { get; }

    public string RawText { get; }

    public ObservableCollection<TemplateField> Fields { get; } = new();

    public ObservableCollection<RuleRow> Rules { get; } = new();

    public ObservableCollection<RuleTrialCandidate> Candidates { get; } = new();

    public IReadOnlyList<OptionItem<RuleType>> RuleTypeOptions { get; }

    public IReadOnlyList<PresetDefinition> PresetOptions => PresetCatalog.Definitions;

    public string RawTextHint => string.IsNullOrWhiteSpace(RawText)
        ? "主界面还没有工单原文。可以先保存规则，回到主界面粘贴文本后再试跑。"
        : $"已载入原文 {RawText.Length:N0} 个字符，可直接试跑。";

    /// <summary>请求关闭窗口，参数表示是否保存成功。</summary>
    public event EventHandler<bool>? CloseRequested;

    [RelayCommand]
    private void AddRule()
    {
        if (SelectedField is null)
        {
            return;
        }

        var type = SelectedRule?.RuleType ?? RuleType.Anchor;
        var row = RuleRow.CreateDefault(type, SelectedField);
        Rules.Add(row);
        SelectedRule = row;
        TrialMessage = "已新增规则，填写完成后可立即试跑。";
    }

    [RelayCommand]
    private void RemoveRule()
    {
        if (SelectedRule is null)
        {
            return;
        }

        var index = Rules.IndexOf(SelectedRule);
        Rules.Remove(SelectedRule);
        SelectedRule = Rules.Count == 0 ? null : Rules[Math.Clamp(index, 0, Rules.Count - 1)];
    }

    [RelayCommand]
    private void Trial()
    {
        if (SelectedField is null)
        {
            return;
        }

        if (string.IsNullOrWhiteSpace(RawText))
        {
            TrialMessage = "主界面还没有工单原文，无法试跑。请先粘贴一单工单文本。";
            return;
        }

        var invalid = Rules.Select(r => (Row: r, Error: r.Validate())).FirstOrDefault(x => x.Error is not null);
        if (invalid.Row is not null)
        {
            TrialMessage = invalid.Error ?? "规则不完整。";
            return;
        }

        CommitRules(SelectedField);
        var result = RuleTrialEngine.Run(RawText, SelectedField);
        TrialResult = result;
        Candidates.Clear();
        foreach (var candidate in result.Candidates)
        {
            Candidates.Add(candidate);
        }

        TrialValue = string.IsNullOrWhiteSpace(result.SelectedValue) ? "未命中" : result.SelectedValue;
        TrialMessage = result.ConflictMessage
                         ?? (result.Candidates.Count == 0
                             ? "所有规则都没有命中，请检查字段名称是否与原文一致。"
                             : $"共 {result.Candidates.Count} 条规则命中，取值为“{TrialValue}”。");
    }

    [RelayCommand]
    private async Task SaveAsync()
    {
        if (IsBusy)
        {
            return;
        }

        CommitRules(SelectedField);
        foreach (var field in _draft.Fields)
        {
            foreach (var rule in field.Rules.Where(r => r.Enabled))
            {
                var row = RuleRow.FromRule(rule, field.FieldName);
                var error = row.Validate();
                if (error is not null)
                {
                    SelectedField = field;
                    TrialMessage = $"字段“{field.FieldName}”：{error}";
                    return;
                }
            }
        }

        IsBusy = true;
        try
        {
            await _templates.SaveRulesAsync(_draft).ConfigureAwait(true);
            _logger.Information("RulesSavedFromUi {TemplateId}", _draft.Id);
            CloseRequested?.Invoke(this, true);
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "RuleSaveFailed");
            TrialMessage = "保存失败：" + ex.Message;
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private void Cancel() => CloseRequested?.Invoke(this, false);

    partial void OnSelectedFieldChanged(TemplateField? value)
    {
        CommitRules(_currentField);
        _currentField = value;
        Rules.Clear();
        Candidates.Clear();
        TrialResult = null;
        TrialValue = string.Empty;
        if (value is null)
        {
            SelectedRule = null;
            return;
        }

        foreach (var rule in value.Rules.OrderByDescending(r => r.Priority).ThenBy(r => r.Id))
        {
            Rules.Add(RuleRow.FromRule(rule, value.FieldName));
        }
        SelectedRule = Rules.FirstOrDefault();
        TrialMessage = Rules.Count == 0
            ? "该字段还没有规则，请点击“新增规则”。"
            : "点击“试跑当前字段”，可以用当前原文立即验证规则。";
        OnPropertyChanged(nameof(RawTextHint));
    }

    private void CommitRules(TemplateField? field)
    {
        if (field is null)
        {
            return;
        }
        field.Rules = Rules.Select(row => row.ToRule()).ToList();
    }
}
