using System.Text;
using TicketTool.Core.Models;

namespace TicketTool.Core.Parsing;

/// <summary>原文中的一个锚点区域。</summary>
public sealed class AnchorRegion
{
    /// <summary>字段主键。</summary>
    public long FieldId { get; init; }

    /// <summary>字段名称。</summary>
    public string FieldName { get; init; } = string.Empty;

    /// <summary>命中的别名。</summary>
    public string Alias { get; init; } = string.Empty;

    /// <summary>锚点起始位置。</summary>
    public int Start { get; init; }

    /// <summary>值起始位置（冒号之后）。</summary>
    public int ValueStart { get; init; }

    /// <summary>值结束位置（下一个锚点之前）。</summary>
    public int End { get; set; }

    /// <summary>抽取出的候选值。</summary>
    public string Value { get; set; } = string.Empty;
}

/// <summary>一次锚点扫描的结果。</summary>
public sealed class AnchorScanResult
{
    /// <summary>按原文位置排序的全部锚点。</summary>
    public List<AnchorRegion> Regions { get; init; } = new();

    /// <summary>按字段主键分组的锚点。</summary>
    public Dictionary<long, List<AnchorRegion>> ByField { get; init; } = new();
}

/// <summary>
/// 锚点扫描器：从左到右一次扫描，为所有字段建立“别名 + 冒号”锚点，
/// 再按下一个锚点位置切分取值区间，保证地址内的逗号与换行不被截断。
/// </summary>
public static class AnchorScanner
{
    private static readonly char[] TrailingSeparators = { ' ', '\t', '\r', '\n', '，', ',', '；', ';', '、', '|', '。' };
    private static readonly char[] LeadingValueSpaces = { ' ', '\t', '\u3000' };

    /// <summary>执行一次全字段扫描。</summary>
    public static AnchorScanResult Scan(string rawText, IReadOnlyList<TemplateField> fields)
    {
        var raw = rawText ?? string.Empty;
        var regions = new List<AnchorRegion>();

        foreach (var field in fields.OrderBy(f => f.SortOrder).ThenBy(f => f.Id))
        {
            var aliasSet = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var rule in field.Rules.Where(r => r.Enabled))
            {
                foreach (var alias in RuleConfigSerializer.GetAliases(rule))
                {
                    var trimmed = alias.Trim();
                    if (trimmed.Length > 0)
                    {
                        aliasSet.Add(trimmed);
                    }
                }
            }

            foreach (var alias in aliasSet)
            {
                FindAnchors(raw, field, alias, regions);
            }
        }

        regions.Sort((a, b) =>
        {
            var compare = a.Start.CompareTo(b.Start);
            if (compare != 0)
            {
                return compare;
            }
            compare = b.Alias.Length.CompareTo(a.Alias.Length);
            return compare != 0 ? compare : a.FieldId.CompareTo(b.FieldId);
        });

        var deduped = new List<AnchorRegion>(regions.Count);
        foreach (var region in regions)
        {
            if (deduped.Count > 0 && region.Start < deduped[^1].ValueStart)
            {
                continue;
            }
            deduped.Add(region);
        }

        for (var i = 0; i < deduped.Count; i++)
        {
            var end = i + 1 < deduped.Count ? deduped[i + 1].Start : raw.Length;
            end = TrimEnd(raw, deduped[i].ValueStart, end);
            deduped[i].End = end;
            deduped[i].Value = end > deduped[i].ValueStart
                ? raw[deduped[i].ValueStart..end]
                : string.Empty;
        }

        var byField = deduped
            .GroupBy(r => r.FieldId)
            .ToDictionary(g => g.Key, g => g.ToList());

        return new AnchorScanResult { Regions = deduped, ByField = byField };
    }

    private static void FindAnchors(string raw, TemplateField field, string alias, List<AnchorRegion> regions)
    {
        var searchIndex = 0;
        while (searchIndex < raw.Length)
        {
            var start = raw.IndexOf(alias, searchIndex, StringComparison.OrdinalIgnoreCase);
            if (start < 0)
            {
                break;
            }

            var cursor = start + alias.Length;
            while (cursor < raw.Length && LeadingValueSpaces.Contains(raw[cursor]))
            {
                cursor++;
            }

            if (cursor < raw.Length && (raw[cursor] == ':' || raw[cursor] == '：'))
            {
                cursor++;
                while (cursor < raw.Length && LeadingValueSpaces.Contains(raw[cursor]))
                {
                    cursor++;
                }

                regions.Add(new AnchorRegion
                {
                    FieldId = field.Id,
                    FieldName = field.FieldName,
                    Alias = alias,
                    Start = start,
                    ValueStart = cursor
                });
            }

            searchIndex = start + Math.Max(1, alias.Length);
        }
    }

    private static int TrimEnd(string raw, int valueStart, int end)
    {
        var cursor = Math.Min(end, raw.Length);
        while (cursor > valueStart && TrailingSeparators.Contains(raw[cursor - 1]))
        {
            cursor--;
        }
        return cursor;
    }

    /// <summary>将锚点区间转换为便于调试的文本。</summary>
    public static string Describe(AnchorRegion region, string rawText)
    {
        var builder = new StringBuilder();
        builder.Append(region.FieldName).Append(" [").Append(region.Start).Append("..").Append(region.End).Append("]: ");
        builder.Append(region.End > region.ValueStart ? rawText[region.ValueStart..region.End] : string.Empty);
        return builder.ToString();
    }
}
