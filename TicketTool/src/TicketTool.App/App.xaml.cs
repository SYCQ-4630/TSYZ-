using System.Windows;
using System.Windows.Threading;
using Microsoft.Extensions.DependencyInjection;
using Serilog;
using TicketTool.App.Services;
using TicketTool.App.ViewModels;
using TicketTool.App.Views;
using TicketTool.Core.Data;
using TicketTool.Core.Diagnostics;
using TicketTool.Core.Models;
using TicketTool.Core.Parsing;
using TicketTool.Core.Search;
using TicketTool.Core.Snapshot;
using TicketTool.Core.Sync;
using TicketTool.Core.Templates;
using TicketTool.Core.Xlsx;

namespace TicketTool.App;

/// <summary>应用入口：单实例、依赖注入、启动恢复与全局异常处理。</summary>
public partial class App : Application
{
    private ServiceProvider? _provider;
    private SingleInstanceService? _singleInstance;
    private ILogger? _logger;
    private MainViewModel? _mainViewModel;

    /// <inheritdoc />
    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        _singleInstance = new SingleInstanceService();
        if (!_singleInstance.TryAcquire())
        {
            SingleInstanceService.ActivateExisting();
            Shutdown();
            return;
        }

        var paths = new AppPaths();
        var logger = LogSetup.Initialize(paths);
        _logger = logger;
        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += OnDomainUnhandledException;
        TaskScheduler.UnobservedTaskException += OnUnobservedTaskException;

        try
        {
            var services = new ServiceCollection();
            ConfigureServices(services, paths, logger);
            _provider = services.BuildServiceProvider();

            var initializer = _provider.GetRequiredService<DatabaseInitializer>();
            await initializer.InitializeAsync().ConfigureAwait(true);

            var backup = _provider.GetRequiredService<BackupService>();
            await backup.EnsureDailyBackupAsync().ConfigureAwait(true);

            var session = _provider.GetRequiredService<AppSession>();
            var snapshots = _provider.GetRequiredService<SnapshotService>();
            var state = await snapshots.InspectStartupAsync().ConfigureAwait(true);
            if (state.Kind == SnapshotStartupKind.Dirty && state.Payload is not null)
            {
                session.PendingSnapshot = state.Payload;
                session.StartupMessage = state.Summary;
            }
            else if (state.Kind == SnapshotStartupKind.Corrupt)
            {
                session.StartupMessage = state.Error;
            }

            var worker = _provider.GetRequiredService<TicketSyncWorker>();
            await worker.StartAsync().ConfigureAwait(true);

            var window = _provider.GetRequiredService<MainWindow>();
            MainWindow = window;
            _mainViewModel = _provider.GetRequiredService<MainViewModel>();
            window.Show();
            await _mainViewModel.InitializeAsync().ConfigureAwait(true);

            if (!string.IsNullOrWhiteSpace(session.StartupMessage) && session.PendingSnapshot is null)
            {
                MessageBox.Show(session.StartupMessage, "数据检查", MessageBoxButton.OK, MessageBoxImage.Information);
            }

            logger.Information("ApplicationStarted {Version}", typeof(App).Assembly.GetName().Version?.ToString());
        }
        catch (Exception ex)
        {
            _logger?.Fatal(ex, "ApplicationStartupFailed");
            MessageBox.Show(
                "程序启动失败，无法打开数据文件。\n\n" + ex.Message + "\n\n请重启电脑后重试；若仍然失败，请联系维护人员。",
                "启动失败",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
            Shutdown(1);
        }
    }

    /// <inheritdoc />
    protected override void OnExit(ExitEventArgs e)
    {
        try
        {
            _mainViewModel?.SaveFinalSnapshotAsync().GetAwaiter().GetResult();
            _provider?.GetService<TicketSyncWorker>()?.DisposeAsync().AsTask().GetAwaiter().GetResult();
            _provider?.GetService<Database>()?.DisposeAsync().AsTask().GetAwaiter().GetResult();
        }
        catch (Exception ex)
        {
            _logger?.Warning(ex, "ApplicationShutdownCleanupFailed");
        }
        finally
        {
            LogSetup.Shutdown();
            _singleInstance?.Dispose();
            _provider?.Dispose();
            base.OnExit(e);
        }
    }

    private static void ConfigureServices(IServiceCollection services, AppPaths paths, ILogger logger)
    {
        services.AddSingleton(paths);
        services.AddSingleton(logger);
        services.AddSingleton<Database>();
        services.AddSingleton<DatabaseInitializer>();
        services.AddSingleton<TemplateRepository>();
        services.AddSingleton<TicketRepository>();
        services.AddSingleton<SyncQueueRepository>();
        services.AddSingleton<SnapshotRepository>();
        services.AddSingleton<ExpressionIndexManager>();
        services.AddSingleton<TemplateService>();
        services.AddSingleton<TicketParser>();
        services.AddSingleton<XlsxRowAppender>();
        services.AddSingleton<CsvService>();
        services.AddSingleton<SearchService>();
        services.AddSingleton<BackupService>();
        services.AddSingleton<DiagnosticsDataService>();
        services.AddSingleton<SnapshotService>();
        services.AddSingleton<LogReader>();
        services.AddSingleton<DiagnosticBundleService>();
        services.AddSingleton<TicketSyncWorker>();
        services.AddSingleton<AppSession>();
        services.AddSingleton<IDialogService, DialogService>();
        services.AddSingleton<IWindowService, WindowService>();
        services.AddSingleton<DiagnosticsViewModel>();
        services.AddSingleton<MainViewModel>();
        services.AddSingleton<MainWindow>();
        services.AddTransient<NewTemplateViewModel>();
        services.AddTransient<RuleEditorViewModel>();
        services.AddTransient<TraceViewModel>();
    }

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        _logger?.Error(e.Exception, "DispatcherUnhandledException");
        MessageBox.Show(
            "当前操作出现异常，程序已保留现有数据并继续运行。\n\n" + e.Exception.Message,
            "操作异常",
            MessageBoxButton.OK,
            MessageBoxImage.Warning);
        e.Handled = true;
    }

    private void OnDomainUnhandledException(object sender, UnhandledExceptionEventArgs e)
    {
        _logger?.Fatal(e.ExceptionObject as Exception, "DomainUnhandledException");
        Log.CloseAndFlush();
    }

    private void OnUnobservedTaskException(object? sender, UnobservedTaskExceptionEventArgs e)
    {
        _logger?.Error(e.Exception, "UnobservedTaskException");
        e.SetObserved();
    }
}
