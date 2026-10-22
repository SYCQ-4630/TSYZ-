using System.Text;
using System.Text.RegularExpressions;
using TicketTool.Core.Models;

namespace TicketTool.Core.Parsing;

/// <summary>
/// 相似字段检测：找出原文中未被规则覆盖、但名称与模板字段相近的标签，
/// 或同一标签同时命中多个字段的情况，交给用户决定写入哪一列。
/// </summary>
public static class SimilarFieldDetector
{
    private const double CandidateThreshold = 0.62;
    private const int MaxMatches = 12;

    private static readonly char[] TrailingSeparators = { ' ', '\t', '\u3000', '，', ',', '；', ';', '、', '|', '。' };

    private static readonly Regex LabelPattern = new(
        @"(?m)(?:^|[\s\r\n，。；;、|])(?<label>[\p{L}\p{N}_（）()\-]{2,24})[ \t\u3000]*[:：][ \t\u3000]*",
        RegexOptions.Compiled | RegexOptions.CultureInvariant,
        TimeSpan.FromMilliseconds(300));

    private sealed record FieldProfile(
        TemplateField Field,
        HashSet<string> NormalizedTerms,
        List<string> OriginalTerms);

    private sealed record CandidateScore(
        FieldProfile Profile,
        string Term,
        double Score);

    /// <summary>检测相似字段。同一标签只保留一条候选组。</summary>
    public static List<SimilarFieldMatch> Detect(
        string? rawText,
        Template? template,
        TicketParseResult? parseResult = null)
    {
        var raw = rawText ?? string.Empty;
        if (raw.Length == 0 || template is null || template.Fields.Count == 0)
        {
            return new List<SimilarFieldMatch>();
        }

        var profiles = BuildProfiles(template);
        if (profiles.Count == 0)
        {
            return new List<SimilarFieldMatch>();
        }

        var matches = new Dictionary<string, SimilarFieldMatch>(StringComparer.OrdinalIgnoreCase);
        MatchCollection labels;
        try
        {
            labels = LabelPattern.Matches(raw);
        }
        catch (RegexMatchTimeoutException)
        {
            return new List<SimilarFieldMatch>();
        }

        for (var i = 0; i < labels.Count; i++)
        {
            var match = labels[i];
            var label = match.Groups["label"].Value.Trim();
            if (!IsPlausibleLabel(label))
            {
                continue;
            }

            var valueStart = match.Index + match.Length;
            var lineEnd = FindLineEnd(raw, valueStart);
            var nextStart = i + 1 < labels.Count ? labels[i + 1].Index : raw.Length;
            var valueEnd = Math.Min(lineEnd, nextStart);
            if (!TryReadValue(raw, valueStart, valueEnd, out var value, out var trimmedStart, out var trimmedEnd))
            {
                continue;
            }

            if (value.StartsWith("//", StringComparison.Ordinal))
            {
                continue;
            }

            var normalizedLabel = Normalize(label);
            if (normalizedLabel.Length < 2)
            {
                continue;
            }

            if (matches.TryGetValue(normalizedLabel, out var existing))
            {
                existing.OccurrenceCount++;
                continue;
            }

            var exact = profiles
                .Where(p => p.NormalizedTerms.Contains(normalizedLabel))
                .ToList();

            List<SimilarFieldCandidate> candidates;
            if (exact.Count > 1)
            {
                candidates = exact
                    .Select(p => BuildCandidate(p, label, 1.0, "名称相同", parseResult))
                    .OrderBy(c => c.ColumnIndex)
                    .Take(6)
                    .ToList();
            }
            else if (exact.Count == 1)
            {
                continue;
            }
            else
            {
                var scored = new List<CandidateScore>();
                foreach (var profile in profiles)
                {
                    var best = profile.OriginalTerms
                        .Select(term => new CandidateScore(profile, term, Score(label, term)))
                        .OrderByDescending(item => item.Score)
                        .ThenBy(item => item.Term.Length)
                        .FirstOrDefault();
                    if (best is not null && best.Score >= CandidateThreshold)
                    {
                        scored.Add(best);
                    }
                }

                candidates = scored
                    .GroupBy(item => item.Profile.Field.Id)
                    .Select(group => group.OrderByDescending(item => item.Score).First())
                    .OrderByDescending(item => item.Score)
                    .ThenBy(item => item.Profile.Field.ColumnIndex)
                    .Take(6)
                    .Select(item => BuildCandidate(
                        item.Profile,
                        item.Term,
                        item.Score,
                        item.Score >= 0.82 ? "名称高度相近" : "名称相近",
                        parseResult))
                    .ToList();
            }

            if (candidates.Count == 0)
            {
                continue;
            }

            matches[normalizedLabel] = new SimilarFieldMatch
            {
                SourceLabel = label,
                SourceValue = value,
                ValueStart = trimmedStart,
                ValueEnd = trimmedEnd,
                Candidates = candidates
            };
        }

        return matches.Values
            .OrderBy(item => item.ValueStart)
            .Take(MaxMatches)
            .ToList();
    }

    private static List<FieldProfile> BuildProfiles(Template template)
    {
        var profiles = new List<FieldProfile>(template.Fields.Count);
        foreach (var field in template.Fields.OrderBy(f => f.SortOrder).ThenBy(f => f.Id))
        {
            var terms = new List<string>();
            AddTerm(terms, field.FieldName);
            foreach (var rule in field.Rules.Where(r => r.Enabled))
            {
                foreach (var alias in RuleConfigSerializer.GetAliases(rule))
                {
                    AddTerm(terms, alias);
                }
            }

            var normalized = new HashSet<string>(
                terms.Select(Normalize).Where(term => term.Length > 0),
                StringComparer.OrdinalIgnoreCase);
            if (normalized.Count > 0)
            {
                profiles.Add(new FieldProfile(field, normalized, terms));
            }
        }
        return profiles;
    }

    private static void AddTerm(List<string> terms, string? term)
    {
        if (string.IsNullOrWhiteSpace(term))
        {
            return;
        }

        var trimmed = term.Trim();
        if (trimmed.Length > 0 && !terms.Contains(trimmed, StringComparer.OrdinalIgnoreCase))
        {
            terms.Add(trimmed);
        }
    }

    private static SimilarFieldCandidate BuildCandidate(
        FieldProfile profile,
        string term,
        double score,
        string reason,
        TicketParseResult? parseResult)
    {
        return new SimilarFieldCandidate
        {
            FieldId = profile.Field.Id,
            FieldName = profile.Field.FieldName,
            ColumnIndex = profile.Field.ColumnIndex,
            Score = score,
            Reason = reason,
            CurrentValue = parseResult?.Fields.FirstOrDefault(f => f.FieldId == profile.Field.Id)?.Value
        };
    }

    private static bool IsPlausibleLabel(string label)
    {
        if (label.Length < 2 || label.Length > 24)
        {
            return false;
        }

        if (label.Contains("http", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        return label.Any(char.IsLetter);
    }

    private static bool TryReadValue(
        string raw,
        int valueStart,
        int valueEnd,
        out string value,
        out int trimmedStart,
        out int trimmedEnd)
    {
        value = string.Empty;
        trimmedStart = valueStart;
        trimmedEnd = valueEnd;
        if (valueEnd <= valueStart)
        {
            return false;
        }

        var text = raw[valueStart..valueEnd];
        var startOffset = 0;
        while (startOffset < text.Length && (char.IsWhiteSpace(text[startOffset]) || text[startOffset] == '\u3000'))
        {
            startOffset++;
        }

        var endOffset = text.Length;
        while (endOffset > startOffset)
        {
            var ch = text[endOffset - 1];
            if (char.IsWhiteSpace(ch) || TrailingSeparators.Contains(ch))
            {
                endOffset--;
                continue;
            }
            break;
        }

        if (endOffset <= startOffset)
        {
            return false;
        }

        value = text[startOffset..endOffset];
        trimmedStart = valueStart + startOffset;
        trimmedEnd = valueStart + endOffset;
        return value.Length > 0;
    }

    private static int FindLineEnd(string raw, int start)
    {
        for (var i = start; i < raw.Length; i++)
        {
            if (raw[i] is '\r' or '\n')
            {
                return i;
            }
        }
        return raw.Length;
    }

    private static string Normalize(string? text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return string.Empty;
        }

        var builder = new StringBuilder(text.Length);
        foreach (var ch in text)
        {
            if (char.IsLetterOrDigit(ch))
            {
                builder.Append(char.ToLowerInvariant(ch));
            }
        }
        return builder.ToString();
    }

    private static double Score(string label, string term)
    {
        var a = Normalize(label);
        var b = Normalize(term);
        if (a.Length == 0 || b.Length == 0)
        {
            return 0;
        }

        if (string.Equals(a, b, StringComparison.Ordinal))
        {
            return 1;
        }

        if (a.Contains(b, StringComparison.Ordinal) || b.Contains(a, StringComparison.Ordinal))
        {
            var shorter = Math.Min(a.Length, b.Length);
            var longer = Math.Max(a.Length, b.Length);
            if (shorter < 2)
            {
                return 0;
            }
            return 0.72 + 0.23 * shorter / longer;
        }

        var dice = DiceBigrams(a, b);
        var jaccard = JaccardChars(a, b);
        var lcs = LongestCommonSubstring(a, b) / (double)Math.Max(a.Length, b.Length);
        var edit = LevenshteinSimilarity(a, b);
        return Math.Max(Math.Max(dice, 0.5 * jaccard + 0.5 * lcs), edit);
    }

    private static double DiceBigrams(string a, string b)
    {
        if (a.Length < 2 || b.Length < 2)
        {
            return 0;
        }

        var counts = new Dictionary<string, int>(StringComparer.Ordinal);
        for (var i = 0; i < a.Length - 1; i++)
        {
            var key = a.Substring(i, 2);
            counts[key] = counts.TryGetValue(key, out var count) ? count + 1 : 1;
        }

        var intersection = 0;
        for (var i = 0; i < b.Length - 1; i++)
        {
            var key = b.Substring(i, 2);
            if (counts.TryGetValue(key, out var count) && count > 0)
            {
                intersection++;
                counts[key] = count - 1;
            }
        }

        return 2.0 * intersection / (a.Length - 1 + b.Length - 1);
    }

    private static double JaccardChars(string a, string b)
    {
        var setA = new HashSet<char>(a);
        var setB = new HashSet<char>(b);
        var intersection = setA.Count(setB.Contains);
        var union = setA.Count + setB.Count - intersection;
        return union == 0 ? 0 : intersection / (double)union;
    }

    private static double LevenshteinSimilarity(string a, string b)
    {
        var maxLength = Math.Max(a.Length, b.Length);
        if (maxLength == 0)
        {
            return 0;
        }

        var previous = new int[b.Length + 1];
        var current = new int[b.Length + 1];
        for (var j = 0; j <= b.Length; j++)
        {
            previous[j] = j;
        }

        for (var i = 1; i <= a.Length; i++)
        {
            current[0] = i;
            for (var j = 1; j <= b.Length; j++)
            {
                var cost = a[i - 1] == b[j - 1] ? 0 : 1;
                current[j] = Math.Min(
                    Math.Min(previous[j] + 1, current[j - 1] + 1),
                    previous[j - 1] + cost);
            }
            (previous, current) = (current, previous);
        }

        return 1.0 - previous[b.Length] / (double)maxLength;
    }

    private static int LongestCommonSubstring(string a, string b)
    {
        var previous = new int[b.Length + 1];
        var current = new int[b.Length + 1];
        var best = 0;
        for (var i = 1; i <= a.Length; i++)
        {
            for (var j = 1; j <= b.Length; j++)
            {
                if (a[i - 1] == b[j - 1])
                {
                    current[j] = previous[j - 1] + 1;
                    if (current[j] > best)
                    {
                        best = current[j];
                    }
                }
                else
                {
                    current[j] = 0;
                }
            }
            (previous, current) = (current, previous);
            Array.Clear(current);
        }
        return best;
    }
}
