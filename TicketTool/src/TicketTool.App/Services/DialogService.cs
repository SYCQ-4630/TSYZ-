using System.Windows;
using System.IO;
using Microsoft.Win32;

namespace TicketTool.App.Services;

/// <summary>面向非技术用户的通用对话框。</summary>
public interface IDialogService
{
    string? OpenFile(string title, string filter, string? initialDirectory = null);
    string? SaveFile(string title, string filter, string defaultFileName, string? initialDirectory = null);
    bool Confirm(string title, string message);
    void Info(string title, string message);
    void Error(string title, string message);
    bool ConfirmDanger(string title, string message);
}

/// <summary>WPF 对话框实现。</summary>
public sealed class DialogService : IDialogService
{
    public string? OpenFile(string title, string filter, string? initialDirectory = null)
    {
        var dialog = new OpenFileDialog { Title = title, Filter = filter, CheckFileExists = true };
        if (!string.IsNullOrWhiteSpace(initialDirectory) && Directory.Exists(initialDirectory))
        {
            dialog.InitialDirectory = initialDirectory;
        }
        return dialog.ShowDialog() == true ? dialog.FileName : null;
    }

    public string? SaveFile(string title, string filter, string defaultFileName, string? initialDirectory = null)
    {
        var dialog = new SaveFileDialog
        {
            Title = title,
            Filter = filter,
            FileName = defaultFileName,
            OverwritePrompt = true
        };
        if (!string.IsNullOrWhiteSpace(initialDirectory) && Directory.Exists(initialDirectory))
        {
            dialog.InitialDirectory = initialDirectory;
        }
        return dialog.ShowDialog() == true ? dialog.FileName : null;
    }

    public bool Confirm(string title, string message)
        => MessageBox.Show(message, title, MessageBoxButton.OKCancel, MessageBoxImage.Question) == MessageBoxResult.OK;

    public bool ConfirmDanger(string title, string message)
        => MessageBox.Show(message, title, MessageBoxButton.OKCancel, MessageBoxImage.Warning) == MessageBoxResult.OK;

    public void Info(string title, string message)
        => MessageBox.Show(message, title, MessageBoxButton.OK, MessageBoxImage.Information);

    public void Error(string title, string message)
        => MessageBox.Show(message, title, MessageBoxButton.OK, MessageBoxImage.Error);
}
