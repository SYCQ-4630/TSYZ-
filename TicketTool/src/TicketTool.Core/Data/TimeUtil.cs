using System.Globalization;

namespace TicketTool.Core.Data;

/// <summary>统一的时间文本格式。</summary>
public static class TimeUtil
{
    /// <summary>转换为数据库存储文本。</summary>
    public static string ToDb(DateTimeOffset value)
        => value.ToString("O", CultureInfo.InvariantCulture);

    /// <summary>从数据库文本解析。</summary>
    public static DateTimeOffset FromDb(string value)
        => DateTimeOffset.TryParse(value, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var parsed)
            ? parsed
            : DateTimeOffset.MinValue;
}
