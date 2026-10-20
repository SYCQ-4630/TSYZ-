using System.ComponentModel;
using System.Diagnostics;
using Serilog;

namespace DingTalkChatExporter.DataSources.Dws;

public sealed record ProcessResult(
    int ExitCode,
    string StandardOutput,
    string StandardError,
    bool TimedOut);

public interface IProcessRunner
{
    Task<ProcessResult> RunAsync(
        string fileName,
        IReadOnlyList<string> arguments,
        TimeSpan timeout,
        CancellationToken cancellationToken);
}

/// <summary>
/// Starts dws directly (never through cmd.exe) and redirects both streams.
/// ArgumentList performs Windows quoting, so paths containing spaces are safe.
/// </summary>
public sealed class ProcessRunner : IProcessRunner
{
    private readonly ILogger _logger;

    public ProcessRunner(ILogger? logger = null)
    {
        _logger = (logger ?? Serilog.Log.Logger).ForContext<ProcessRunner>();
    }

    public async Task<ProcessResult> RunAsync(
        string fileName,
        IReadOnlyList<string> arguments,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = fileName,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            StandardOutputEncoding = System.Text.Encoding.UTF8,
            StandardErrorEncoding = System.Text.Encoding.UTF8
        };

        foreach (var argument in arguments)
        {
            startInfo.ArgumentList.Add(argument);
        }

        using var process = new Process { StartInfo = startInfo };
        try
        {
            if (!process.Start())
            {
                throw new DwsExecutionException($"无法启动 dws 进程：{fileName}");
            }
        }
        catch (Win32Exception ex)
        {
            throw new DwsNotInstalledException(fileName, ex);
        }

        var stdoutTask = process.StandardOutput.ReadToEndAsync();
        var stderrTask = process.StandardError.ReadToEndAsync();

        using var timeoutSource = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeoutSource.CancelAfter(timeout);

        var timedOut = false;
        try
        {
            await process.WaitForExitAsync(timeoutSource.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            KillProcess(process);
            throw;
        }
        catch (OperationCanceledException)
        {
            timedOut = true;
            KillProcess(process);
        }

        var stdout = await ReadSafelyAsync(stdoutTask).ConfigureAwait(false);
        var stderr = await ReadSafelyAsync(stderrTask).ConfigureAwait(false);

        if (timedOut)
        {
            _logger.Warning(
                "dws process timed out after {TimeoutSeconds:s} seconds. Arguments count={ArgumentCount}.",
                timeout,
                arguments.Count);
        }

        return new ProcessResult(timedOut ? -1 : process.ExitCode, stdout, stderr, timedOut);
    }

    private void KillProcess(Process process)
    {
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
            }
        }
        catch (Exception ex)
        {
            _logger.Debug(ex, "Failed to terminate timed-out dws process.");
        }
    }

    private static async Task<string> ReadSafelyAsync(Task<string> readTask)
    {
        try
        {
            return await readTask.WaitAsync(TimeSpan.FromSeconds(3)).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            return string.Empty;
        }
        catch (Exception)
        {
            return string.Empty;
        }
    }
}
