using DingTalkChatExporter.Core.Models;

namespace DingTalkChatExporter.Core.Management;

public enum FlaUiCaptureMode
{
    VisibleOnly,
    Scroll
}

public sealed record FlaUiSettings
{
    public string ProcessName { get; init; } = "dingding";
    public FlaUiCaptureMode CaptureMode { get; init; } = FlaUiCaptureMode.VisibleOnly;
    public int MaxScrollScreens { get; init; } = 10;
    public int ScrollIntervalMs { get; init; } = 500;
    public bool HideOwnWindow { get; init; }
}

public sealed record DataSourceSettings
{
    public string DwsExecutablePath { get; init; } = string.Empty;
    public RouterStrategy RouterStrategy { get; init; } = RouterStrategy.Auto;
    public FlaUiSettings FlaUi { get; init; } = new();
}
