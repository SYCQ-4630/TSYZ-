namespace DingTalkChatExporter.Core.Management;

public enum AppThemeChoice
{
    System,
    Light,
    Dark
}

public enum AppFontScale
{
    Small,
    Medium,
    Large
}

public sealed record RecentGroup(string Identifier, string DisplayName, DateTimeOffset LastUsedAt);

public sealed record UiSettings
{
    public AppThemeChoice Theme { get; init; } = AppThemeChoice.System;
    public AppFontScale FontSize { get; init; } = AppFontScale.Medium;
    public List<RecentGroup> RecentGroups { get; init; } = new();
}
