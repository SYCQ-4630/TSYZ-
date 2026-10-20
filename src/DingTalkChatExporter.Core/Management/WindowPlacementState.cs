using System.Text.Json.Serialization;

namespace DingTalkChatExporter.Core.Management;

/// <summary>
/// Physical-pixel window placement captured through Win32 GetWindowPlacement.
/// WPF logical Left/Top/Width/Height are intentionally not persisted because they
/// are unreliable when monitors use different DPI scaling factors.
/// </summary>
public sealed record WindowPlacementState
{
    public int NormalLeft { get; init; }
    public int NormalTop { get; init; }
    public int NormalRight { get; init; }
    public int NormalBottom { get; init; }
    public int MinPositionX { get; init; } = -1;
    public int MinPositionY { get; init; } = -1;
    public int MaxPositionX { get; init; } = -1;
    public int MaxPositionY { get; init; } = -1;

    /// <summary>Win32 SW_SHOWNORMAL = 1, SW_SHOWMINIMIZED = 2, SW_SHOWMAXIMIZED = 3.</summary>
    public int ShowCommand { get; init; } = 1;

    public uint Flags { get; init; }

    [JsonIgnore]
    public bool IsMaximized => ShowCommand == 3;

    [JsonIgnore]
    public int NormalWidth => Math.Max(0, NormalRight - NormalLeft);

    [JsonIgnore]
    public int NormalHeight => Math.Max(0, NormalBottom - NormalTop);
}
