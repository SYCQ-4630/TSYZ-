namespace DingTalkChatExporter.Core.Management;

public enum LogVerbosity
{
    Debug,
    Information,
    Warning,
    Error
}

/// <summary>
/// Feature switches. Safe defaults: experimental/high-risk features are off,
/// low-risk basics are on. No switch can disable read-only mode.
/// </summary>
public sealed record FeatureSettings
{
    public bool AllowFlaUiFallback { get; init; }
    public bool HideAppDuringFlaUiCapture { get; init; }
    public bool EnableIncrementalRead { get; init; } = true;

    public bool BackupBeforeExport { get; init; } = true;
    public bool HighlightLowConfidenceInExport { get; init; } = true;
    public bool OpenFolderAfterExport { get; init; }

    public bool HighlightLowConfidenceInPreview { get; init; } = true;
    public bool LiveRuleTesting { get; init; } = true;

    public bool EnableAutoSnapshot { get; init; } = true;
    public bool SnapshotPreviewResults { get; init; } = true;
    public LogVerbosity MinimumLogLevel { get; init; } = LogVerbosity.Information;

    public bool PlaySoundOnComplete { get; init; }
    public bool FlashTaskbarOnComplete { get; init; }

    public bool OnboardingCompleted { get; init; }
}
