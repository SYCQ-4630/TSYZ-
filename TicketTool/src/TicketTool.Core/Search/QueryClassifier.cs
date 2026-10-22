using System.Text.RegularExpressions;
using TicketTool.Core.Models;
using TicketTool.Core.Parsing;

namespace TicketTool.Core.Search;

/// <summary>按输入内容自动判断搜索域，并给出规范化值。</summary>
public static class QueryClassifier
{
    private static readonly Regex Mobile = new(@"^1[3-9]\d{9}$", RegexOptions.CultureInvariant, TimeSpan.FromMilliseconds(200));
    private static readonly Regex Landline = new(@"^0\d{2,3}-?\d{7,8}$", RegexOptions.CultureInvariant, TimeSpan.FromMilliseconds(200));
    private static readonly Regex TicketLike = new(@"^[A-Za-z0-9_-]{4,32}$", RegexOptions.CultureInvariant, TimeSpan.FromMilliseconds(200));
    private static readonly Regex Cjk = new(@"[\u3400-\u9FFF]", RegexOptions.CultureInvariant, TimeSpan.FromMilliseconds(200));

    /// <summary>分类并规范化。</summary>
    public static SearchQueryClassification Classify(string? input, SearchDomain requested)
    {
        var raw = (input ?? string.Empty).Trim();
        if (raw.Length == 0)
        {
            return SearchQueryClassification.Unknown(string.Empty);
        }

        var domain = requested;
        if (domain == SearchDomain.Auto)
        {
            var compact = TextNormalizer.NormalizePhone(raw);
            if (Mobile.IsMatch(compact) || Landline.IsMatch(compact))
            {
                domain = SearchDomain.Phone;
            }
            else if (Cjk.IsMatch(raw))
            {
                domain = SearchDomain.Address;
            }
            else if (TicketLike.IsMatch(raw))
            {
                domain = SearchDomain.TicketNo;
            }
            else
            {
                domain = SearchDomain.TicketNo;
            }
        }

        var normalized = domain switch
        {
            SearchDomain.TicketNo => TextNormalizer.NormalizeTicketNo(raw),
            SearchDomain.Phone => TextNormalizer.NormalizePhone(raw),
            SearchDomain.Address => TextNormalizer.NormalizeAddress(raw),
            _ => raw
        };

        return new SearchQueryClassification(domain, normalized);
    }
}
