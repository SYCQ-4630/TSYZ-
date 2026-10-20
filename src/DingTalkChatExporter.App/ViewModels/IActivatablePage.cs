namespace DingTalkChatExporter.App.ViewModels;

/// <summary>Pages that need to refresh from services when navigated to.</summary>
public interface IActivatablePage
{
    void OnActivated();
}
