using System.IO;
using DingTalkChatExporter.Core.Management;
using Serilog;
using Serilog.Events;

namespace DingTalkChatExporter.App.Services;

public static class LoggingConfigurator
{
    public static void Configure(AppPaths paths, LogVerbosity verbosity)
    {
        var previous = Log.Logger;
        Log.Logger = new LoggerConfiguration()
            .MinimumLevel.Is(ToEventLevel(verbosity))
            .WriteTo.File(
                Path.Combine(paths.LogsDirectory, "app-.log"),
                rollingInterval: RollingInterval.Day,
                retainedFileCountLimit: 14,
                shared: true)
            .CreateLogger();

        _ = previous;
    }

    public static LogEventLevel ToEventLevel(LogVerbosity verbosity) => verbosity switch
    {
        LogVerbosity.Debug => LogEventLevel.Debug,
        LogVerbosity.Warning => LogEventLevel.Warning,
        LogVerbosity.Error => LogEventLevel.Error,
        _ => LogEventLevel.Information
    };
}
