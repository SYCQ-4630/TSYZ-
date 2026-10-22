using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Documents;
using System.Windows.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Serilog;
using TicketTool.App.Services;
using TicketTool.Core.Data;
using TicketTool.Core.Models;

namespace TicketTool.App.ViewModels;

/// <summary>工单原文与字段溯源。</summary>
public sealed partial class TraceViewModel : ObservableObject
{
    private readonly TicketRepository _tickets;
    private readonly IDialogService _dialogs;
    private readonly ILogger _logger;

    [ObservableProperty]
    private string _rawText = string.Empty;

    [ObservableProperty]
    private FlowDocument? _document;

    [ObservableProperty]
    private FieldSpan? _selectedSpan;

    [ObservableProperty]
    private string _status = "正在读取工单原文…";

    [ObservableProperty]
    private bool _isBusy;

    /// <summary>创建溯源视图模型。</summary>
    public TraceViewModel(
        TicketRepository tickets,
        IDialogService dialogs,
        long ticketId,
        string fieldName,
        string displayValue,
        ILogger? logger = null)
    {
        _tickets = tickets;
        _dialogs = dialogs;
        TicketId = ticketId;
        FieldName = fieldName;
        DisplayValue = displayValue;
        _logger = logger ?? Log.ForContext<TraceViewModel>();
        WindowTitle = $"字段溯源 · {fieldName}";
    }

    public long TicketId { get; }

    public string FieldName { get; }

    public string DisplayValue { get; }

    public string WindowTitle { get; }

    public ObservableCollection<FieldSpan> Spans { get; } = new();

    /// <summary>加载原文与区间。</summary>
    public async Task InitializeAsync()
    {
        IsBusy = true;
        try
        {
            var data = await _tickets.GetRawAsync(TicketId).ConfigureAwait(true);
            if (data is null)
            {
                Status = "没有找到该工单的原文，可能已被清理。";
                Document = BuildDocument(string.Empty, null);
                return;
            }

            RawText = data.Value.RawText;
            Spans.Clear();
            foreach (var span in data.Value.Spans.OrderBy(s => s.StartOffset))
            {
                Spans.Add(span);
            }

            var match = Spans.FirstOrDefault(s => string.Equals(s.FieldName, FieldName, StringComparison.Ordinal));
            if (match is null && !string.IsNullOrWhiteSpace(DisplayValue))
            {
                var index = RawText.IndexOf(DisplayValue, StringComparison.Ordinal);
                if (index >= 0)
                {
                    match = new FieldSpan
                    {
                        FieldName = FieldName,
                        StartOffset = index,
                        EndOffset = index + DisplayValue.Length
                    };
                    Spans.Add(match);
                }
            }

            SelectedSpan = match;
            if (match is null)
            {
                Status = $"原文 {RawText.Length:N0} 个字符，但没有定位到“{FieldName}”。可能该字段是手动补充的。";
            }
            else
            {
                Status = $"原文 {RawText.Length:N0} 个字符，已在原文中高亮“{FieldName}”，区间 {match.StartOffset}–{match.EndOffset}。";
            }
        }
        catch (Exception ex)
        {
            _logger.Error(ex, "TraceLoadFailed {TicketId}", TicketId);
            Status = "读取原文失败：" + ex.Message;
            _dialogs.Error("读取原文失败", ex.Message);
        }
        finally
        {
            IsBusy = false;
            Document = BuildDocument(RawText, SelectedSpan);
        }
    }

    partial void OnSelectedSpanChanged(FieldSpan? value)
    {
        if (RawText.Length == 0)
        {
            return;
        }
        Document = BuildDocument(RawText, value);
        if (value is not null)
        {
            Status = $"正在查看“{value.FieldName}”，区间 {value.StartOffset}–{value.EndOffset}。";
        }
    }

    [RelayCommand]
    private void Close() => Application.Current?.MainWindow?.Activate();

    private static FlowDocument BuildDocument(string raw, FieldSpan? highlight)
    {
        var document = new FlowDocument
        {
            FontFamily = new FontFamily("Microsoft YaHei UI, Segoe UI"),
            FontSize = 13,
            PagePadding = new Thickness(10),
            LineHeight = 20
        };

        if (string.IsNullOrEmpty(raw))
        {
            document.Blocks.Add(new Paragraph(new Run("（没有可显示的原文）")));
            return document;
        }

        var lineStart = 0;
        while (lineStart <= raw.Length)
        {
            var newline = raw.IndexOf('\n', lineStart);
            var lineEnd = newline < 0 ? raw.Length : newline;
            var lineText = raw[lineStart..lineEnd].TrimEnd('\r');
            var paragraph = new Paragraph { Margin = new Thickness(0, 0, 0, 2) };
            AddRuns(paragraph, lineText, lineStart, highlight);
            document.Blocks.Add(paragraph);
            if (newline < 0)
            {
                break;
            }
            lineStart = newline + 1;
        }

        return document;
    }

    private static void AddRuns(Paragraph paragraph, string lineText, int lineStart, FieldSpan? highlight)
    {
        if (highlight is null || lineText.Length == 0)
        {
            paragraph.Inlines.Add(new Run(lineText));
            return;
        }

        var start = Math.Max(highlight.StartOffset - lineStart, 0);
        var end = Math.Min(highlight.EndOffset - lineStart, lineText.Length);
        if (start >= end)
        {
            paragraph.Inlines.Add(new Run(lineText));
            return;
        }

        if (start > 0)
        {
            paragraph.Inlines.Add(new Run(lineText[..start]));
        }

        paragraph.Inlines.Add(new Run(lineText[start..end])
        {
            Background = Brushes.Gold,
            FontWeight = FontWeights.SemiBold
        });

        if (end < lineText.Length)
        {
            paragraph.Inlines.Add(new Run(lineText[end..]));
        }
    }
}
