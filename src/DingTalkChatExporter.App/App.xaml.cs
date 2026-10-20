using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Threading;
using DingTalkChatExporter.App.Services;
using DingTalkChatExporter.App.ViewModels;
using DingTalkChatExporter.Core.Management;
using DingTalkChatExporter.Core.Services;
using Serilog;

namespace DingTalkChatExporter.App;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        var dataDirectory = ReadDataDirectory(e.Args);
        var paths = new AppPaths(dataDirectory);
        paths.EnsureDirectories();

        LoggingConfigurator.Configure(paths, LogVerbosity.Debug);

        DispatcherUnhandledException += OnDispatcherUnhandledException;

        var settings = new SettingsService(paths, Log.Logger);
        settings.LoadAll();
        LoggingConfigurator.Configure(paths, settings.Features.MinimumLogLevel);
        Log.Information("Application starting. Data directory: {DataDirectory}", paths.BaseDirectory);

        var snapshots = new SnapshotService(
            paths,
            Log.Logger,
            () => settings.Features.EnableAutoSnapshot,
            () => settings.Features.SnapshotPreviewResults);
        var snapshot = snapshots.Load();
        if (snapshots.LastWarning is not null)
        {
            Log.Warning("Snapshot fallback: {Warning}", snapshots.LastWarning);
        }

        var indexService = new TableIndexService(paths, Log.Logger);
        var tables = new TableManager(paths, indexService, Log.Logger);
        tables.Load();
        if (tables.LastLoadWarning is not null)
        {
            Log.Warning("Table list fallback: {Warning}", tables.LastLoadWarning);
        }

        var ruleLibraries = new RuleLibraryManager(paths.RuleLibrariesDirectory);
        var workbench = new WorkbenchService(settings, ruleLibraries, Log.Logger);

        var dialogs = new DialogService();
        var viewModel = new MainViewModel(
            snapshots,
            settings,
            workbench,
            tables,
            indexService,
            dialogs,
            paths,
            Log.Logger);
        viewModel.Initialize();

        var window = new MainWindow(viewModel, snapshot, snapshots, Log.Logger);
        MainWindow = window;
        window.Show();

        if (!settings.Features.OnboardingCompleted)
        {
            var onboarding = new OnboardingViewModel(workbench, paths, Log.Logger);
            var wizard = new OnboardingWindow(onboarding) { Owner = window };
            wizard.ShowDialog();
            if (onboarding.Completed)
            {
                settings.SaveFeatures(settings.Features with { OnboardingCompleted = true });
                Log.Information("Onboarding completed.");
            }
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        Log.Information("Application exiting with code {ExitCode}.", e.ApplicationExitCode);
        Log.CloseAndFlush();
        base.OnExit(e);
    }

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        Log.Error(e.Exception, "Unhandled UI exception.");
        MessageBox.Show(
            "应用遇到未处理的错误，详细信息已写入日志。\n\n" + e.Exception.Message,
            "钉钉聊天记录导出工具",
            MessageBoxButton.OK,
            MessageBoxImage.Error);
        e.Handled = true;
    }

    private static string? ReadDataDirectory(string[] args)
    {
        var index = Array.IndexOf(args, "--data-dir");
        if (index >= 0 && index + 1 < args.Length && !string.IsNullOrWhiteSpace(args[index + 1]))
        {
            return Path.GetFullPath(args[index + 1]);
        }

        var inline = args.FirstOrDefault(
            argument => argument.StartsWith("--data-dir=", StringComparison.OrdinalIgnoreCase));
        return inline is null ? null : Path.GetFullPath(inline["--data-dir=".Length..]);
    }

}
