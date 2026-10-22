using System.Windows;
using TicketTool.App.ViewModels;
using TicketTool.Core.Models;

namespace TicketTool.App.Views;

/// <summary>相似字段选择窗口。</summary>
public partial class SimilarFieldWindow : Window
{
    private readonly SimilarFieldDialogViewModel _viewModel;

    /// <summary>创建窗口。</summary>
    public SimilarFieldWindow(IReadOnlyList<SimilarFieldMatch> matches)
    {
        InitializeComponent();
        _viewModel = new SimilarFieldDialogViewModel(matches);
        DataContext = _viewModel;
    }

    /// <summary>用户确认后的选择；取消时为 null。</summary>
    public IReadOnlyList<SimilarFieldSelection>? Selections { get; private set; }

    private void OnConfirmClick(object sender, RoutedEventArgs e)
    {
        Selections = _viewModel.BuildSelections();
        DialogResult = true;
    }

    private void OnIgnoreAllClick(object sender, RoutedEventArgs e)
    {
        _viewModel.IgnoreAll();
        Selections = Array.Empty<SimilarFieldSelection>();
        DialogResult = true;
    }

    private void OnCancelClick(object sender, RoutedEventArgs e) => DialogResult = false;
}
