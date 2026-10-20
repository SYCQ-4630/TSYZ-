using System.IO;
using System.Linq;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using DingTalkChatExporter.App.Services;
using DingTalkChatExporter.Core.Management;
using DingTalkChatExporter.Core.Models;
using ModernWpf;
using Serilog;

namespace DingTalkChatExporter.App.ViewModels;

public sealed record RouterStrategyChoice(RouterStrategy Value, string Name);

public sealed record LogLevelChoice(LogVerbosity Value, string Name);

public sealed record ThemeChoice(AppThemeChoice Value, string Name);

public sealed record FontScaleChoice(AppFontScale Value, string Name)
{
    public double Size => Value switch
    {
        AppFontScale.Small => 12,
        AppFontScale.Large => 15,
        _ => 13
    };
}

public sealed record CaptureModeChoice(Core.Management.FlaUiCaptureMode Value, string Name);

/// <summary>
/// Settings are split by category and saved independently. High-risk switches ask
/// for confirmation when enabled; read-only mode cannot be disabled here.
/// </summary>
public sealed partial class SettingsPageViewModel : PageViewModelBase
{
    private readonly SettingsService _settings;
    private readonly WorkbenchService _workbench;
    private readonly IDialogService _dialogs;
    private readonly AppPaths _paths;
    private readonly ILogger _logger;

    [ObservableProperty]
    private string _dwsExecutablePath = string.Empty;

    [ObservableProperty]
    private RouterStrategyChoice? _selectedStrategy;

    [ObservableProperty]
    private string _flaUiProcessName = "dingding";

    [ObservableProperty]
    private CaptureModeChoice? _selectedCaptureMode;

    [ObservableProperty]
    private int _flaUiMaxScrollScreens = 10;

    [ObservableProperty]
    private int _flaUiScrollIntervalMs = 500;

    [ObservableProperty]
    private bool _flaUiHideOwnWindow;

    [ObservableProperty]
    private bool _allowFlaUiFallback;

    [ObservableProperty]
    private bool _hideAppDuringFlaUiCapture;

    [ObservableProperty]
    private bool _enableIncrementalRead;

    [ObservableProperty]
    private bool _backupBeforeExport;

    [ObservableProperty]
    private bool _highlightLowConfidenceInExport;

    [ObservableProperty]
    private bool _openFolderAfterExport;

    [ObservableProperty]
    private bool _highlightLowConfidenceInPreview;

    [ObservableProperty]
    private bool _liveRuleTesting;

    [ObservableProperty]
    private bool _enableAutoSnapshot;

    [ObservableProperty]
    private bool _snapshotPreviewResults;

    [ObservableProperty]
    private bool _playSoundOnComplete;

    [ObservableProperty]
    private bool _flashTaskbarOnComplete;

    [ObservableProperty]
    private LogLevelChoice? _selectedLogLevel;

    [ObservableProperty]
    private ThemeChoice? _selectedTheme;

    [ObservableProperty]
    private FontScaleChoice? _selectedFontScale;

    [ObservableProperty]
    private string _statusMessage = "设置按类别独立保存，修改后立即生效。";

    [ObservableProperty]
    private string _dataSourceCheckResult = "尚未检查";

    [ObservableProperty]
    private bool _isBusy;

    public SettingsPageViewModel(
        SettingsService settings,
        WorkbenchService workbench,
        IDialogService dialogs,
        AppPaths paths,
        ILogger logger)
        : base("settings", "设置", "数据源、功能开关、外观与日志。")
    {
        _settings = settings;
        _workbench = workbench;
        _dialogs = dialogs;
        _paths = paths;
        _logger = logger.ForContext<SettingsPageViewModel>();

        Strategies =
        [
            new RouterStrategyChoice(RouterStrategy.Auto, "自动：dws 优先，可降级 FlaUI"),
            new RouterStrategyChoice(RouterStrategy.DwsOnly, "仅 dws（不降级）"),
            new RouterStrategyChoice(RouterStrategy.FlaUiOnly, "仅 FlaUI（实验性）")
        ];
        LogLevels =
        [
            new LogLevelChoice(LogVerbosity.Debug, "Debug（详细）"),
            new LogLevelChoice(LogVerbosity.Information, "Information（默认）"),
            new LogLevelChoice(LogVerbosity.Warning, "Warning"),
            new LogLevelChoice(LogVerbosity.Error, "Error")
        ];
        Themes =
        [
            new ThemeChoice(AppThemeChoice.System, "跟随系统"),
            new ThemeChoice(AppThemeChoice.Light, "浅色"),
            new ThemeChoice(AppThemeChoice.Dark, "深色")
        ];
        FontScales =
        [
            new FontScaleChoice(AppFontScale.Small, "小"),
            new FontScaleChoice(AppFontScale.Medium, "中（默认）"),
            new FontScaleChoice(AppFontScale.Large, "大")
        ];
        CaptureModes =
        [
            new CaptureModeChoice(Core.Management.FlaUiCaptureMode.VisibleOnly, "仅当前可见区域（稳定）"),
            new CaptureModeChoice(Core.Management.FlaUiCaptureMode.Scroll, "滚动采集（实验性）")
        ];

        Reload();
    }

    public IReadOnlyList<RouterStrategyChoice> Strategies { get; }

    public IReadOnlyList<LogLevelChoice> LogLevels { get; }

    public IReadOnlyList<ThemeChoice> Themes { get; }

    public IReadOnlyList<FontScaleChoice> FontScales { get; }

    public IReadOnlyList<CaptureModeChoice> CaptureModes { get; }

    public string DataDirectory => _paths.BaseDirectory;

    public string LogsDirectory => _paths.LogsDirectory;

    public string VersionText { get; } =
        typeof(SettingsPageViewModel).Assembly.GetName().Version is { } version
            ? $"v{version.Major}.{version.Minor}.{version.Build}"
            : "v1.0.0";

    [RelayCommand]
    private void Reload()
    {
        var features = _settings.Features;
        AllowFlaUiFallback = features.AllowFlaUiFallback;
        HideAppDuringFlaUiCapture = features.HideAppDuringFlaUiCapture;
        EnableIncrementalRead = features.EnableIncrementalRead;
        BackupBeforeExport = features.BackupBeforeExport;
        HighlightLowConfidenceInExport = features.HighlightLowConfidenceInExport;
        OpenFolderAfterExport = features.OpenFolderAfterExport;
        HighlightLowConfidenceInPreview = features.HighlightLowConfidenceInPreview;
        LiveRuleTesting = features.LiveRuleTesting;
        EnableAutoSnapshot = features.EnableAutoSnapshot;
        SnapshotPreviewResults = features.SnapshotPreviewResults;
        PlaySoundOnComplete = features.PlaySoundOnComplete;
        FlashTaskbarOnComplete = features.FlashTaskbarOnComplete;
        SelectedLogLevel = LogLevels.FirstOrDefault(item => item.Value == features.MinimumLogLevel) ?? LogLevels[1];

        var data = _settings.DataSources;
        DwsExecutablePath = data.DwsExecutablePath;
        SelectedStrategy = Strategies.FirstOrDefault(item => item.Value == data.RouterStrategy) ?? Strategies[0];
        FlaUiProcessName = data.FlaUi.ProcessName;
        SelectedCaptureMode = CaptureModes.FirstOrDefault(item => item.Value == data.FlaUi.CaptureMode) ?? CaptureModes[0];
        FlaUiMaxScrollScreens = data.FlaUi.MaxScrollScreens;
        FlaUiScrollIntervalMs = data.FlaUi.ScrollIntervalMs;
        FlaUiHideOwnWindow = data.FlaUi.HideOwnWindow;

        var ui = _settings.Ui;
        SelectedTheme = Themes.FirstOrDefault(item => item.Value == ui.Theme) ?? Themes[0];
        SelectedFontScale = FontScales.FirstOrDefault(item => item.Value == ui.FontSize) ?? FontScales[1];

        ApplyTheme(SelectedTheme?.Value ?? AppThemeChoice.System);
        StatusMessage = "设置已加载。";
    }

    [RelayCommand]
    private void BrowseDws()
    {
        var path = _dialogs.PickOpenFile(
            "选择 dws 可执行文件",
            "可执行文件 (*.exe)|*.exe|所有文件 (*.*)|*.*");
        if (path is not null)
        {
            DwsExecutablePath = path;
        }
    }

    [RelayCommand]
    private async Task CheckDataSourcesAsync()
    {
        if (IsBusy)
        {
            return;
        }

        IsBusy = true;
        DataSourceCheckResult = "正在检查…";
        try
        {
            if (!TrySaveDataSources(silent: true))
            {
                DataSourceCheckResult = StatusMessage;
                return;
            }

            var status = await _workbench.GetStatusAsync(true, CancellationToken.None);
            DataSourceCheckResult =
                $"dws：{status.Dws.Summary}\n" +
                (status.Dws.Detail is null ? string.Empty : status.Dws.Detail + "\n") +
                $"FlaUI：{(status.FlaUiAvailable ? "可用（实验性）" : status.FlaUiReason ?? "不可用")}\n" +
                $"当前策略：{SelectedStrategy?.Name}";
            StatusMessage = "数据源检查完成。";
        }
        catch (Exception ex)
        {
            DataSourceCheckResult = "检查失败：" + ex.Message;
            StatusMessage = "数据源检查失败：" + ex.Message;
            _logger.Warning(ex, "Settings data source check failed.");
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private void SaveDataSources()
    {
        if (TrySaveDataSources(silent: false))
        {
            StatusMessage = "数据源设置已保存并立即生效。";
        }
    }

    private bool TrySaveDataSources(bool silent)
    {
        if (!string.IsNullOrWhiteSpace(DwsExecutablePath) && !File.Exists(DwsExecutablePath))
        {
            StatusMessage = "dws 可执行文件路径不存在，请重新选择或留空以使用 PATH。";
            return false;
        }

        if (string.IsNullOrWhiteSpace(FlaUiProcessName))
        {
            StatusMessage = "FlaUI 进程名不能为空。";
            return false;
        }

        var settings = new DataSourceSettings
        {
            DwsExecutablePath = DwsExecutablePath.Trim(),
            RouterStrategy = SelectedStrategy?.Value ?? RouterStrategy.Auto,
            FlaUi = new Core.Management.FlaUiSettings
            {
                ProcessName = FlaUiProcessName.Trim(),
                CaptureMode = SelectedCaptureMode?.Value ?? Core.Management.FlaUiCaptureMode.VisibleOnly,
                MaxScrollScreens = Math.Clamp(FlaUiMaxScrollScreens, 1, 100),
                ScrollIntervalMs = Math.Clamp(FlaUiScrollIntervalMs, 100, 5000),
                HideOwnWindow = FlaUiHideOwnWindow
            }
        };

        try
        {
            _settings.SaveDataSources(settings);
            _workbench.ApplySettings();
            if (!silent)
            {
                StatusMessage = "数据源设置已保存并立即生效。";
            }

            return true;
        }
        catch (Exception ex)
        {
            StatusMessage = "保存数据源设置失败：" + ex.Message;
            return false;
        }
    }

    [RelayCommand]
    private void SaveFeatures()
    {
        if (!ConfirmHighRiskTransitions())
        {
            return;
        }

        var updated = new FeatureSettings
        {
            AllowFlaUiFallback = AllowFlaUiFallback,
            HideAppDuringFlaUiCapture = HideAppDuringFlaUiCapture,
            EnableIncrementalRead = EnableIncrementalRead,
            BackupBeforeExport = BackupBeforeExport,
            HighlightLowConfidenceInExport = HighlightLowConfidenceInExport,
            OpenFolderAfterExport = OpenFolderAfterExport,
            HighlightLowConfidenceInPreview = HighlightLowConfidenceInPreview,
            LiveRuleTesting = LiveRuleTesting,
            EnableAutoSnapshot = EnableAutoSnapshot,
            SnapshotPreviewResults = SnapshotPreviewResults,
            MinimumLogLevel = SelectedLogLevel?.Value ?? LogVerbosity.Information,
            PlaySoundOnComplete = PlaySoundOnComplete,
            FlashTaskbarOnComplete = FlashTaskbarOnComplete,
            OnboardingCompleted = _settings.Features.OnboardingCompleted
        };

        try
        {
            _settings.SaveFeatures(updated);
            LoggingConfigurator.Configure(_paths, updated.MinimumLogLevel);
            _workbench.ApplySettings();
            StatusMessage = "功能开关已保存；日志级别与数据源行为立即生效。";
        }
        catch (Exception ex)
        {
            StatusMessage = "保存功能开关失败：" + ex.Message;
            _logger.Warning(ex, "Failed to save feature settings.");
        }
    }

    [RelayCommand]
    private void SaveUi()
    {
        var updated = _settings.Ui with
        {
            Theme = SelectedTheme?.Value ?? AppThemeChoice.System,
            FontSize = SelectedFontScale?.Value ?? AppFontScale.Medium
        };

        try
        {
            _settings.SaveUi(updated);
            ApplyTheme(updated.Theme);
            ApplyFontScale(updated.FontSize);
            StatusMessage = "外观设置已保存。";
        }
        catch (Exception ex)
        {
            StatusMessage = "保存外观设置失败：" + ex.Message;
        }
    }

    [RelayCommand]
    private void ResetFeatures()
    {
        var defaults = new FeatureSettings();
        AllowFlaUiFallback = defaults.AllowFlaUiFallback;
        HideAppDuringFlaUiCapture = defaults.HideAppDuringFlaUiCapture;
        EnableIncrementalRead = defaults.EnableIncrementalRead;
        BackupBeforeExport = defaults.BackupBeforeExport;
        HighlightLowConfidenceInExport = defaults.HighlightLowConfidenceInExport;
        OpenFolderAfterExport = defaults.OpenFolderAfterExport;
        HighlightLowConfidenceInPreview = defaults.HighlightLowConfidenceInPreview;
        LiveRuleTesting = defaults.LiveRuleTesting;
        EnableAutoSnapshot = defaults.EnableAutoSnapshot;
        SnapshotPreviewResults = defaults.SnapshotPreviewResults;
        PlaySoundOnComplete = defaults.PlaySoundOnComplete;
        FlashTaskbarOnComplete = defaults.FlashTaskbarOnComplete;
        SelectedLogLevel = LogLevels.First(item => item.Value == defaults.MinimumLogLevel);
        StatusMessage = "已恢复功能开关默认值，点击“保存功能开关”后生效。";
    }

    [RelayCommand]
    private void ResetDataSources()
    {
        var defaults = new DataSourceSettings();
        DwsExecutablePath = defaults.DwsExecutablePath;
        SelectedStrategy = Strategies.First(item => item.Value == defaults.RouterStrategy);
        FlaUiProcessName = defaults.FlaUi.ProcessName;
        SelectedCaptureMode = CaptureModes.First(item => item.Value == defaults.FlaUi.CaptureMode);
        FlaUiMaxScrollScreens = defaults.FlaUi.MaxScrollScreens;
        FlaUiScrollIntervalMs = defaults.FlaUi.ScrollIntervalMs;
        FlaUiHideOwnWindow = defaults.FlaUi.HideOwnWindow;
        StatusMessage = "已恢复数据源默认值，点击“保存数据源”后生效。";
    }

    [RelayCommand]
    private void ResetUi()
    {
        SelectedTheme = Themes[0];
        SelectedFontScale = FontScales[1];
        StatusMessage = "已恢复外观默认值，点击“保存外观”后生效。";
    }

    [RelayCommand]
    private void OpenDataDirectory() => OpenFolder(_paths.BaseDirectory);

    [RelayCommand]
    private void OpenLogsDirectory() => OpenFolder(_paths.LogsDirectory);

    private bool ConfirmHighRiskTransitions()
    {
        if (AllowFlaUiFallback && !_settings.Features.AllowFlaUiFallback)
        {
            if (!_dialogs.Confirm(
                    "启用实验性 FlaUI 降级",
                    "FlaUI 依赖桌面 UI 自动化，可能受钉钉版本更新影响，且滚动采集属于实验性功能。\n\n" +
                    "该功能只读，但可能产生不稳定结果。确认启用？"))
            {
                AllowFlaUiFallback = false;
                return false;
            }
        }

        if (HideAppDuringFlaUiCapture && !_settings.Features.HideAppDuringFlaUiCapture)
        {
            if (!_dialogs.Confirm(
                    "抓取时隐藏应用窗口",
                    "隐藏窗口用于减少 FlaUI 抓取时的遮挡，但抓取期间界面不可见。确认启用？"))
            {
                HideAppDuringFlaUiCapture = false;
                return false;
            }
        }

        return true;
    }

    private static void ApplyTheme(AppThemeChoice choice)
    {
        try
        {
            ThemeManager.Current.ApplicationTheme = choice switch
            {
                AppThemeChoice.Light => ApplicationTheme.Light,
                AppThemeChoice.Dark => ApplicationTheme.Dark,
                _ => null
            };
        }
        catch (Exception)
        {
        }
    }

    private static void ApplyFontScale(AppFontScale scale)
    {
        if (System.Windows.Application.Current?.MainWindow is { } window)
        {
            window.FontSize = scale switch
            {
                AppFontScale.Small => 12,
                AppFontScale.Large => 15,
                _ => 13
            };
        }
    }

    private void OpenFolder(string path)
    {
        try
        {
            Directory.CreateDirectory(path);
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = "explorer.exe",
                Arguments = $"\"{path}\"",
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            StatusMessage = "无法打开文件夹：" + ex.Message;
        }
    }
}
