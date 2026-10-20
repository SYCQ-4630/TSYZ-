namespace DingTalkChatExporter.Core.Models;

public enum RouterStrategy
{
    /// <summary>dws first; fall back to FlaUI only when enabled.</summary>
    Auto,

    /// <summary>Never fall back; fail with an explicit reason.</summary>
    DwsOnly,

    /// <summary>Use FlaUI directly (experimental).</summary>
    FlaUiOnly
}
