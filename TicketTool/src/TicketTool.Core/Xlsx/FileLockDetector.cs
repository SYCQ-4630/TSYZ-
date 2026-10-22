namespace TicketTool.Core.Xlsx;

/// <summary>通过独占打开探测目标文件是否被占用。</summary>
public static class FileLockDetector
{
    /// <summary>文件是否可被独占写入。</summary>
    public static bool IsWritable(string filePath)
    {
        if (!File.Exists(filePath))
        {
            return true;
        }

        try
        {
            using var stream = new FileStream(filePath, FileMode.Open, FileAccess.ReadWrite, FileShare.None);
            return true;
        }
        catch (IOException)
        {
            return false;
        }
        catch (UnauthorizedAccessException)
        {
            return false;
        }
    }
}
