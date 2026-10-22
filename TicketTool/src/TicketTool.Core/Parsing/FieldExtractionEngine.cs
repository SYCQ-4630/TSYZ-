using System.Globalization;
using System.Text.RegularExpressions;
using TicketTool.Core.Models;

namespace TicketTool.Core.Parsing;

/// <summary>按字段规则从锚点候选中抽取最终值。</summary>
public static class FieldExtractionEngine
{
    private sealed record Candidate(string Value, int Start, int End, FieldRule Rule, bool IsValid);

    /// <summary>抽取字段值。</summary>
    public static ParsedField? Extract(
        string rawText,
        TemplateField field,
        AnchorScanResult scan)
    {
        var candidates = new List<Candidate>();
        var anchors = scan.ByField.TryGetValue(field.Id, out var list) ? list : new List<AnchorRegion>();

        foreach (var rule in field.Rules.Where(r => r.Enabled).OrderByDescending(r => r.Priority).ThenBy(r => r.Id))
        {
            switch (rule.RuleType)
            {
                case RuleType.Regex:
                    ExtractRegex(rawText, rule, candidates);
                    break;
                case RuleType.Segment:
                    ExtractSegment(rule, field, anchors, candidates);
                    break;
                default:
                    ExtractAnchored(rule, anchors, candidates);
                    break;
            }
        }

        if (candidates.Count == 0)
        {
            return null;
        }

        var distinctValues = candidates
            .Where(c => c.IsValid && !string.IsNullOrWhiteSpace(c.Value))
            .Select(c => c.Value.Trim())
            .Distinct(StringComparer.Ordinal)
            .ToList();

        var chosen = candidates.FirstOrDefault(c => c.IsValid && !string.IsNullOrWhiteSpace(c.Value))
                     ?? candidates.FirstOrDefault(c => !string.IsNullOrWhiteSpace(c.Value))
                     ?? candidates[0];

        return new ParsedField
        {
            FieldId = field.Id,
            FieldName = field.FieldName,
            Value = chosen.Value,
            StartOffset = chosen.Start,
            EndOffset = chosen.End,
            MatchedRuleType = chosen.Rule.RuleType,
            IsValid = chosen.IsValid,
            HasConflict = distinctValues.Count > 1,
            ConflictMessage = distinctValues.Count > 1
                ? $"该字段有 {distinctValues.Count} 条规则得到不同结果，已按优先级采用当前值。"
                : null
        };
    }

    private static void ExtractAnchored(FieldRule rule, List<AnchorRegion> anchors, List<Candidate> candidates)
    {
        if (anchors.Count == 0)
        {
            return;
        }

        var aliases = new HashSet<string>(
            RuleConfigSerializer.GetAliases(rule).Select(a => a.Trim()),
            StringComparer.OrdinalIgnoreCase);
        if (aliases.Count == 0)
        {
            return;
        }

        var pattern = RuleConfigSerializer.GetValuePattern(rule);
        foreach (var anchor in anchors.Where(a => aliases.Contains(a.Alias)))
        {
            AddCandidate(candidates, rule, pattern, anchor.Value, anchor.ValueStart);
        }
    }

    private static void ExtractSegment(FieldRule rule, TemplateField field, List<AnchorRegion> anchors, List<Candidate> candidates)
    {
        _ = field;
        var config = RuleConfigSerializer.Parse<SegmentRuleConfig>(rule.ConfigJson);
        var aliases = new HashSet<string>(config.Aliases.Select(a => a.Trim()), StringComparer.OrdinalIgnoreCase);
        if (aliases.Count == 0 || string.IsNullOrEmpty(config.Separator))
        {
            return;
        }

        foreach (var anchor in anchors.Where(a => aliases.Contains(a.Alias)))
        {
            var value = anchor.Value;
            var parts = value.Split(new[] { config.Separator }, StringSplitOptions.None);
            if (config.Index < 0 || config.Index >= parts.Length)
            {
                continue;
            }

            var offset = 0;
            for (var i = 0; i < config.Index; i++)
            {
                offset += parts[i].Length + config.Separator.Length;
            }

            var part = parts[config.Index];
            candidates.Add(new Candidate(
                part,
                anchor.ValueStart + offset,
                anchor.ValueStart + offset + part.Length,
                rule,
                true));
        }
    }

    private static void ExtractRegex(string rawText, FieldRule rule, List<Candidate> candidates)
    {
        var config = RuleConfigSerializer.Parse<RegexRuleConfig>(rule.ConfigJson);
        if (string.IsNullOrWhiteSpace(config.Pattern))
        {
            return;
        }

        try
        {
            var regex = RuleConfigSerializer.CreateRegex(config.Pattern);
            var match = regex.Match(rawText);
            if (!match.Success)
            {
                return;
            }

            System.Text.RegularExpressions.Group group = match;
            if (!string.IsNullOrWhiteSpace(config.GroupName))
            {
                var named = match.Groups[config.GroupName];
                if (named.Success)
                {
                    group = named;
                }
            }
            else if (match.Groups.Count > 1)
            {
                group = match.Groups[1];
            }

            if (!group.Success)
            {
                return;
            }

            candidates.Add(new Candidate(group.Value, group.Index, group.Index + group.Length, rule, true));
        }
        catch (ArgumentException)
        {
            return;
        }
        catch (System.Text.RegularExpressions.RegexMatchTimeoutException)
        {
            return;
        }
    }

    private static void AddCandidate(
        List<Candidate> candidates,
        FieldRule rule,
        string? pattern,
        string rawValue,
        int absoluteStart)
    {
        if (string.IsNullOrEmpty(rawValue))
        {
            return;
        }

        if (RuleConfigSerializer.TryRefine(pattern, rawValue, out var offset, out var length))
        {
            var sliced = rawValue.Substring(offset, Math.Min(length, rawValue.Length - offset));
            candidates.Add(new Candidate(
                sliced,
                absoluteStart + offset,
                absoluteStart + offset + sliced.Length,
                rule,
                true));
        }
        else
        {
            candidates.Add(new Candidate(rawValue, absoluteStart, absoluteStart + rawValue.Length, rule, false));
        }
    }

    /// <summary>将规则试跑结果格式化为业务语言。</summary>
    public static string DescribeCandidate(string value, int start, int end)
        => string.Create(CultureInfo.InvariantCulture, $"抽取值：{value}（原文第 {start + 1} 至 {end} 个字符）");
}
