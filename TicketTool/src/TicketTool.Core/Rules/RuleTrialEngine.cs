using TicketTool.Core.Models;
using TicketTool.Core.Parsing;

namespace TicketTool.Core.Rules;

/// <summary>单条规则的试跑结果。</summary>
public sealed class RuleTrialCandidate
{
    /// <summary>规则。</summary>
    public FieldRule Rule { get; set; } = new();

    /// <summary>抽取值。</summary>
    public string Value { get; set; } = string.Empty;

    /// <summary>起始偏移。</summary>
    public int StartOffset { get; set; }

    /// <summary>结束偏移。</summary>
    public int EndOffset { get; set; }

    /// <summary>是否满足值模式。</summary>
    public bool IsValid { get; set; }

    /// <summary>规则的人类可读说明。</summary>
    public string Description { get; set; } = string.Empty;
}

/// <summary>字段试跑汇总。</summary>
public sealed class RuleTrialResult
{
    /// <summary>各规则的候选结果。</summary>
    public List<RuleTrialCandidate> Candidates { get; set; } = new();

    /// <summary>冲突提示。</summary>
    public string? ConflictMessage { get; set; }

    /// <summary>最终采用的值。</summary>
    public string? SelectedValue { get; set; }

    /// <summary>最终采用的区间。</summary>
    public (int Start, int End)? SelectedSpan { get; set; }
}

/// <summary>规则试跑引擎：不落库、不改数据，直接展示每条规则的命中情况。</summary>
public static class RuleTrialEngine
{
    /// <summary>对单字段执行试跑。</summary>
    public static RuleTrialResult Run(string rawText, TemplateField field)
    {
        var result = new RuleTrialResult();
        var enabled = field.Rules.Where(r => r.Enabled).OrderByDescending(r => r.Priority).ThenBy(r => r.Id).ToList();
        if (enabled.Count == 0)
        {
            return result;
        }

        foreach (var rule in enabled)
        {
            var clone = new TemplateField
            {
                Id = field.Id,
                TemplateId = field.TemplateId,
                FieldName = field.FieldName,
                ColumnIndex = field.ColumnIndex,
                CoreType = field.CoreType,
                SortOrder = field.SortOrder,
                Rules = new List<FieldRule> { rule }
            };
            var scan = AnchorScanner.Scan(rawText, new[] { clone });
            var parsed = FieldExtractionEngine.Extract(rawText, clone, scan);
            if (parsed is null)
            {
                continue;
            }

            result.Candidates.Add(new RuleTrialCandidate
            {
                Rule = rule,
                Value = parsed.Value,
                StartOffset = parsed.StartOffset,
                EndOffset = parsed.EndOffset,
                IsValid = parsed.IsValid,
                Description = DescribeRule(rule)
            });
        }

        var valid = result.Candidates.Where(c => c.IsValid).ToList();
        var chosen = valid.FirstOrDefault() ?? result.Candidates.FirstOrDefault();
        if (chosen is not null)
        {
            result.SelectedValue = chosen.Value;
            result.SelectedSpan = (chosen.StartOffset, chosen.EndOffset);
        }

        var distinct = valid.Select(c => c.Value.Trim()).Distinct(StringComparer.Ordinal).ToList();
        if (distinct.Count > 1)
        {
            result.ConflictMessage = $"有 {distinct.Count} 条规则得到不同结果，确认导入时将按优先级采用“{chosen?.Value}”。";
        }

        return result;
    }

    /// <summary>规则说明。</summary>
    public static string DescribeRule(FieldRule rule)
    {
        var type = rule.RuleType switch
        {
            RuleType.Preset => "预置字段",
            RuleType.Anchor => "字段名称锚点",
            RuleType.Regex => "高级模式",
            _ => "分段取值"
        };
        return $"{type}（优先级 {rule.Priority}）";
    }
}
