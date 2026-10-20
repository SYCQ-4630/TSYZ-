using System.Text.Json;
using Serilog;

namespace DingTalkChatExporter.DataSources.Dws;

/// <summary>
/// Optional group discovery helper built on "dws chat +chat-search".
/// Failure is non-fatal: the workbench always allows manual group identifiers.
/// </summary>
public sealed class DwsGroupDirectory
{
    private readonly DwsOptions _options;
    private readonly IProcessRunner _runner;
    private readonly ILogger _logger;

    public DwsGroupDirectory(DwsOptions options, IProcessRunner? runner = null, ILogger? logger = null)
    {
        _options = options;
        _runner = runner ?? new ProcessRunner(logger);
        _logger = (logger ?? Serilog.Log.Logger).ForContext<DwsGroupDirectory>();
    }

    public string? LastFailureReason { get; private set; }

    public async Task<IReadOnlyList<DwsChatGroup>> SearchGroupsAsync(
        string query,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(query))
        {
            return Array.Empty<DwsChatGroup>();
        }

        var arguments = new[]
        {
            "chat",
            "+chat-search",
            "--query",
            query.Trim(),
            "--format",
            "json"
        };

        try
        {
            var result = await _runner
                .RunAsync(_options.ResolvedExecutablePath, arguments, TimeSpan.FromSeconds(30), cancellationToken)
                .ConfigureAwait(false);

            if (result.TimedOut)
            {
                LastFailureReason = "群搜索超时（30 秒）。";
                return Array.Empty<DwsChatGroup>();
            }

            if (result.ExitCode != 0)
            {
                LastFailureReason = $"群搜索失败（退出码 {result.ExitCode}）。可直接手动输入群标识。";
                return Array.Empty<DwsChatGroup>();
            }

            LastFailureReason = null;
            return DwsJsonParser.ParseGroups(result.StandardOutput);
        }
        catch (Exception ex) when (ex is DwsNotInstalledException or DwsExecutionException or JsonException)
        {
            LastFailureReason = ex.Message;
            _logger.Warning(ex, "dws group search failed.");
            return Array.Empty<DwsChatGroup>();
        }
    }
}
