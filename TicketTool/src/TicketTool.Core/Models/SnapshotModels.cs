namespace TicketTool.Core.Models;

/// <summary>应用快照内容。只保存未确认的编辑状态。</summary>
public sealed class SnapshotPayload
{
    /// <summary>快照结构版本。</summary>
    public int Version { get; set; } = 1;

    /// <summary>当前编辑区原文。</summary>
    public string RawText { get; set; } = string.Empty;

    /// <summary>当前选中模板。</summary>
    public long? TemplateId { get; set; }

    /// <summary>当前选中模板名称。</summary>
    public string? TemplateName { get; set; }

    /// <summary>规则编辑草稿 JSON。</summary>
    public string? RuleDraftJson { get; set; }

    /// <summary>预览修正后的字段值。</summary>
    public Dictionary<string, string?> PreviewValues { get; set; } = new();

    /// <summary>当前阶段说明。</summary>
    public string Stage { get; set; } = "编辑中";

    /// <summary>保存时间。</summary>
    public DateTimeOffset SavedAt { get; set; } = DateTimeOffset.Now;
}

/// <summary>最新快照的启动恢复状态。</summary>
public enum SnapshotStartupKind
{
    /// <summary>没有快照。</summary>
    None = 0,

    /// <summary>正常退出后的快照，静默清理。</summary>
    Clean = 1,

    /// <summary>异常退出后的快照，需要询问用户。</summary>
    Dirty = 2,

    /// <summary>快照损坏。</summary>
    Corrupt = 3
}

/// <summary>启动恢复判定结果。</summary>
public sealed class SnapshotStartupState
{
    /// <summary>状态。</summary>
    public SnapshotStartupKind Kind { get; set; }

    /// <summary>快照主键。</summary>
    public long SnapshotId { get; set; }

    /// <summary>保存时间。</summary>
    public DateTimeOffset CreatedAt { get; set; }

    /// <summary>摘要文字。</summary>
    public string Summary { get; set; } = string.Empty;

    /// <summary>可恢复内容。</summary>
    public SnapshotPayload? Payload { get; set; }

    /// <summary>错误说明。</summary>
    public string? Error { get; set; }
}
