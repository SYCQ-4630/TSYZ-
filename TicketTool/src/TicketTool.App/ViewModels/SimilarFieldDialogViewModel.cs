using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using TicketTool.Core.Models;
using TicketTool.Core.Xlsx;

namespace TicketTool.App.ViewModels;

/// <summary>相似字段选择结果。</summary>
public sealed record SimilarFieldSelection(SimilarFieldMatch Match, long? TargetFieldId);

/// <summary>下拉框中的一个写入目标。</summary>
public sealed class SimilarFieldCandidateOption
{
    private SimilarFieldCandidateOption()
    {
    }

    /// <summary>目标字段主键；为空表示忽略。</summary>
    public long? FieldId { get; private init; }

    /// <summary>下拉框显示文本。</summary>
    public string DisplayText { get; private init; } = string.Empty;

    /// <summary>候选详情。</summary>
    public SimilarFieldCandidate? Candidate { get; private init; }

    /// <summary>创建一个忽略选项。</summary>
    public static SimilarFieldCandidateOption Ignore() => new()
    {
        DisplayText = "忽略此字段（不写入）"
    };

    /// <summary>从候选创建选项。</summary>
    public static SimilarFieldCandidateOption FromCandidate(SimilarFieldCandidate candidate)
    {
        var column = HeaderReader.GetColumnName(candidate.ColumnIndex);
        var current = string.IsNullOrWhiteSpace(candidate.CurrentValue)
            ? "当前为空"
            : $"当前值：{Trim(candidate.CurrentValue)}";
        return new SimilarFieldCandidateOption
        {
            FieldId = candidate.FieldId,
            Candidate = candidate,
            DisplayText = $"写入“{candidate.FieldName}”（{column} 列 · {current}）"
        };
    }

    private static string Trim(string value)
    {
        var text = value.Trim();
        return text.Length <= 16 ? text : text[..16] + "…";
    }
}

/// <summary>弹窗中的一行相似字段。</summary>
public sealed partial class SimilarFieldRowViewModel : ObservableObject
{
    [ObservableProperty]
    private SimilarFieldCandidateOption _selectedCandidate = null!;

    /// <summary>创建一行。</summary>
    public SimilarFieldRowViewModel(SimilarFieldMatch match)
    {
        Match = match;
        Candidates = new ObservableCollection<SimilarFieldCandidateOption>
        {
            SimilarFieldCandidateOption.Ignore()
        };
        foreach (var candidate in match.Candidates.OrderByDescending(item => item.Score))
        {
            Candidates.Add(SimilarFieldCandidateOption.FromCandidate(candidate));
        }
        _selectedCandidate = Candidates[0];
    }

    /// <summary>原始匹配信息。</summary>
    public SimilarFieldMatch Match { get; }

    /// <summary>候选列表。</summary>
    public ObservableCollection<SimilarFieldCandidateOption> Candidates { get; }

    /// <summary>“标签：值”显示文本。</summary>
    public string SourceDisplay => $"{Match.SourceLabel}：{Match.SourceValue}";

    /// <summary>出现次数提示。</summary>
    public string OccurrenceText => Match.OccurrenceCount > 1
        ? $"该标签在原文中出现 {Match.OccurrenceCount} 次，这里使用第一次出现的值。"
        : "原文中该标签与模板字段名称相近。";

    /// <summary>返回忽略选项。</summary>
    public void Ignore() => SelectedCandidate = Candidates[0];
}

/// <summary>相似字段确认弹窗的视图模型。</summary>
public sealed class SimilarFieldDialogViewModel
{
    /// <summary>创建视图模型。</summary>
    public SimilarFieldDialogViewModel(IReadOnlyList<SimilarFieldMatch> matches)
    {
        Rows = new ObservableCollection<SimilarFieldRowViewModel>(
            matches.Select(match => new SimilarFieldRowViewModel(match)));
    }

    /// <summary>所有相似字段行。</summary>
    public ObservableCollection<SimilarFieldRowViewModel> Rows { get; }

    /// <summary>全部忽略。</summary>
    public void IgnoreAll()
    {
        foreach (var row in Rows)
        {
            row.Ignore();
        }
    }

    /// <summary>生成用户选择。</summary>
    public IReadOnlyList<SimilarFieldSelection> BuildSelections()
    {
        return Rows
            .Where(row => row.SelectedCandidate.FieldId is not null)
            .Select(row => new SimilarFieldSelection(row.Match, row.SelectedCandidate.FieldId))
            .ToList();
    }
}
