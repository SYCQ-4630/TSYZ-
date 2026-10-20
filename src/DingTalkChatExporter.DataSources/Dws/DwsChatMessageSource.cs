using System.Globalization;
using System.Text.Json;
using DingTalkChatExporter.Core.Abstractions;
using DingTalkChatExporter.Core.Models;
using Serilog;

namespace DingTalkChatExporter.DataSources.Dws;

/// <summary>
/// Primary message source backed by the official DingTalk dws CLI.
/// Read-only: this class only invokes read commands and never writes to DingTalk.
/// </summary>
public sealed class DwsChatMessageSource : IChatMessageSource
{
    public const string Name = "dws";
    private static readonly TimeSpan FetchTimeout = TimeSpan.FromSeconds(120);

    private readonly DwsOptions _options;
    private readonly DwsAvailabilityChecker _availabilityChecker;
    private readonly IProcessRunner _runner;
    private readonly ILogger _logger;

    public DwsChatMessageSource(
        DwsOptions options,
        DwsAvailabilityChecker availabilityChecker,
        IProcessRunner? runner = null,
        ILogger? logger = null)
    {
        _options = options;
        _availabilityChecker = availabilityChecker;
        _runner = runner ?? new ProcessRunner(logger);
        _logger = (logger ?? Serilog.Log.Logger).ForContext<DwsChatMessageSource>();
    }

    public string SourceName => Name;

    public bool IsAvailable => _availabilityChecker.CachedResult?.IsAvailable == true;

    public async Task<IReadOnlyList<RawMessage>> FetchAsync(ChatFetchRequest request, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(request.GroupIdentifier))
        {
            throw new DwsExecutionException("群标识为空，无法拉取 dws 消息。");
        }

        var availability = _availabilityChecker.CachedResult
                           ?? await _availabilityChecker.CheckAsync(false, ct).ConfigureAwait(false);

        if (!availability.Installed)
        {
            throw new DwsNotInstalledException(_options.ResolvedExecutablePath);
        }

        if (!availability.Authenticated || !availability.TokenValid)
        {
            throw new DwsAuthException(
                "dws 未认证，请先在界面中完成 dws 登录",
                availability.Detail ?? availability.Summary);
        }

        var outputDirectory = Path.Combine(Path.GetTempPath(), "DingTalkChatExporter");
        Directory.CreateDirectory(outputDirectory);
        var outputPath = Path.Combine(outputDirectory, $"dws-{Guid.NewGuid():N}.json");

        try
        {
            var arguments = new List<string>
            {
                "chat",
                "+chat-messages",
                "--group",
                request.GroupIdentifier,
                "--page-all",
                "--output",
                outputPath,
                "--format",
                "json"
            };

            if (request.Start is not null)
            {
                arguments.Add("--start");
                arguments.Add(FormatIso8601(request.Start.Value));
            }

            if (request.End is not null)
            {
                arguments.Add("--end");
                arguments.Add(FormatIso8601(request.End.Value));
            }

            var result = await _runner
                .RunAsync(_options.ResolvedExecutablePath, arguments, FetchTimeout, ct)
                .ConfigureAwait(false);

            if (result.TimedOut)
            {
                throw new DwsExecutionException("dws 拉取消息超时（120 秒），请缩小时间范围或稍后重试。");
            }

            if (result.ExitCode != 0)
            {
                if (DwsErrorClassifier.IsAuthFailure(result.StandardError, result.ExitCode))
                {
                    _logger.Warning(
                        "dws fetch reported an authentication failure. stderrChars={StderrChars}.",
                        result.StandardError?.Length ?? 0);
                    throw new DwsAuthException(
                        "dws 未认证，请先在界面中完成 dws 登录",
                        result.StandardError ?? string.Empty);
                }

                _logger.Warning(
                    "dws fetch failed with exit code {ExitCode}. stderr summary={StderrSummary}.",
                    result.ExitCode,
                    Summarize(result.StandardError));
                throw new DwsExecutionException(
                    $"dws 拉取消息失败（退出码 {result.ExitCode}）。{Summarize(result.StandardError)}",
                    result.ExitCode,
                    result.StandardError ?? string.Empty);
            }

            if (!File.Exists(outputPath))
            {
                throw new DwsExecutionException("dws 未生成输出文件，消息可能为空或命令参数不被当前版本支持。");
            }

            var json = await File.ReadAllTextAsync(outputPath, ct).ConfigureAwait(false);
            IReadOnlyList<RawMessage> messages;
            try
            {
                messages = DwsJsonParser.ParseMessages(json);
            }
            catch (JsonException ex)
            {
                _logger.Error(ex, "dws output JSON could not be parsed. chars={CharCount}.", json.Length);
                throw new DwsExecutionException("dws 返回的 JSON 无法解析，请确认 dws 版本与输出格式。", ex);
            }

            if (request.MaxResults > 0 && messages.Count > request.MaxResults)
            {
                messages = messages.Take(request.MaxResults).ToList();
            }

            _logger.Information(
                "dws fetched {MessageCount} messages for group hash={GroupHash}.",
                messages.Count,
                HashGroup(request.GroupIdentifier));

            return messages;
        }
        finally
        {
            try
            {
                if (File.Exists(outputPath))
                {
                    File.Delete(outputPath);
                }
            }
            catch (IOException ex)
            {
                _logger.Debug(ex, "Failed to remove temporary dws output file.");
            }
        }
    }

    private static string FormatIso8601(DateTimeOffset value) =>
        value.ToString("yyyy-MM-dd'T'HH:mm:sszzz", CultureInfo.InvariantCulture);

    private static string Summarize(string? stderr)
    {
        if (string.IsNullOrWhiteSpace(stderr))
        {
            return string.Empty;
        }

        var text = stderr.Trim();
        return text.Length <= 200 ? text : text[..200] + "...";
    }

    private static string HashGroup(string groupIdentifier)
    {
        var bytes = System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(groupIdentifier));
        return Convert.ToHexString(bytes)[..8];
    }
}
