using System.Globalization;
using System.Windows.Data;
using TicketTool.Core.Models;

namespace TicketTool.App.Converters;

/// <summary>同步状态转面向用户的中文文案。</summary>
public sealed class SyncStatusTextConverter : IValueConverter
{
    /// <inheritdoc />
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
        => value is SyncStatus status
            ? status switch
            {
                SyncStatus.Pending => "等待同步",
                SyncStatus.Syncing => "同步中",
                SyncStatus.Synced => "已同步",
                SyncStatus.Blocked => "等待关闭表格",
                _ => "同步失败"
            }
            : string.Empty;

    /// <inheritdoc />
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}
