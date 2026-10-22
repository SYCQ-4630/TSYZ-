using Serilog;
using Serilog.Formatting.Compact;
using TicketTool.Core.Data;

namespace TicketTool.Core.Diagnostics;

/// <summary>Serilog 结构化日志初始化。</summary>
public static class LogSetup
{
    /// <summary>配置异步文件日志，按天滚动，保留 30 天。</summary>
    public static ILogger Initialize(AppPaths paths)
    {
        Directory.CreateDirectory(paths.LogsDirectory);
        Log.Logger = new LoggerConfiguration()
            .MinimumLevel.Information()
            .Enrich.WithProperty("Application", "TicketTool")
            .WriteTo.Async(sink => sink.File(
                new CompactJsonFormatter(),
                Path.Combine(paths.LogsDirectory, "ticket-.log"),
                rollingInterval: RollingInterval.Day,
                retainedFileCountLimit: 30), bufferSize: 2000)
            .CreateLogger();
        return Log.Logger;
    }

    /// <summary>关闭日志。</summary>
    public static void Shutdown() => Log.CloseAndFlush();
}
