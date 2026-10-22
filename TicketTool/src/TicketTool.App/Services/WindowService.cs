using System.Windows;
using Microsoft.Extensions.DependencyInjection;
using TicketTool.App.ViewModels;
using TicketTool.App.Views;
using TicketTool.Core.Models;

namespace TicketTool.App.Services;

/// <summary>打开编辑与溯源窗口。</summary>
public interface IWindowService
{
    Template? ShowTemplateEditor(Template? existing);
    bool ShowRuleEditor(Template template, string rawText);
    void ShowTrace(long ticketId, string fieldName, string displayValue);
    IReadOnlyList<SimilarFieldSelection>? ShowSimilarFields(IReadOnlyList<SimilarFieldMatch> matches);
}

/// <summary>基于 DI 的窗口服务。</summary>
public sealed class WindowService : IWindowService
{
    private readonly IServiceProvider _provider;

    public WindowService(IServiceProvider provider) => _provider = provider;

    public Template? ShowTemplateEditor(Template? existing)
    {
        var viewModel = existing is null
            ? ActivatorUtilities.CreateInstance<NewTemplateViewModel>(_provider)
            : ActivatorUtilities.CreateInstance<NewTemplateViewModel>(_provider, existing);
        var window = new NewTemplateWindow(viewModel)
        {
            Owner = Application.Current.MainWindow
        };
        return window.ShowDialog() == true ? viewModel.SavedTemplate : null;
    }

    public bool ShowRuleEditor(Template template, string rawText)
    {
        var viewModel = ActivatorUtilities.CreateInstance<RuleEditorViewModel>(_provider, template, rawText);
        var window = new RuleEditorWindow(viewModel)
        {
            Owner = Application.Current.MainWindow
        };
        return window.ShowDialog() == true;
    }

    public void ShowTrace(long ticketId, string fieldName, string displayValue)
    {
        var viewModel = ActivatorUtilities.CreateInstance<TraceViewModel>(_provider, ticketId, fieldName, displayValue);
        var window = new TraceWindow(viewModel)
        {
            Owner = Application.Current.MainWindow
        };
        window.ShowDialog();
    }

    public IReadOnlyList<SimilarFieldSelection>? ShowSimilarFields(IReadOnlyList<SimilarFieldMatch> matches)
    {
        var window = new SimilarFieldWindow(matches)
        {
            Owner = Application.Current.MainWindow
        };
        return window.ShowDialog() == true ? window.Selections : null;
    }
}
