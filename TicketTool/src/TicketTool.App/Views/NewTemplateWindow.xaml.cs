using System.Windows;
using TicketTool.App.ViewModels;

namespace TicketTool.App.Views;

/// <summary>新建或编辑模板窗口。</summary>
public partial class NewTemplateWindow : Window
{
    /// <summary>创建窗口。</summary>
    public NewTemplateWindow(NewTemplateViewModel viewModel)
    {
        InitializeComponent();
        DataContext = viewModel;
        viewModel.CloseRequested += (_, saved) => DialogResult = saved;
    }
}
