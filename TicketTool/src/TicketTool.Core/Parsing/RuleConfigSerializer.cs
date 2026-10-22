using System.Text.Json;
using System.Text.RegularExpressions;
using TicketTool.Core.Data;
using TicketTool.Core.Models;

namespace TicketTool.Core.Parsing;

/// <summary>规则配置 JSON 的解析与别名提取。</summary>
public static class RuleConfigSerializer
{
    private static readonly TimeSpan RegexTimeout = TimeSpan.FromMilliseconds(500);

    /// <summary>反序列化规则配置。</summary>
    public static T Parse<T>(string? json) where T : class, new()
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            return new T();
        }

        try
        {
            return JsonSerializer.Deserialize<T>(json, JsonDefaults.Options) ?? new T();
        }
        catch (JsonException)
        {
            return new T();
        }
    }

    /// <summary>序列化规则配置。</summary>
    public static string Serialize<T>(T config) where T : class
        => JsonSerializer.Serialize(config, JsonDefaults.Options);

    /// <summary>获取规则参与锚点扫描的别名集合。</summary>
    public static IReadOnlyList<string> GetAliases(FieldRule rule)
    {
        return rule.RuleType switch
        {
            RuleType.Preset => GetPresetAliases(Parse<PresetRuleConfig>(rule.ConfigJson)),
            RuleType.Anchor => Parse<AnchorRuleConfig>(rule.ConfigJson).Aliases,
            RuleType.Segment => Parse<SegmentRuleConfig>(rule.ConfigJson).Aliases,
            _ => Array.Empty<string>()
        };
    }

    /// <summary>获取规则候选值模式。</summary>
    public static string? GetValuePattern(FieldRule rule)
    {
        return rule.RuleType switch
        {
            RuleType.Preset => PresetCatalog.Find(Parse<PresetRuleConfig>(rule.ConfigJson).PresetKey)?.ValuePattern,
            RuleType.Anchor => Parse<AnchorRuleConfig>(rule.ConfigJson).ValuePattern,
            _ => null
        };
    }

    /// <summary>创建安全正则。</summary>
    public static Regex CreateRegex(string pattern, RegexOptions options = RegexOptions.None)
        => new(pattern, options | RegexOptions.CultureInvariant, RegexTimeout);

    /// <summary>尝试按模式截取候选值，并返回相对偏移。</summary>
    public static bool TryRefine(string? pattern, string candidate, out int offset, out int length)
    {
        offset = 0;
        length = candidate.Length;
        if (string.IsNullOrWhiteSpace(pattern) || pattern == ".*")
        {
            return true;
        }

        try
        {
            var match = CreateRegex(pattern).Match(candidate);
            if (!match.Success)
            {
                return false;
            }
            offset = match.Index;
            length = match.Length;
            return true;
        }
        catch (ArgumentException)
        {
            return false;
        }
        catch (RegexMatchTimeoutException)
        {
            return false;
        }
    }

    private static IReadOnlyList<string> GetPresetAliases(PresetRuleConfig config)
    {
        var preset = PresetCatalog.Find(config.PresetKey);
        var result = new List<string>();
        if (preset is not null)
        {
            result.AddRange(preset.Aliases);
        }
        result.AddRange(config.ExtraAliases.Where(a => !string.IsNullOrWhiteSpace(a)));
        return result;
    }
}
