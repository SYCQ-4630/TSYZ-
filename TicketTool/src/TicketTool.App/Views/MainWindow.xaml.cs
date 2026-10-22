using System.Windows;
using TicketTool.App.ViewModels;

namespace TicketTool.App.Views;

/// <summary>主窗口。</summary>
public partial class MainWindow : Window
{
    /// <summary>创建主窗口。</summary>
    public MainWindow(MainViewModel viewModel)
    {
        InitializeComponent();
        DataContext = viewModel;
    }
}
