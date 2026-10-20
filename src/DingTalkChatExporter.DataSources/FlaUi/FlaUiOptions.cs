namespace DingTalkChatExporter.DataSources.FlaUi;

public enum FlaUiCaptureMode
{
    /// <summary>Fast, stable default: only the currently visible message area.</summary>
    VisibleOnly,

    /// <summary>Experimental: scroll the message list and accumulate new items.</summary>
    Scroll
}

/// <summary>
/// FlaUI is the experimental fallback and is only used when dws is unavailable.
/// </summary>
public sealed class FlaUiOptions
{
    public string ProcessName { get; set; } = "dingding";
    public FlaUiCaptureMode CaptureMode { get; set; } = FlaUiCaptureMode.VisibleOnly;
    public int MaxScrollScreens { get; set; } = 10;
    public int ScrollIntervalMs { get; set; } = 500;
    public bool HideOwnWindow { get; set; }
}
