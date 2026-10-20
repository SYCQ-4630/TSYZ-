namespace DingTalkChatExporter.DataSources.Dws;

public sealed class DwsOptions
{
    /// <summary>Empty means resolve "dws" from PATH.</summary>
    public string ExecutablePath { get; set; } = string.Empty;

    public string ResolvedExecutablePath =>
        string.IsNullOrWhiteSpace(ExecutablePath) ? "dws" : ExecutablePath.Trim();
}
