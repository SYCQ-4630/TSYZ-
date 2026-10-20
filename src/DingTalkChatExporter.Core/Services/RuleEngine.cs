using System.Text.RegularExpressions;
using DingTalkChatExporter.Core.Models;

namespace DingTalkChatExporter.Core.Services;

/// <summary>
/// Deterministic extraction. Regex hits are High confidence, keyword hits are Low
/// (value is the matched fragment), and misses are None with a null value.
/// No value is ever invented.
/// </summary>
public sealed class RuleEngine
{
    public static readonly TimeSpan MatchTimeout = TimeSpan.FromSeconds(1);

    public Dictionary<string, ExtractionResult> Extract(
        RawMessage message,
        IReadOnlyList<ExtractionRule> rules)
    {
        var results = new Dictionary<string, ExtractionResult>(StringComparer.Ordinal);
        if (rules.Count == 0)
        {
            return results;
        }

        foreach (var rule in rules)
        {
            if (string.IsNullOrWhiteSpace(rule.FieldName))
            {
                continue;
            }

            var candidate = Evaluate(message.Content ?? string.Empty, rule);
            if (!results.TryGetValue(rule.FieldName, out var existing) ||
                Rank(candidate.Confidence) > Rank(existing.Confidence))
            {
                results[rule.FieldName] = candidate;
            }
        }

        return results;
    }

    public ExtractionResult Evaluate(string content, ExtractionRule rule)
    {
        if (string.IsNullOrWhiteSpace(rule.Pattern))
        {
            return new ExtractionResult(null, Confidence.None);
        }

        var matchType = (rule.MatchType ?? string.Empty).Trim();
        if (matchType.Equals("regex", StringComparison.OrdinalIgnoreCase) ||
            matchType.Equals("regexp", StringComparison.OrdinalIgnoreCase) ||
            matchType.Equals("正则", StringComparison.OrdinalIgnoreCase))
        {
            return EvaluateRegex(content, rule);
        }

        if (matchType.Equals("keyword", StringComparison.OrdinalIgnoreCase) ||
            matchType.Equals("contains", StringComparison.OrdinalIgnoreCase) ||
            matchType.Equals("关键词", StringComparison.OrdinalIgnoreCase))
        {
            return EvaluateKeyword(content, rule);
        }

        return new ExtractionResult(null, Confidence.None);
    }

    private static ExtractionResult EvaluateRegex(string content, ExtractionRule rule)
    {
        try
        {
            var regex = new Regex(
                rule.Pattern,
                RegexOptions.IgnoreCase | RegexOptions.CultureInvariant,
                MatchTimeout);

            var match = regex.Match(content);
            if (!match.Success)
            {
                return new ExtractionResult(null, Confidence.None);
            }

            var groupIndex = rule.GroupIndex;
            if (groupIndex > 0 && groupIndex < match.Groups.Count && match.Groups[groupIndex].Success)
            {
                return new ExtractionResult(match.Groups[groupIndex].Value, Confidence.High);
            }

            return new ExtractionResult(match.Value, Confidence.High);
        }
        catch (RegexParseException)
        {
            return new ExtractionResult(null, Confidence.None);
        }
        catch (ArgumentException)
        {
            return new ExtractionResult(null, Confidence.None);
        }
        catch (RegexMatchTimeoutException)
        {
            return new ExtractionResult(null, Confidence.None);
        }
    }

    private static ExtractionResult EvaluateKeyword(string content, ExtractionRule rule)
    {
        var index = content.IndexOf(rule.Pattern, StringComparison.OrdinalIgnoreCase);
        if (index < 0)
        {
            return new ExtractionResult(null, Confidence.None);
        }

        return new ExtractionResult(content.Substring(index, rule.Pattern.Length), Confidence.Low);
    }

    private static int Rank(Confidence confidence) => confidence switch
    {
        Confidence.High => 2,
        Confidence.Low => 1,
        _ => 0
    };
}

public static class TemplateRuleSkeletonBuilder
{
    /// <summary>
    /// Reverse mapping helper: one empty rule per template column header. The user
    /// only needs to fill in Pattern afterwards.
    /// </summary>
    public static IReadOnlyList<ExtractionRule> FromHeaders(IEnumerable<string> headers)
    {
        var rules = new List<ExtractionRule>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (var header in headers)
        {
            var name = header?.Trim();
            if (string.IsNullOrWhiteSpace(name) || !seen.Add(name))
            {
                continue;
            }

            rules.Add(new ExtractionRule(name, "regex", string.Empty, 1));
        }

        return rules;
    }
}
