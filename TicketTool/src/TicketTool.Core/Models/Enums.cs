namespace TicketTool.Core.Models;

/// <summary>核心可检索字段类型。</summary>
public enum CoreFieldType
{
    None = 0,
    TicketNo = 1,
    Phone = 2,
    Address = 3
}

/// <summary>规则类型。</summary>
public enum RuleType
{
    Preset = 0,
    Anchor = 1,
    Regex = 2,
    Segment = 3
}

/// <summary>XLSX 同步状态。</summary>
public enum SyncStatus
{
    Pending = 0,
    Syncing = 1,
    Synced = 2,
    Blocked = 3,
    Failed = 4
}

/// <summary>搜索域。</summary>
public enum SearchDomain
{
    Auto = 0,
    TicketNo = 1,
    Phone = 2,
    Address = 3
}

/// <summary>UI 顶部同步状态汇总。</summary>
public enum SyncBadgeState
{
    Synced = 0,
    Syncing = 1,
    WaitingForFile = 2,
    Failed = 3
}

/// <summary>枚举与数据库文本值之间的转换。</summary>
public static class EnumText
{
    /// <summary>核心字段类型转数据库文本。</summary>
    public static string ToDb(CoreFieldType value) => value switch
    {
        CoreFieldType.TicketNo => "ticket_no",
        CoreFieldType.Phone => "phone",
        CoreFieldType.Address => "address",
        _ => string.Empty
    };

    /// <summary>数据库文本转核心字段类型。</summary>
    public static CoreFieldType CoreFromDb(string? value) => value switch
    {
        "ticket_no" => CoreFieldType.TicketNo,
        "phone" => CoreFieldType.Phone,
        "address" => CoreFieldType.Address,
        _ => CoreFieldType.None
    };

    /// <summary>规则类型转数据库文本。</summary>
    public static string ToDb(RuleType value) => value switch
    {
        RuleType.Preset => "preset",
        RuleType.Anchor => "anchor",
        RuleType.Regex => "regex",
        _ => "segment"
    };

    /// <summary>数据库文本转规则类型。</summary>
    public static RuleType RuleFromDb(string? value) => value switch
    {
        "preset" => RuleType.Preset,
        "regex" => RuleType.Regex,
        "segment" => RuleType.Segment,
        _ => RuleType.Anchor
    };

    /// <summary>同步状态转数据库文本。</summary>
    public static string ToDb(SyncStatus value) => value switch
    {
        SyncStatus.Pending => "pending",
        SyncStatus.Syncing => "syncing",
        SyncStatus.Synced => "synced",
        SyncStatus.Blocked => "blocked",
        _ => "failed"
    };

    /// <summary>数据库文本转同步状态。</summary>
    public static SyncStatus SyncFromDb(string? value) => value switch
    {
        "pending" => SyncStatus.Pending,
        "syncing" => SyncStatus.Syncing,
        "synced" => SyncStatus.Synced,
        "blocked" => SyncStatus.Blocked,
        _ => SyncStatus.Failed
    };
}
