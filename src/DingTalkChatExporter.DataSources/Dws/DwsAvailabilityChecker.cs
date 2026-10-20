using System.Text.Json;
using Serilog;

namespace DingTalkChatExporter.DataSources.Dws;

public sealed record DwsAvailability(
    bool Installed,
    bool Authenticated,
    bool TokenValid,
    string? Version,
    string? TokenExpiresAt,
    string Summary,
    string? Detail,
    DateTimeOffset CheckedAt)
{
    public bool IsAvailable => Installed && Authenticated && TokenValid;
}

/// <summary>
/// Runs "dws --version" (10 s) and "dws auth status --format json" (15 s).
/// Results including failures are cached for 30 seconds to avoid spawning a
/// process for every UI refresh. This class never triggers "dws auth login".
/// </summary>
public sealed class DwsAvailabilityChecker
{
    private static readonly TimeSpan VersionTimeout = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan AuthTimeout = TimeSpan.FromSeconds(15);
    private static readonly TimeSpan CacheDuration = TimeSpan.FromSeconds(30);

    private readonly DwsOptions _options;
    private readonly IProcessRunner _runner;
    private readonly ILogger _logger;
    private readonly SemaphoreSlim _gate = new(1, 1);

    private DwsAvailability? _cached;
    private DateTimeOffset _cachedAt;

    public DwsAvailabilityChecker(DwsOptions options, IProcessRunner? runner = null, ILogger? logger = null)
    {
        _options = options;
        _runner = runner ?? new ProcessRunner(logger);
        _logger = (logger ?? Serilog.Log.Logger).ForContext<DwsAvailabilityChecker>();
    }

    public TimeSpan CacheWindow { get; init; } = CacheDuration;

    public DwsAvailability? CachedResult => _cached;

    public void Invalidate()
    {
        _cached = null;
        _cachedAt = default;
    }

    public async Task<DwsAvailability> CheckAsync(bool forceRefresh, CancellationToken cancellationToken)
    {
        if (!forceRefresh && _cached is not null && DateTimeOffset.UtcNow - _cachedAt < CacheWindow)
        {
            return _cached;
        }

        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (!forceRefresh && _cached is not null && DateTimeOffset.UtcNow - _cachedAt < CacheWindow)
            {
                return _cached;
            }

            var result = await CheckCoreAsync(cancellationToken).ConfigureAwait(false);
            _cached = result;
            _cachedAt = DateTimeOffset.UtcNow;

            _logger.Information(
                "dws availability checked: installed={Installed}, authenticated={Authenticated}, tokenValid={TokenValid}, summary={Summary}",
                result.Installed,
                result.Authenticated,
                result.TokenValid,
                result.Summary);

            return result;
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task<DwsAvailability> CheckCoreAsync(CancellationToken cancellationToken)
    {
        var executable = _options.ResolvedExecutablePath;
        string? version = null;

        try
        {
            var versionResult = await _runner
                .RunAsync(executable, new[] { "--version" }, VersionTimeout, cancellationToken)
                .ConfigureAwait(false);

            if (versionResult.TimedOut)
            {
                return Failure("dws 版本检查超时（10 秒）。", "请确认 dws 能正常执行，或检查安全软件是否拦截了子进程。");
            }

            if (versionResult.ExitCode != 0)
            {
                return Failure(
                    "dws 已找到但无法正常执行。",
                    $"退出码 {versionResult.ExitCode}，stderr 摘要：{Summarize(versionResult.StandardError)}");
            }

            version = FirstNonEmptyLine(versionResult.StandardOutput) ?? FirstNonEmptyLine(versionResult.StandardError);
        }
        catch (DwsNotInstalledException)
        {
            return Failure("dws 未安装或不在 PATH 中。", "请安装 dws，或在数据源管理中指定 dws.exe 的完整路径。");
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "Failed to check dws version.");
            return Failure("dws 版本检查失败。", ex.Message);
        }

        try
        {
            var authResult = await _runner
                .RunAsync(
                    executable,
                    new[] { "auth", "status", "--format", "json" },
                    AuthTimeout,
                    cancellationToken)
                .ConfigureAwait(false);

            if (authResult.TimedOut)
            {
                return new DwsAvailability(
                    true, false, false, version, null,
                    "dws 已安装，但认证状态检查超时。", "认证检查超过 15 秒，请稍后重试。", DateTimeOffset.UtcNow);
            }

            if (authResult.ExitCode != 0)
            {
                if (DwsErrorClassifier.IsAuthFailure(authResult.StandardError, authResult.ExitCode))
                {
                    return new DwsAvailability(
                        true, false, false, version, null,
                        "dws 已安装但未认证。", "请运行 dws auth login 完成登录。", DateTimeOffset.UtcNow);
                }

                return new DwsAvailability(
                    true, false, false, version, null,
                    "dws 认证状态检查失败。",
                    $"退出码 {authResult.ExitCode}，stderr 摘要：{Summarize(authResult.StandardError)}",
                    DateTimeOffset.UtcNow);
            }

            var auth = DwsAuthStatusParser.Parse(authResult.StandardOutput);
            if (!auth.Authenticated || !auth.TokenValid)
            {
                return new DwsAvailability(
                    true,
                    auth.Authenticated,
                    auth.TokenValid,
                    version,
                    auth.ExpiresAt,
                    auth.Authenticated ? "dws 已认证，但 Token 已失效。" : "dws 已安装但未认证。",
                    auth.Authenticated ? "请重新运行 dws auth login。" : "请运行 dws auth login 完成登录。",
                    DateTimeOffset.UtcNow);
            }

            return new DwsAvailability(
                true,
                true,
                true,
                version,
                auth.ExpiresAt,
                "dws 已认证，可用。",
                null,
                DateTimeOffset.UtcNow);
        }
        catch (DwsNotInstalledException)
        {
            return Failure("dws 未安装或不在 PATH 中。", "请安装 dws，或在数据源管理中指定 dws.exe 的完整路径。");
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "Failed to check dws auth status.");
            return new DwsAvailability(
                true, false, false, version, null,
                "dws 认证状态检查失败。", ex.Message, DateTimeOffset.UtcNow);
        }
    }

    private static DwsAvailability Failure(string summary, string? detail) =>
        new(false, false, false, null, null, summary, detail, DateTimeOffset.UtcNow);

    private static string? FirstNonEmptyLine(string value) =>
        value.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries)
            .Select(line => line.Trim())
            .FirstOrDefault(line => line.Length > 0);

    private static string Summarize(string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return "(empty)";
        }

        var trimmed = text.Trim();
        return trimmed.Length <= 160 ? trimmed : trimmed[..160] + "...";
    }

    public sealed record DwsAuthStatus(bool Authenticated, bool TokenValid, string? ExpiresAt);

    public static class DwsAuthStatusParser
    {
        public static DwsAuthStatus Parse(string json)
        {
            if (string.IsNullOrWhiteSpace(json))
            {
                return new DwsAuthStatus(false, false, null);
            }

            try
            {
                using var document = JsonDocument.Parse(json);
                var authenticated = FindBoolean(document.RootElement, "authenticated", "isAuthenticated", "logged_in");
                var tokenValid = FindBoolean(document.RootElement, "token_valid", "tokenValid", "valid");
                var expiresAt = FindString(document.RootElement, "expires_at", "expiresAt", "token_expires_at", "expire_time", "expiration");
                return new DwsAuthStatus(authenticated, tokenValid, expiresAt);
            }
            catch (JsonException)
            {
                return new DwsAuthStatus(false, false, null);
            }
        }

        private static bool FindBoolean(JsonElement element, params string[] names)
        {
            var found = FindValue(element, names, out var value);
            if (!found)
            {
                return false;
            }

            return value.ValueKind switch
            {
                JsonValueKind.True => true,
                JsonValueKind.False => false,
                JsonValueKind.String => bool.TryParse(value.GetString(), out var parsed) && parsed,
                JsonValueKind.Number => value.TryGetInt32(out var number) && number != 0,
                _ => false
            };
        }

        private static string? FindString(JsonElement element, params string[] names)
        {
            var found = FindValue(element, names, out var value);
            if (!found)
            {
                return null;
            }

            return value.ValueKind switch
            {
                JsonValueKind.String => value.GetString(),
                JsonValueKind.Number => value.GetRawText(),
                _ => null
            };
        }

        private static bool FindValue(JsonElement element, string[] names, out JsonElement value)
        {
            if (element.ValueKind == JsonValueKind.Object)
            {
                foreach (var property in element.EnumerateObject())
                {
                    if (names.Any(name => string.Equals(name, property.Name, StringComparison.OrdinalIgnoreCase)))
                    {
                        value = property.Value;
                        return true;
                    }
                }

                foreach (var property in element.EnumerateObject())
                {
                    if (property.Value.ValueKind is JsonValueKind.Object or JsonValueKind.Array &&
                        FindValue(property.Value, names, out value))
                    {
                        return true;
                    }
                }
            }
            else if (element.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in element.EnumerateArray())
                {
                    if (FindValue(item, names, out value))
                    {
                        return true;
                    }
                }
            }

            value = default;
            return false;
        }
    }
}

public static class DwsErrorClassifier
{
    private static readonly string[] AuthMarkers =
    {
        "not authenticated",
        "unauthorized",
        "unauthenticated",
        "token expired",
        "token_expired",
        "token invalid",
        "invalid token",
        "please login",
        "please log in",
        "auth login",
        "401",
        "未认证",
        "未登录",
        "登录已失效",
        "token 已失效"
    };

    public static bool IsAuthFailure(string? standardError, int exitCode)
    {
        if (exitCode == 0 || string.IsNullOrWhiteSpace(standardError))
        {
            return false;
        }

        return AuthMarkers.Any(marker =>
            standardError.Contains(marker, StringComparison.OrdinalIgnoreCase));
    }
}
