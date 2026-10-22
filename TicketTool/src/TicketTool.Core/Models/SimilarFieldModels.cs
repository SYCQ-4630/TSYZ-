namespace TicketTool.Core.Models;

/// <summary>原文中的一个相似字段候选组。</summary>
public sealed class SimilarFieldMatch
{
    /// <summary>原文中的标签，例如“客户联系电话”。</summary>
    public string SourceLabel { get; set; } = string.Empty;

    /// <summary>标签后识别到的值。</summary>
    public string SourceValue { get; set; } = string.Empty;

    /// <summary>值在原文中的起始偏移。</summary>
    public int ValueStart { get; set; }

    /// <summary>值在原文中的结束偏移。</summary>
    public int ValueEnd { get; set; }

    /// <summary>相同标签在原文中出现的次数。</summary>
    public int OccurrenceCount { get; set; } = 1;

    /// <summary>可选择的模板字段。</summary>
    public List<SimilarFieldCandidate> Candidates { get; set; } = new();
}

/// <summary>相似字段对应的模板列。</summary>
public sealed class SimilarFieldCandidate
{
    /// <summary>字段主键。</summary>
    public long FieldId { get; set; }

    /// <summary>字段名称。</summary>
    public string FieldName { get; set; } = string.Empty;

    /// <summary>零基列号。</summary>
    public int ColumnIndex { get; set; }

    /// <summary>相似度，0 至 1。</summary>
    public double Score { get; set; }

    /// <summary>匹配原因，面向用户的业务语言。</summary>
    public string Reason { get; set; } = string.Empty;

    /// <summary>当前预览中的值，仅供弹窗参考。</summary>
    public string? CurrentValue { get; set; }
}
