using TicketTool.Core.Models;

namespace TicketTool.App.Services;

/// <summary>启动阶段在窗口之间传递的会话状态。</summary>
public sealed class AppSession
{
    /// <summary>待恢复的快照内容。</summary>
    public SnapshotPayload? PendingSnapshot { get; set; }

    /// <summary>启动恢复提示。</summary>
    public string? StartupMessage { get; set; }
}
