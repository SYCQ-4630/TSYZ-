using System.Windows;
using TicketTool.App.ViewModels;

namespace TicketTool.App.Views;

/// <summary>规则编辑窗口。</summary>
public partial class RuleEditorWindow : Window
{
    /// <summary>创建窗口。</summary>
    public RuleEditorWindow(RuleEditorViewModel viewModel)
    {
        InitializeComponent();
        DataContext = viewModel;
        viewModel.CloseRequested += (_, saved) => DialogResult = saved;
    }
}
