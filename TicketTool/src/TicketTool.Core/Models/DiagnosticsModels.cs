namespace TicketTool.Core.Models;

/// <summary>诊断面板中的一条日志。</summary>
public sealed class LogEntry
{
    /// <summary>时间。</summary>
    public DateTimeOffset Timestamp { get; set; }

    /// <summary>级别。</summary>
    public string Level { get; set; } = string.Empty;

    /// <summary>模块或事件名。</summary>
    public string Module { get; set; } = string.Empty;

    /// <summary>消息。</summary>
    public string Message { get; set; } = string.Empty;

    /// <summary>异常文本。</summary>
    public string? Exception { get; set; }

    /// <summary>原始结构化数据。</summary>
    public string? RawJson { get; set; }
}

/// <summary>日志过滤条件。</summary>
public sealed class LogFilter
{
    /// <summary>最低级别，空表示全部。</summary>
    public string? Level { get; set; }

    /// <summary>模块关键字。</summary>
    public string? Module { get; set; }

    /// <summary>关键词。</summary>
    public string? Keyword { get; set; }

    /// <summary>起始时间。</summary>
    public DateTimeOffset? From { get; set; }

    /// <summary>结束时间。</summary>
    public DateTimeOffset? To { get; set; }

    /// <summary>最大返回条数。</summary>
    public int Limit { get; set; } = 2000;
}

/// <summary>数据完整性检查结果。</summary>
public sealed class IntegrityCheckResult
{
    /// <summary>是否通过。</summary>
    public bool Ok { get; set; }

    /// <summary>quick_check 原始输出。</summary>
    public string Message { get; set; } = string.Empty;

    /// <summary>检查时间。</summary>
    public DateTimeOffset CheckedAt { get; set; } = DateTimeOffset.Now;
}

/// <summary>诊断包导出结果。</summary>
public sealed class DiagnosticBundleResult
{
    /// <summary>压缩包路径。</summary>
    public string FilePath { get; set; } = string.Empty;

    /// <summary>文件数。</summary>
    public int FileCount { get; set; }
}
