namespace TicketTool.Core.Xlsx;

/// <summary>目标 XLSX 不存在或已被改名。</summary>
public sealed class XlsxFileMissingException : Exception
{
    /// <summary>创建异常。</summary>
    public XlsxFileMissingException(string path)
        : base($"目标表格不存在：{path}。请在模板管理中重新绑定文件。")
    {
        FilePath = path;
    }

    /// <summary>目标路径。</summary>
    public string FilePath { get; }
}

/// <summary>目标 XLSX 正被其他程序占用。</summary>
public sealed class XlsxFileLockedException : Exception
{
    /// <summary>创建异常。</summary>
    public XlsxFileLockedException(string path, Exception? inner = null)
        : base("目标表格正被其他程序占用。关闭表格后会自动继续同步。", inner)
    {
        FilePath = path;
    }

    /// <summary>目标路径。</summary>
    public string FilePath { get; }
}

/// <summary>XLSX 结构不符合预期。</summary>
public sealed class XlsxStructureException : Exception
{
    /// <summary>创建异常。</summary>
    public XlsxStructureException(string message) : base(message)
    {
    }
}
