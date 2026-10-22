namespace TicketTool.Core.Models;

/// <summary>单个字段的抽取结果。</summary>
public sealed class ParsedField
{
    /// <summary>字段名称。</summary>
    public string FieldName { get; set; } = string.Empty;

    /// <summary>字段主键。</summary>
    public long FieldId { get; set; }

    /// <summary>抽取值。</summary>
    public string Value { get; set; } = string.Empty;

    /// <summary>起始偏移。</summary>
    public int StartOffset { get; set; }

    /// <summary>结束偏移。</summary>
    public int EndOffset { get; set; }

    /// <summary>命中的规则类型。</summary>
    public RuleType? MatchedRuleType { get; set; }

    /// <summary>是否命中并满足值模式。</summary>
    public bool IsValid { get; set; } = true;

    /// <summary>是否存在多条规则命中。</summary>
    public bool HasConflict { get; set; }

    /// <summary>冲突说明，面向用户的业务语言。</summary>
    public string? ConflictMessage { get; set; }
}

/// <summary>一次解析的整体结果。</summary>
public sealed class TicketParseResult
{
    /// <summary>原文。</summary>
    public string RawText { get; set; } = string.Empty;

    /// <summary>已识别字段。</summary>
    public List<ParsedField> Fields { get; set; } = new();

    /// <summary>未识别字段的名称。</summary>
    public List<string> MissingFields { get; set; } = new();

    /// <summary>解析耗时。</summary>
    public TimeSpan Elapsed { get; set; }

    /// <summary>按字段名取值。</summary>
    public string? GetValue(string fieldName)
        => Fields.FirstOrDefault(f => f.FieldName == fieldName)?.Value;
}

/// <summary>解析进度。</summary>
public sealed class ParseProgress
{
    /// <summary>进度百分比。</summary>
    public int Percent { get; init; }

    /// <summary>当前步骤说明。</summary>
    public string Message { get; init; } = string.Empty;
}
