using System.Windows;
using TicketTool.App.ViewModels;

namespace TicketTool.App.Views;

/// <summary>字段溯源窗口。</summary>
public partial class TraceWindow : Window
{
    /// <summary>创建窗口。</summary>
    public TraceWindow(TraceViewModel viewModel)
    {
        InitializeComponent();
        DataContext = viewModel;
        Loaded += async (_, _) => await viewModel.InitializeAsync().ConfigureAwait(true);
    }

    private void OnCloseClick(object sender, RoutedEventArgs e) => Close();
}
