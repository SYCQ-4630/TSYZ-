using TicketTool.Core.Models;
using TicketTool.Core.Parsing;

namespace TicketTool.Tests;

public sealed class SimilarFieldDetectorTests
{
    [Fact]
    public void DetectsSimilarLabelAndSuggestsClosestField()
    {
        var template = BuildTemplate();
        var text = "工单号：GD20240001\n联系电话号码：13812345678\n安装地址：广东省深圳市南山区科技园路1号";

        var matches = SimilarFieldDetector.Detect(text, template);

        var phone = Assert.Single(matches, m => m.SourceLabel == "联系电话号码");
        Assert.Equal("13812345678", phone.SourceValue);
        Assert.Contains(phone.Candidates, c => c.FieldName == "联系方式" && c.Score >= 0.8);

        var address = Assert.Single(matches, m => m.SourceLabel == "安装地址");
        Assert.Equal("广东省深圳市南山区科技园路1号", address.SourceValue);
        Assert.Contains(address.Candidates, c => c.FieldName == "客户地址" && c.Score >= 0.8);
    }

    [Fact]
    public void DetectsLabelWithInsertedCharacter()
    {
        var template = BuildTemplate();

        var matches = SimilarFieldDetector.Detect("联系人电话：13812345678", template);

        var match = Assert.Single(matches);
        Assert.Contains(match.Candidates, c => c.FieldName == "联系方式" && c.Score >= 0.75);
    }

    [Fact]
    public void PromptsWhenOneLabelMatchesMultipleFieldsExactly()
    {
        var template = BuildTemplate();
        var shared = "联系电话";
        foreach (var field in template.Fields.Where(f => f.FieldName is "联系方式" or "备用联系方式"))
        {
            field.Rules.Clear();
            field.Rules.Add(new FieldRule
            {
                RuleType = RuleType.Anchor,
                ConfigJson = RuleConfigSerializer.Serialize(new AnchorRuleConfig { Aliases = { shared } })
            });
        }

        var matches = SimilarFieldDetector.Detect($"{shared}：13812345678", template);

        var match = Assert.Single(matches);
        Assert.Equal(2, match.Candidates.Count);
        Assert.All(match.Candidates, candidate => Assert.Equal(1d, candidate.Score, 3));
        Assert.Equal(new[] { "联系方式", "备用联系方式" }, match.Candidates.Select(c => c.FieldName).ToArray());
    }

    [Fact]
    public void SkipsUniqueExactAndCountsRepeatedLabels()
    {
        var template = BuildTemplate();
        var matches = SimilarFieldDetector.Detect(
            "联系电话号码：13800000000\n联系电话号码：13900000000",
            template);

        var match = Assert.Single(matches);
        Assert.Equal(2, match.OccurrenceCount);
        Assert.Equal("13800000000", match.SourceValue);
    }

    [Fact]
    public void IgnoresLabelsWithoutCandidateAndUrls()
    {
        var template = BuildTemplate();
        var matches = SimilarFieldDetector.Detect(
            "备注：客户要求下午回访\n链接：http://example.com/a:b",
            template);

        Assert.Empty(matches);
    }

    [Fact]
    public void ReturnsEmptyForMissingInput()
    {
        Assert.Empty(SimilarFieldDetector.Detect(null, BuildTemplate()));
        Assert.Empty(SimilarFieldDetector.Detect("任意文本", null));
    }

    private static Template BuildTemplate()
    {
        return new Template
        {
            Id = 7,
            Name = "测试模板",
            Fields =
            {
                Field("工单号", 0, "ticket_no", "工单号", "单号"),
                Field("联系方式", 1, null, "联系方式", "联系电话", "电话"),
                Field("备用联系方式", 2, null, "备用联系方式", "备用电话"),
                Field("客户地址", 3, null, "客户地址", "地址", "投诉地址")
            }
        };
    }

    private static TemplateField Field(string name, int column, string? preset, params string[] aliases)
    {
        var field = new TemplateField { Id = column + 1, FieldName = name, ColumnIndex = column };
        field.Rules.Add(preset is null
            ? new FieldRule
            {
                RuleType = RuleType.Anchor,
                ConfigJson = RuleConfigSerializer.Serialize(new AnchorRuleConfig { Aliases = aliases.ToList() })
            }
            : new FieldRule
            {
                RuleType = RuleType.Preset,
                ConfigJson = RuleConfigSerializer.Serialize(new PresetRuleConfig
                {
                    PresetKey = preset,
                    ExtraAliases = aliases.ToList()
                })
            });
        return field;
    }
}
