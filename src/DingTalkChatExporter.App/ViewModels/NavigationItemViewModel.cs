namespace DingTalkChatExporter.App.ViewModels;

public sealed record NavigationItemViewModel(string Glyph, PageViewModelBase Page)
{
    public string Key => Page.Key;
    public string Title => Page.Title;
    public string Description => Page.Description;
}
