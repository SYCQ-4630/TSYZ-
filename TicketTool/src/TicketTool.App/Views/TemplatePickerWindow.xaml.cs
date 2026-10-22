using System.Windows;
using TicketTool.Core.Models;

namespace TicketTool.App.Views;

/// <summary>选择要复制规则的来源模板。</summary>
public partial class TemplatePickerWindow : Window
{
    /// <summary>创建选择窗口。</summary>
    public TemplatePickerWindow(IReadOnlyList<Template> templates)
    {
        InitializeComponent();
        TemplateList.ItemsSource = templates;
        TemplateList.SelectedIndex = templates.Count > 0 ? 0 : -1;
    }

    /// <summary>选中的模板。</summary>
    public Template? SelectedTemplate => TemplateList.SelectedItem as Template;

    private void OnConfirmClick(object sender, RoutedEventArgs e)
    {
        if (SelectedTemplate is null)
        {
            MessageBox.Show("请先选择一个模板。", "选择模板", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        DialogResult = true;
    }

    private void OnCancelClick(object sender, RoutedEventArgs e) => DialogResult = false;
}
