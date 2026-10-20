using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using DingTalkChatExporter.App.Services;
using Serilog;

namespace DingTalkChatExporter.App.ViewModels;

/// <summary>
/// First-run wizard. It explains read-only behavior and checks data sources,
/// but never blocks the user from finishing when dws is not yet configured.
/// </summary>
public sealed partial class OnboardingViewModel : ObservableObject
{
    private readonly WorkbenchService _workbench;
    private readonly AppPaths _paths;
    private readonly ILogger _logger;

    [ObservableProperty]
    private int _currentStep;

    [ObservableProperty]
    private string _statusMessage = "先了解一下工具如何读取数据。";

    [ObservableProperty]
    private string _checkResult = "尚未检查数据源";

    [ObservableProperty]
    private bool _isBusy;

    public OnboardingViewModel(WorkbenchService workbench, AppPaths paths, ILogger logger)
    {
        _workbench = workbench;
        _paths = paths;
        _logger = logger.ForContext<OnboardingViewModel>();
    }

    public event EventHandler<bool>? RequestClose;

    public string DataDirectory => _paths.BaseDirectory;

    public bool IsFirstStep => CurrentStep == 0;

    public bool IsSecondStep => CurrentStep == 1;

    public bool IsThirdStep => CurrentStep == 2;

    public string StepLabel => $"第 {CurrentStep + 1} / 3 步";

    public bool Completed { get; private set; }

    partial void OnCurrentStepChanged(int value)
    {
        OnPropertyChanged(nameof(IsFirstStep));
        OnPropertyChanged(nameof(IsSecondStep));
        OnPropertyChanged(nameof(IsThirdStep));
        OnPropertyChanged(nameof(StepLabel));
        BackCommand.NotifyCanExecuteChanged();
        NextCommand.NotifyCanExecuteChanged();
    }

    private bool CanGoBack() => CurrentStep > 0 && !IsBusy;

    private bool CanGoNext() => CurrentStep < 2 && !IsBusy;

    [RelayCommand(CanExecute = nameof(CanGoBack))]
    private void Back()
    {
        if (CurrentStep > 0)
        {
            CurrentStep--;
        }
    }

    [RelayCommand(CanExecute = nameof(CanGoNext))]
    private void Next()
    {
        if (CurrentStep < 2)
        {
            CurrentStep++;
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
        CheckResult = "正在检查 dws 与 FlaUI…";
        try
        {
            var status = await _workbench.GetStatusAsync(true, CancellationToken.None);
            CheckResult =
                $"dws：{status.Dws.Summary}\n" +
                (status.Dws.Detail is null ? string.Empty : status.Dws.Detail + "\n") +
                $"FlaUI：{(status.FlaUiAvailable ? "可用（实验性）" : status.FlaUiReason ?? "不可用")}\n" +
                "即使现在不可用，也可以先完成向导，稍后在设置中配置。";
            StatusMessage = "检查完成。";
        }
        catch (Exception ex)
        {
            CheckResult = "检查失败：" + ex.Message;
            StatusMessage = "无法完成检查，可稍后在设置中重试。";
            _logger.Warning(ex, "Onboarding source check failed.");
        }
        finally
        {
            IsBusy = false;
            BackCommand.NotifyCanExecuteChanged();
            NextCommand.NotifyCanExecuteChanged();
        }
    }

    [RelayCommand]
    private void Finish()
    {
        Completed = true;
        RequestClose?.Invoke(this, true);
    }

    [RelayCommand]
    private void Skip()
    {
        Completed = true;
        RequestClose?.Invoke(this, true);
    }
}
