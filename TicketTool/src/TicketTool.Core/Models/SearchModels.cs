namespace TicketTool.Core.Models;

/// <summary>搜索结果行。</summary>
public sealed class TicketSearchResult
{
    /// <summary>工单主键。</summary>
    public long TicketId { get; set; }

    /// <summary>模板主键。</summary>
    public long TemplateId { get; set; }

    /// <summary>模板名称。</summary>
    public string TemplateName { get; set; } = string.Empty;

    /// <summary>命中字段名称。</summary>
    public string FieldName { get; set; } = string.Empty;

    /// <summary>命中字段展示值。</summary>
    public string DisplayValue { get; set; } = string.Empty;

    /// <summary>入库时间。</summary>
    public DateTimeOffset CreatedAt { get; set; }
}

/// <summary>搜索请求。</summary>
public sealed class TicketSearchRequest
{
    /// <summary>关键词。</summary>
    public string Query { get; set; } = string.Empty;

    /// <summary>搜索域。</summary>
    public SearchDomain Domain { get; set; } = SearchDomain.Auto;

    /// <summary>限定模板，null 表示全部模板。</summary>
    public long? TemplateId { get; set; }

    /// <summary>页码，从 0 开始。</summary>
    public int PageIndex { get; set; }

    /// <summary>每页大小。</summary>
    public int PageSize { get; set; } = 50;
}

/// <summary>搜索结果分页。</summary>
public sealed class TicketSearchPage
{
    /// <summary>当前页数据。</summary>
    public List<TicketSearchResult> Items { get; set; } = new();

    /// <summary>实际使用的搜索域。</summary>
    public SearchDomain EffectiveDomain { get; set; }

    /// <summary>总命中数。</summary>
    public int TotalCount { get; set; }

    /// <summary>耗时。</summary>
    public TimeSpan Elapsed { get; set; }
}

/// <summary>搜索关键词类型判定结果。</summary>
public sealed record SearchQueryClassification(SearchDomain Domain, string NormalizedValue)
{
    /// <summary>无法判定时的安全默认域。</summary>
    public static SearchQueryClassification Unknown(string value)
        => new(SearchDomain.TicketNo, value);
}
