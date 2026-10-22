using System.ComponentModel;
using System.Runtime.CompilerServices;
using TicketTool.Core.Models;

namespace TicketTool.App.ViewModels;

/// <summary>下拉选项。</summary>
public sealed record OptionItem<T>(string Text, T Value);

/// <summary>预览表格中的一行字段。</summary>
public sealed class FieldPreviewRow : INotifyPropertyChanged
{
    private string _value = string.Empty;

    public FieldPreviewRow()
    {
    }

    public FieldPreviewRow(ParsedField? parsed, TemplateField field)
    {
        FieldId = field.Id;
        FieldName = field.FieldName;
        StartOffset = parsed?.StartOffset ?? 0;
        EndOffset = parsed?.EndOffset ?? 0;
        IsMissing = parsed is null;
        IsValid = parsed?.IsValid ?? false;
        HasConflict = parsed?.HasConflict ?? false;
        Note = parsed?.ConflictMessage ?? (parsed is null ? "原文中未找到该字段" : (parsed.IsValid ? string.Empty : "格式与预期不完全一致，请核对"));
        OriginalValue = parsed?.Value ?? string.Empty;
        _value = OriginalValue;
    }

    public long FieldId { get; set; }

    public string FieldName { get; set; } = string.Empty;

    public int StartOffset { get; set; }

    public int EndOffset { get; set; }

    public bool IsMissing { get; init; }

    public bool IsValid { get; init; }

    public bool HasConflict { get; init; }

    public string Note { get; init; } = string.Empty;

    public string OriginalValue { get; init; } = string.Empty;

    public string Value
    {
        get => _value;
        set
        {
            if (_value == value)
            {
                return;
            }
            _value = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(IsModified));
            OnPropertyChanged(nameof(StatusText));
        }
    }

    public bool IsModified => !string.Equals(Value, OriginalValue, StringComparison.Ordinal);

    public string StatusText => IsMissing ? "未识别" : !IsValid ? "格式待确认" : HasConflict ? "多规则命中" : IsModified ? "已手动修正" : "已识别";

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

/// <summary>同步状态徽标显示。</summary>
public sealed class SyncBadge
{
    public SyncBadge(SyncStatistics statistics)
    {
        Statistics = statistics;
        Text = statistics.Badge switch
        {
            SyncBadgeState.Synced => "已同步",
            SyncBadgeState.Syncing => "同步中",
            SyncBadgeState.WaitingForFile => "等待关闭表格",
            _ => "同步失败"
        };
    }

    public SyncStatistics Statistics { get; }

    public string Text { get; }

    public string Detail => $"待同步 {Statistics.Pending} · 同步中 {Statistics.Syncing} · 等待文件 {Statistics.Blocked} · 失败 {Statistics.Failed} · 已同步 {Statistics.Synced}";
}
