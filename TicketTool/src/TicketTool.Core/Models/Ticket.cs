namespace TicketTool.Core.Models;

/// <summary>已入库工单。</summary>
public sealed class Ticket
{
    /// <summary>工单主键。</summary>
    public long Id { get; set; }

    /// <summary>所属模板。</summary>
    public long TemplateId { get; set; }

    /// <summary>字段值 JSON。</summary>
    public string DataJson { get; set; } = "{}";

    /// <summary>原始文本。</summary>
    public string RawText { get; set; } = string.Empty;

    /// <summary>入库时间。</summary>
    public DateTimeOffset CreatedAt { get; set; }

    /// <summary>字段溯源区间。</summary>
    public List<FieldSpan> Spans { get; set; } = new();
}

/// <summary>字段在原文中的字符区间。</summary>
public sealed class FieldSpan
{
    /// <summary>字段名称。</summary>
    public string FieldName { get; set; } = string.Empty;

    /// <summary>起始字符偏移（含）。</summary>
    public int StartOffset { get; set; }

    /// <summary>结束字符偏移（不含）。</summary>
    public int EndOffset { get; set; }
}

/// <summary>待写入数据库的工单。</summary>
public sealed class TicketInsert
{
    /// <summary>所属模板。</summary>
    public long TemplateId { get; set; }

    /// <summary>字段值。</summary>
    public Dictionary<string, string?> Values { get; set; } = new();

    /// <summary>原文。</summary>
    public string RawText { get; set; } = string.Empty;

    /// <summary>溯源自段。</summary>
    public List<FieldSpan> Spans { get; set; } = new();

    /// <summary>入库时间。</summary>
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.Now;
}
