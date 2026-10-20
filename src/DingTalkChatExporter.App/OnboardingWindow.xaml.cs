using System.Windows;
using DingTalkChatExporter.App.ViewModels;

namespace DingTalkChatExporter.App;

public partial class OnboardingWindow : Window
{
    public OnboardingWindow(OnboardingViewModel viewModel)
    {
        InitializeComponent();
        DataContext = viewModel;
        viewModel.RequestClose += OnRequestClose;
        Closed += OnClosed;
    }

    private void OnRequestClose(object? sender, bool completed)
    {
        DialogResult = completed;
        Close();
    }

    private void OnClosed(object? sender, EventArgs e)
    {
        if (DataContext is OnboardingViewModel viewModel)
        {
            viewModel.RequestClose -= OnRequestClose;
        }

        Closed -= OnClosed;
    }
}
