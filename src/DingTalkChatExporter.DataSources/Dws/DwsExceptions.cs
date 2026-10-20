namespace DingTalkChatExporter.DataSources.Dws;

public class DwsException : Exception
{
    public DwsException(string message) : base(message)
    {
    }

    public DwsException(string message, Exception? innerException) : base(message, innerException)
    {
    }
}

public sealed class DwsNotInstalledException : DwsException
{
    public DwsNotInstalledException(string executable, Exception? innerException = null)
        : base($"未找到 dws 可执行文件“{executable}”。请先安装 dws，或在数据源管理中指定 dws.exe 的完整路径。", innerException)
    {
        Executable = executable;
    }

    public string Executable { get; }
}

public sealed class DwsAuthException : DwsException
{
    public DwsAuthException(string message, string standardError, Exception? innerException = null)
        : base(message, innerException ?? new InvalidOperationException(message))
    {
        StandardError = standardError;
    }

    /// <summary>Original stderr is kept for diagnostics but must never be logged verbatim.</summary>
    public string StandardError { get; }
}

public sealed class DwsExecutionException : DwsException
{
    public DwsExecutionException(string message) : base(message)
    {
    }

    public DwsExecutionException(string message, Exception innerException) : base(message, innerException)
    {
    }

    public DwsExecutionException(string message, int exitCode, string standardError) : base(message)
    {
        ExitCode = exitCode;
        StandardError = standardError;
    }

    public int ExitCode { get; }
    public string StandardError { get; } = string.Empty;
}
