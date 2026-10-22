using System.Text.Json.Serialization;

namespace TicketTool.Core.Models;

/// <summary>锚点规则配置。</summary>
public sealed class AnchorRuleConfig
{
    /// <summary>字段别名（不含冒号）。</summary>
    public List<string> Aliases { get; set; } = new();

    /// <summary>候选值校验模式，空表示不校验。</summary>
    public string? ValuePattern { get; set; }
}

/// <summary>预置规则配置。</summary>
public sealed class PresetRuleConfig
{
    /// <summary>预置模式键，例如 ticket_no。</summary>
    public string PresetKey { get; set; } = string.Empty;

    /// <summary>用户补充的别名。</summary>
    public List<string> ExtraAliases { get; set; } = new();
}

/// <summary>正则规则配置。</summary>
public sealed class RegexRuleConfig
{
    /// <summary>正则表达式。</summary>
    public string Pattern { get; set; } = string.Empty;

    /// <summary>取值分组名，默认 value。</summary>
    public string? GroupName { get; set; }
}

/// <summary>分段规则配置。</summary>
public sealed class SegmentRuleConfig
{
    /// <summary>锚点别名。</summary>
    public List<string> Aliases { get; set; } = new();

    /// <summary>分隔符，支持普通字符串。</summary>
    public string Separator { get; set; } = " ";

    /// <summary>零基段索引。</summary>
    public int Index { get; set; }
}

/// <summary>规则版本快照内容。</summary>
public sealed class RuleVersionPayload
{
    /// <summary>模板主键。</summary>
    public long TemplateId { get; set; }

    /// <summary>保存时的字段规则。</summary>
    public List<RuleVersionField> Fields { get; set; } = new();

    /// <summary>生成时间。</summary>
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.Now;
}

/// <summary>规则版本中的单个字段。</summary>
public sealed class RuleVersionField
{
    /// <summary>字段名称。</summary>
    public string FieldName { get; set; } = string.Empty;

    /// <summary>核心类型。</summary>
    public CoreFieldType CoreType { get; set; }

    /// <summary>规则列表。</summary>
    public List<RuleVersionRule> Rules { get; set; } = new();
}

/// <summary>规则版本中的单条规则。</summary>
public sealed class RuleVersionRule
{
    /// <summary>规则类型。</summary>
    [JsonConverter(typeof(JsonStringEnumConverter))]
    public RuleType RuleType { get; set; }

    /// <summary>配置 JSON。</summary>
    public string ConfigJson { get; set; } = "{}";

    /// <summary>优先级。</summary>
    public int Priority { get; set; }

    /// <summary>是否启用。</summary>
    public bool Enabled { get; set; } = true;
}

/// <summary>规则版本摘要。</summary>
public sealed class RuleVersionSummary
{
    /// <summary>版本主键。</summary>
    public long Id { get; set; }

    /// <summary>模板主键。</summary>
    public long TemplateId { get; set; }

    /// <summary>创建时间。</summary>
    public DateTimeOffset CreatedAt { get; set; }
}
