using System.Collections.ObjectModel;
using System.Linq;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using DingTalkChatExporter.App.Services;
using DingTalkChatExporter.Core.Abstractions;
using Serilog;

namespace DingTalkChatExporter.App.ViewModels;

/// <summary>
/// Shell view model: navigation, selected page and global status text.
/// All page state lives in the page view models; this type owns no page logic.
/// </summary>
public sealed partial class MainViewModel : ObservableObject
{
    private readonly SnapshotService _snapshots;
    private readonly SettingsService _settings;
    private readonly AppPaths _paths;
    private readonly ILogger _logger;
    private bool _restoring;

    [ObservableProperty]
    private NavigationItemViewModel? _selectedNavigation;

    [ObservableProperty]
    private PageViewModelBase? _selectedPage;

    [ObservableProperty]
    private string _statusMessage = "就绪";

    public MainViewModel(
        SnapshotService snapshots,
        SettingsService settings,
        WorkbenchService workbench,
        TableManager tables,
        ITableIndexService indexService,
        IDialogService dialogs,
        AppPaths paths,
        ILogger logger)
    {
        _snapshots = snapshots;
        _settings = settings;
        _paths = paths;
        _logger = logger.ForContext<MainViewModel>();

        Navigation =
        [
            new("M", new SnapshotPageViewModel(workbench, snapshots, settings, logger)),
            new("R", new RulesPageViewModel(workbench, logger)),
            new("T", new TablesPageViewModel(tables, indexService, workbench, dialogs, logger)),
            new("E", new ExportPageViewModel(snapshots, settings, workbench, dialogs, logger)),
            new("H", new HistoryPageViewModel(tables, settings, workbench, dialogs, logger)),
            new("S", new SettingsPageViewModel(settings, workbench, dialogs, paths, logger))
        ];
    }

    public ObservableCollection<NavigationItemViewModel> Navigation { get; }

    public string DataDirectory => _paths.BaseDirectory;

    public string VersionText { get; } =
        typeof(MainViewModel).Assembly.GetName().Version is { } version
            ? $"v{version.Major}.{version.Minor}.{version.Build}"
            : "v1.0.0";

    public void Initialize()
    {
        _restoring = true;
        try
        {
            var savedKey = _snapshots.Current.LastNavigationPage;
            SelectedNavigation = Navigation.FirstOrDefault(item => item.Key == savedKey) ?? Navigation[0];
        }
        finally
        {
            _restoring = false;
        }

        if (_settings.LastWarnings.Count > 0)
        {
            StatusMessage = _settings.LastWarnings[0];
        }
    }

    partial void OnSelectedNavigationChanged(NavigationItemViewModel? value)
    {
        SelectedPage = value?.Page;
        if (value is null || _restoring)
        {
            return;
        }

        try
        {
            _snapshots.SetNavigationPage(value.Key);
            (value.Page as IActivatablePage)?.OnActivated();
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "Failed to persist navigation page {PageKey}.", value.Key);
            StatusMessage = "导航状态保存失败，将在下次操作时重试。";
        }
    }

    [RelayCommand]
    private void Navigate(string? key)
    {
        var target = Navigation.FirstOrDefault(item =>
            string.Equals(item.Key, key, StringComparison.OrdinalIgnoreCase));
        if (target is not null)
        {
            SelectedNavigation = target;
        }
    }

    [RelayCommand]
    private void RefreshCurrent()
    {
        if (SelectedPage is IActivatablePage page)
        {
            page.OnActivated();
            StatusMessage = "已刷新当前页面。";
        }
    }

    public void ReportStatus(string message) => StatusMessage = message;
}
