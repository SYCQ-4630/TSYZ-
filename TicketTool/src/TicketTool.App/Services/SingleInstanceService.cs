using System.Diagnostics;
using System.Runtime.InteropServices;

namespace TicketTool.App.Services;

/// <summary>单实例互斥体与已有窗口激活。</summary>
public sealed class SingleInstanceService : IDisposable
{
    private const string MutexName = "TicketTool.SingleInstance.9C31F7A1";
    private Mutex? _mutex;

    /// <summary>尝试成为唯一实例。</summary>
    public bool TryAcquire()
    {
        _mutex = new Mutex(true, MutexName, out var createdNew);
        return createdNew;
    }

    /// <summary>激活已有窗口。</summary>
    public static void ActivateExisting()
    {
        var handle = NativeMethods.FindWindow(null, "通信工单助手");
        if (handle == IntPtr.Zero)
        {
            var process = Process.GetCurrentProcess();
            handle = process.MainWindowHandle;
        }

        if (handle != IntPtr.Zero)
        {
            NativeMethods.ShowWindow(handle, NativeMethods.SwRestore);
            NativeMethods.SetForegroundWindow(handle);
        }
    }

    /// <inheritdoc />
    public void Dispose()
    {
        try
        {
            _mutex?.ReleaseMutex();
        }
        catch (ApplicationException)
        {
        }
        _mutex?.Dispose();
    }

    private static class NativeMethods
    {
        public const int SwRestore = 9;

        [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        public static extern IntPtr FindWindow(string? lpClassName, string? lpWindowName);

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool SetForegroundWindow(IntPtr hWnd);
    }
}
