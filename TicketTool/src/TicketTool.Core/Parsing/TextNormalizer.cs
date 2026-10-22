using System.Text;
using TicketTool.Core.Models;

namespace TicketTool.Core.Parsing;

/// <summary>
/// 核心字段规范化的唯一实现。写入与查询必须共用本类。
/// </summary>
public static class TextNormalizer
{
    /// <summary>全角字符转半角，并统一表意空格。</summary>
    public static string ToHalfWidth(string? text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return string.Empty;
        }

        var builder = new StringBuilder(text.Length);
        foreach (var ch in text)
        {
            if (ch == '\u3000')
            {
                builder.Append(' ');
            }
            else if (ch >= '\uFF01' && ch <= '\uFF5E')
            {
                builder.Append((char)(ch - 0xFEE0));
            }
            else
            {
                builder.Append(ch);
            }
        }
        return builder.ToString();
    }

    /// <summary>工单号：去首尾空格并转大写。</summary>
    public static string NormalizeTicketNo(string? value)
        => (value ?? string.Empty).Trim().ToUpperInvariant();

    /// <summary>联系方式：全角转半角、去空格、去常见横线与括号。</summary>
    public static string NormalizePhone(string? value)
    {
        var text = ToHalfWidth(value);
        var builder = new StringBuilder(text.Length);
        foreach (var ch in text)
        {
            if (ch is ' ' or '\t' or '\r' or '\n')
            {
                continue;
            }

            if (ch is '-' or '\u2010' or '\u2011' or '\u2012' or '\u2013' or '\u2014' or '\u2015' or '\u2212' or '(' or ')' or '+')
            {
                continue;
            }

            builder.Append(ch);
        }
        return builder.ToString();
    }

    /// <summary>投诉地址：全半角归一、去首尾空格，保留内部标点。</summary>
    public static string NormalizeAddress(string? value)
        => ToHalfWidth(value).Trim();

    /// <summary>按核心类型规范化。</summary>
    public static string Normalize(CoreFieldType type, string? value) => type switch
    {
        CoreFieldType.TicketNo => NormalizeTicketNo(value),
        CoreFieldType.Phone => NormalizePhone(value),
        CoreFieldType.Address => NormalizeAddress(value),
        _ => value ?? string.Empty
    };

    /// <summary>写入 data_json 的内部规范化键。</summary>
    public static string CoreKey(CoreFieldType type, long fieldId)
        => $"__norm:{EnumText.ToDb(type)}:{fieldId.ToString(System.Globalization.CultureInfo.InvariantCulture)}";

    /// <summary>将字符串安全转换为 JSON Path 中的引号键。</summary>
    public static string ToJsonPathKey(string key)
        => "$.\"" + key.Replace("\\", "\\\\", StringComparison.Ordinal).Replace("\"", "\\\"", StringComparison.Ordinal) + "\"";
}
