namespace DingTalkChatExporter.App.Services;

public interface IDialogService
{
    string? PickOpenFile(string title, string filter);

    string? PickSaveFile(string title, string filter, string? defaultFileName = null);

    bool Confirm(string title, string message);

    void ShowInfo(string title, string message);

    void ShowError(string title, string message);
}
