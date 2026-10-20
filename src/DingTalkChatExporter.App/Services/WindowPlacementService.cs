using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Threading;
using DingTalkChatExporter.Core.Management;
using Microsoft.Win32;
using Serilog;

namespace DingTalkChatExporter.App.Services;

/// <summary>
/// Persists and restores physical window geometry using Win32 window placement APIs.
/// WPF Left/Top/Width/Height are deliberately not persisted: they are DIP based and
/// drift when monitors use different DPI scaling factors.
/// </summary>
public sealed class WindowPlacementService : IDisposable
{
    private const int WmDpiChanged = 0x02E0;

    private const uint SwpNoZOrder = 0x0004;
    private const uint SwpNoActivate = 0x0010;
    private const uint SwpNoOwnerZOrder = 0x0200;

    private const uint MonitorDefaultToNull = 0x00000000;
    private const uint MonitorDefaultToPrimary = 0x00000001;

    private readonly Window _window;
    private readonly ILogger _logger;
    private readonly Func<WindowPlacementState, Task>? _persistPlacement;
    private readonly DispatcherTimer _debounceTimer;

    private HwndSource? _hwndSource;
    private IntPtr _hwnd;
    private WindowPlacementState? _lastNormalPlacement;
    private bool _subscribed;
    private bool _restoring;
    private bool _disposed;

    public WindowPlacementService(
        Window window,
        ILogger logger,
        Func<WindowPlacementState, Task>? persistPlacement = null)
    {
        _window = window;
        _logger = logger.ForContext<WindowPlacementService>();
        _persistPlacement = persistPlacement;
        _debounceTimer = new DispatcherTimer(DispatcherPriority.Background)
        {
            Interval = TimeSpan.FromSeconds(1)
        };
        _debounceTimer.Tick += OnDebounceElapsed;
    }

    /// <summary>
    /// Attaches restore/save behavior. <paramref name="savedPlacement"/> is applied once
    /// the window has a native handle and is loaded.
    /// </summary>
    public void Attach(WindowPlacementState? savedPlacement)
    {
        if (_subscribed)
        {
            return;
        }

        _subscribed = true;
        _window.SourceInitialized += OnSourceInitialized;
        _window.Loaded += OnLoaded;
        _window.Closing += OnClosing;
        _window.LocationChanged += OnWindowGeometryChanged;
        _window.SizeChanged += OnWindowGeometryChanged;
        _window.StateChanged += OnWindowStateChanged;

        if (new WindowInteropHelper(_window).Handle != IntPtr.Zero)
        {
            EnsureHwndHook();
        }

        _pendingRestore = savedPlacement;
        if (_window.IsLoaded)
        {
            TryRestore();
        }

        SystemEvents.DisplaySettingsChanged += OnDisplaySettingsChanged;
    }

    private WindowPlacementState? _pendingRestore;

    public WindowPlacementState? LastSavedPlacement { get; private set; }

    private void OnSourceInitialized(object? sender, EventArgs e)
    {
        EnsureHwndHook();
    }

    private void EnsureHwndHook()
    {
        if (_hwnd != IntPtr.Zero)
        {
            return;
        }

        _hwnd = new WindowInteropHelper(_window).Handle;
        if (_hwnd == IntPtr.Zero)
        {
            return;
        }

        _hwndSource = HwndSource.FromHwnd(_hwnd);
        _hwndSource?.AddHook(WndProc);
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        EnsureHwndHook();
        TryRestore();
    }

    private void TryRestore()
    {
        if (_restoring || _pendingRestore is null || _hwnd == IntPtr.Zero)
        {
            return;
        }

        var saved = _pendingRestore;
        _pendingRestore = null;
        RestorePlacement(saved);
    }

    private void RestorePlacement(WindowPlacementState saved)
    {
        _restoring = true;
        _lastNormalPlacement = saved;
        try
        {
            var target = new Rect32
            {
                Left = saved.NormalLeft,
                Top = saved.NormalTop,
                Right = saved.NormalRight,
                Bottom = saved.NormalBottom
            };

            var hasSize = target.Width >= 100 && target.Height >= 100;
            if (!hasSize || MonitorFromRect(ref target, MonitorDefaultToNull) == IntPtr.Zero)
            {
                target = CenterOnPrimary(
                    hasSize ? target.Width : LogicalToPhysical(_window.Width, GetPrimaryDpi()),
                    hasSize ? target.Height : LogicalToPhysical(_window.Height, GetPrimaryDpi()));
                _logger.Information(
                    "Saved window rectangle is off-screen or invalid; centering on primary monitor at physical {Left},{Top} {Width}x{Height}.",
                    target.Left,
                    target.Top,
                    target.Width,
                    target.Height);
            }

            SetWindowPos(
                _hwnd,
                IntPtr.Zero,
                target.Left,
                target.Top,
                target.Width,
                target.Height,
                SwpNoZOrder | SwpNoActivate | SwpNoOwnerZOrder);

            var dpi = GetDpiForWindowSafe(_hwnd);
            var scale = dpi / 96.0;
            _window.Width = target.Width / scale;
            _window.Height = target.Height / scale;
            _window.WindowState = saved.IsMaximized ? WindowState.Maximized : WindowState.Normal;

            LastSavedPlacement = saved;
            _logger.Information(
                "Restored window placement at physical {Left},{Top} {Width}x{Height}, DPI {Dpi}, maximized={Maximized}.",
                target.Left,
                target.Top,
                target.Width,
                target.Height,
                dpi,
                saved.IsMaximized);
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "Failed to restore window placement; continuing with default placement.");
        }
        finally
        {
            _window.Dispatcher.BeginInvoke(
                DispatcherPriority.ApplicationIdle,
                new Action(() => _restoring = false));
        }
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg != WmDpiChanged)
        {
            return IntPtr.Zero;
        }

        try
        {
            var dpi = (int)(wParam.ToInt64() & 0xFFFF);
            var suggested = Marshal.PtrToStructure<Rect32>(lParam);
            _restoring = true;

            SetWindowPos(
                hwnd,
                IntPtr.Zero,
                suggested.Left,
                suggested.Top,
                suggested.Width,
                suggested.Height,
                SwpNoZOrder | SwpNoActivate | SwpNoOwnerZOrder);

            var scale = dpi / 96.0;
            _window.Width = suggested.Width / scale;
            _window.Height = suggested.Height / scale;

            _logger.Debug(
                "Handled WM_DPICHANGED to DPI {Dpi}; physical bounds {Left},{Top} {Width}x{Height}.",
                dpi,
                suggested.Left,
                suggested.Top,
                suggested.Width,
                suggested.Height);

            _window.Dispatcher.BeginInvoke(
                DispatcherPriority.ApplicationIdle,
                new Action(() =>
                {
                    _restoring = false;
                    ScheduleSave();
                }));
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "Failed to process WM_DPICHANGED.");
            _restoring = false;
        }

        handled = true;
        return IntPtr.Zero;
    }

    private void OnDisplaySettingsChanged(object? sender, EventArgs e)
    {
        _window.Dispatcher.BeginInvoke(new Action(() =>
        {
            try
            {
                if (_hwnd == IntPtr.Zero)
                {
                    return;
                }

                var current = GetCurrentPhysicalRect();
                if (MonitorFromRect(ref current, MonitorDefaultToNull) != IntPtr.Zero)
                {
                    return;
                }

                var centered = CenterOnPrimary(current.Width, current.Height);
                _restoring = true;
                SetWindowPos(
                    _hwnd,
                    IntPtr.Zero,
                    centered.Left,
                    centered.Top,
                    centered.Width,
                    centered.Height,
                    SwpNoZOrder | SwpNoActivate | SwpNoOwnerZOrder);

                var dpi = GetDpiForWindowSafe(_hwnd);
                var scale = dpi / 96.0;
                _window.Width = centered.Width / scale;
                _window.Height = centered.Height / scale;
                _logger.Information("Display configuration changed and the window was off-screen; moved to the primary work area.");
            }
            catch (Exception ex)
            {
                _logger.Warning(ex, "Failed to correct window placement after a display change.");
            }
            finally
            {
                _window.Dispatcher.BeginInvoke(
                    DispatcherPriority.ApplicationIdle,
                    new Action(() =>
                    {
                        _restoring = false;
                        ScheduleSave();
                    }));
            }
        }));
    }

    private void OnWindowGeometryChanged(object? sender, EventArgs e)
    {
        if (!_restoring)
        {
            ScheduleSave();
        }
    }

    private void OnWindowStateChanged(object? sender, EventArgs e)
    {
        if (_window.WindowState != WindowState.Minimized)
        {
            ScheduleSave();
        }
    }

    private void ScheduleSave()
    {
        if (_disposed)
        {
            return;
        }

        _debounceTimer.Stop();
        _debounceTimer.Start();
    }

    private async void OnDebounceElapsed(object? sender, EventArgs e)
    {
        _debounceTimer.Stop();
        try
        {
            await SaveNowAsync().ConfigureAwait(true);
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "Debounced window placement save failed.");
        }
    }

    public async Task SaveNowAsync()
    {
        if (_hwnd == IntPtr.Zero || _disposed)
        {
            return;
        }

        try
        {
            var placement = CapturePlacement();
            LastSavedPlacement = placement;
            if (_persistPlacement is not null)
            {
                await _persistPlacement(placement).ConfigureAwait(true);
            }
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "Failed to save window placement.");
        }
    }

    public WindowPlacementState CapturePlacement()
    {
        var current = GetCurrentPlacement();
        if (current.ShowCommand == 2 && _lastNormalPlacement is not null)
        {
            return _lastNormalPlacement;
        }

        if (current.ShowCommand == 1)
        {
            _lastNormalPlacement = current;
        }

        return current;
    }

    private WindowPlacementState GetCurrentPlacement()
    {
        var placement = new WindowPlacement32 { Length = Marshal.SizeOf<WindowPlacement32>() };
        if (!GetWindowPlacement(_hwnd, ref placement))
        {
            throw new InvalidOperationException("GetWindowPlacement failed.");
        }

        return new WindowPlacementState
        {
            NormalLeft = placement.RcNormalPosition.Left,
            NormalTop = placement.RcNormalPosition.Top,
            NormalRight = placement.RcNormalPosition.Right,
            NormalBottom = placement.RcNormalPosition.Bottom,
            MinPositionX = placement.PtMinPosition.X,
            MinPositionY = placement.PtMinPosition.Y,
            MaxPositionX = placement.PtMaxPosition.X,
            MaxPositionY = placement.PtMaxPosition.Y,
            ShowCommand = placement.ShowCmd,
            Flags = placement.Flags
        };
    }

    private Rect32 GetCurrentPhysicalRect()
    {
        var placement = new WindowPlacement32 { Length = Marshal.SizeOf<WindowPlacement32>() };
        if (GetWindowPlacement(_hwnd, ref placement))
        {
            return placement.RcNormalPosition;
        }

        GetWindowRect(_hwnd, out var rect);
        return rect;
    }

    private Rect32 CenterOnPrimary(int width, int height)
    {
        var info = new MonitorInfo32 { Size = Marshal.SizeOf<MonitorInfo32>() };
        var monitor = MonitorFromPoint(new Point32(), MonitorDefaultToPrimary);
        if (!GetMonitorInfo(monitor, ref info))
        {
            return new Rect32 { Left = 0, Top = 0, Right = width, Bottom = height };
        }

        var work = info.WorkArea;
        var left = work.Left + Math.Max(0, (work.Width - width) / 2);
        var top = work.Top + Math.Max(0, (work.Height - height) / 2);
        return new Rect32 { Left = left, Top = top, Right = left + width, Bottom = top + height };
    }

    private static int LogicalToPhysical(double dips, uint dpi) => (int)Math.Round(dips * dpi / 96.0);

    private uint GetPrimaryDpi()
    {
        var monitor = MonitorFromPoint(new Point32(), MonitorDefaultToPrimary);
        return GetDpiForMonitorSafe(monitor);
    }

    private static uint GetDpiForWindowSafe(IntPtr hwnd)
    {
        try
        {
            var dpi = GetDpiForWindow(hwnd);
            return dpi == 0 ? 96u : dpi;
        }
        catch (EntryPointNotFoundException)
        {
            var monitor = MonitorFromWindow(hwnd, MonitorDefaultToPrimary);
            return GetDpiForMonitorSafe(monitor);
        }
    }

    private static uint GetDpiForMonitorSafe(IntPtr monitor)
    {
        try
        {
            if (monitor != IntPtr.Zero &&
                GetDpiForMonitor(monitor, 0, out var dpiX, out _) == 0 &&
                dpiX > 0)
            {
                return dpiX;
            }
        }
        catch (DllNotFoundException)
        {
        }
        catch (EntryPointNotFoundException)
        {
        }

        return 96;
    }

    private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        _debounceTimer.Stop();
        try
        {
            if (_hwnd != IntPtr.Zero)
            {
                var placement = CapturePlacement();
                LastSavedPlacement = placement;
                _persistPlacement?.Invoke(placement).GetAwaiter().GetResult();
            }
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "Synchronous window placement save on close failed.");
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _debounceTimer.Stop();
        _debounceTimer.Tick -= OnDebounceElapsed;
        SystemEvents.DisplaySettingsChanged -= OnDisplaySettingsChanged;
        _window.SourceInitialized -= OnSourceInitialized;
        _window.Loaded -= OnLoaded;
        _window.Closing -= OnClosing;
        _window.LocationChanged -= OnWindowGeometryChanged;
        _window.SizeChanged -= OnWindowGeometryChanged;
        _window.StateChanged -= OnWindowStateChanged;
        _hwndSource?.RemoveHook(WndProc);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Point32
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Rect32
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;

        public int Width => Right - Left;
        public int Height => Bottom - Top;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct WindowPlacement32
    {
        public int Length;
        public uint Flags;
        public int ShowCmd;
        public Point32 PtMinPosition;
        public Point32 PtMaxPosition;
        public Rect32 RcNormalPosition;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MonitorInfo32
    {
        public int Size;
        public Rect32 Monitor;
        public Rect32 WorkArea;
        public uint Flags;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool GetWindowPlacement(IntPtr hWnd, ref WindowPlacement32 placement);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool GetWindowRect(IntPtr hWnd, out Rect32 rect);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowPos(
        IntPtr hWnd,
        IntPtr hWndInsertAfter,
        int x,
        int y,
        int cx,
        int cy,
        uint flags);

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromRect(ref Rect32 rect, uint flags);

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromPoint(Point32 point, uint flags);

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint flags);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern bool GetMonitorInfo(IntPtr monitor, ref MonitorInfo32 info);

    [DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(IntPtr hwnd);

    [DllImport("Shcore.dll")]
    private static extern int GetDpiForMonitor(IntPtr monitor, int dpiType, out uint dpiX, out uint dpiY);
}
