using CommunityToolkit.Mvvm.ComponentModel;

namespace DingTalkChatExporter.App.ViewModels;

public abstract partial class PageViewModelBase : ObservableObject
{
    protected PageViewModelBase(string key, string title, string description)
    {
        Key = key;
        Title = title;
        Description = description;
    }

    public string Key { get; }

    public string Title { get; }

    public string Description { get; }
}
