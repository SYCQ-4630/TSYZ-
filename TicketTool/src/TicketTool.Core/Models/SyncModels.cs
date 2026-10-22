namespace TicketTool.Core.Models;

/// <summary>同步队列中的一条记录。</summary>
public sealed class SyncQueueItem
{
    /// <summary>队列主键。</summary>
    public long Id { get; set; }

    /// <summary>工单主键。</summary>
    public long TicketId { get; set; }

    /// <summary>状态。</summary>
    public SyncStatus Status { get; set; }

    /// <summary>重试次数。</summary>
    public int Retry { get; set; }

    /// <summary>最近错误。</summary>
    public string? LastError { get; set; }

    /// <summary>更新时间。</summary>
    public DateTimeOffset UpdatedAt { get; set; }
}

/// <summary>同步队列统计。</summary>
public sealed class SyncStatistics
{
    /// <summary>待同步。</summary>
    public int Pending { get; set; }

    /// <summary>同步中。</summary>
    public int Syncing { get; set; }

    /// <summary>已同步。</summary>
    public int Synced { get; set; }

    /// <summary>因文件占用等待。</summary>
    public int Blocked { get; set; }

    /// <summary>失败。</summary>
    public int Failed { get; set; }

    /// <summary>汇总成徽标状态。</summary>
    public SyncBadgeState Badge => Failed > 0 ? SyncBadgeState.Failed
        : Blocked > 0 ? SyncBadgeState.WaitingForFile
        : Pending > 0 || Syncing > 0 ? SyncBadgeState.Syncing
        : SyncBadgeState.Synced;
}

/// <summary>一次 XLSX 追加的结果。</summary>
public sealed class XlsxAppendResult
{
    /// <summary>写入行号。</summary>
    public int RowNumber { get; set; }

    /// <summary>写入列数与值。</summary>
    public Dictionary<int, string?> Cells { get; set; } = new();

    /// <summary>耗时。</summary>
    public TimeSpan Elapsed { get; set; }
}

/// <summary>XLSX 写入预览。</summary>
public sealed class XlsxWritePreview
{
    /// <summary>目标文件。</summary>
    public string FilePath { get; set; } = string.Empty;

    /// <summary>目标行号。</summary>
    public int RowNumber { get; set; }

    /// <summary>列号到值的映射，零基列号。</summary>
    public List<(int ColumnIndex, string FieldName, string? Value)> Cells { get; set; } = new();
}
