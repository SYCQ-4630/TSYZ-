namespace TicketTool.Core.Models;

/// <summary>一个 XLSX 模板及其字段定义。</summary>
public sealed class Template
{
    /// <summary>模板主键。</summary>
    public long Id { get; set; }

    /// <summary>模板名称，全局唯一。</summary>
    public string Name { get; set; } = string.Empty;

    /// <summary>目标 XLSX 的绝对路径。</summary>
    public string FilePath { get; set; } = string.Empty;

    /// <summary>表头所在行号，从 1 开始。</summary>
    public int HeaderRow { get; set; } = 1;

    /// <summary>创建时间。</summary>
    public DateTimeOffset CreatedAt { get; set; }

    /// <summary>最近更新时间。</summary>
    public DateTimeOffset UpdatedAt { get; set; }

    /// <summary>模板字段。</summary>
    public List<TemplateField> Fields { get; set; } = new();
}

/// <summary>模板中的一列。</summary>
public sealed class TemplateField
{
    /// <summary>字段主键。</summary>
    public long Id { get; set; }

    /// <summary>所属模板。</summary>
    public long TemplateId { get; set; }

    /// <summary>显示名称，同时作为数据 JSON 的属性名。</summary>
    public string FieldName { get; set; } = string.Empty;

    /// <summary>XLSX 中的零基列号。</summary>
    public int ColumnIndex { get; set; }

    /// <summary>核心检索类型。</summary>
    public CoreFieldType CoreType { get; set; }

    /// <summary>显示排序。</summary>
    public int SortOrder { get; set; }

    /// <summary>该字段的规则。</summary>
    public List<FieldRule> Rules { get; set; } = new();
}

/// <summary>字段抽取规则。</summary>
public sealed class FieldRule
{
    /// <summary>规则主键。</summary>
    public long Id { get; set; }

    /// <summary>所属模板字段。</summary>
    public long TemplateFieldId { get; set; }

    /// <summary>规则类型。</summary>
    public RuleType RuleType { get; set; }

    /// <summary>规则配置 JSON。</summary>
    public string ConfigJson { get; set; } = "{}";

    /// <summary>数值越大优先级越高。</summary>
    public int Priority { get; set; }

    /// <summary>是否启用。</summary>
    public bool Enabled { get; set; } = true;

    /// <summary>规则版本号。</summary>
    public int Version { get; set; } = 1;
}

/// <summary>从表头识别出来、尚未保存的字段草案。</summary>
public sealed class HeaderFieldDraft
{
    /// <summary>字段名称。</summary>
    public string FieldName { get; set; } = string.Empty;

    /// <summary>零基列号。</summary>
    public int ColumnIndex { get; set; }

    /// <summary>核心字段类型。</summary>
    public CoreFieldType CoreType { get; set; }

    /// <summary>排序。</summary>
    public int SortOrder { get; set; }
}
