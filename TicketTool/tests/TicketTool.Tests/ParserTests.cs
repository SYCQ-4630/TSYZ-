using TicketTool.Core.Models;
using TicketTool.Core.Parsing;

namespace TicketTool.Tests;

public sealed class ParserTests
{
    private readonly TicketParser _parser = new();

    [Fact]
    public void ParsesFieldsInAnyOrderAndKeepsAddressPunctuation()
    {
        var text = """
            投诉地址：广东省深圳市南山区科技园路1号，A栋、3楼
            联系电话：138-1234-5678
            工单号： gd20240001 
            客户姓名：张三
            """;

        var result = _parser.Parse(text, TestTemplates.CommunicationTemplate());

        Assert.Equal("gd20240001", result.GetValue("工单号"));
        Assert.Equal("GD20240001", TextNormalizer.NormalizeTicketNo(result.GetValue("工单号")));
        Assert.Equal("13812345678", TextNormalizer.NormalizePhone(result.GetValue("联系方式")));
        Assert.Contains("，A栋、3楼", result.GetValue("投诉地址"));
        Assert.Equal("张三", result.GetValue("客户姓名"));
        Assert.DoesNotContain("工单号", result.MissingFields);
        Assert.DoesNotContain("投诉地址", result.MissingFields);
        Assert.Contains("第二联系方式", result.MissingFields);
    }

    [Fact]
    public void MissingFieldIsReportedAndDoesNotBreakOtherFields()
    {
        var text = "工单号：GD20240002\n投诉地址：杭州市西湖区文三路";
        var result = _parser.Parse(text, TestTemplates.CommunicationTemplate());

        Assert.Equal("GD20240002", result.GetValue("工单号"));
        Assert.Contains("联系方式", result.MissingFields);
        Assert.Contains("第二联系方式", result.MissingFields);
    }

    [Fact]
    public void AliasesAndValuePatternRefinePhone()
    {
        var text = "工单号：GD20240003\n电话：联系电话13812345678\n投诉地址：南京市鼓楼区";
        var result = _parser.Parse(text, TestTemplates.CommunicationTemplate());

        var phone = result.Fields.Single(f => f.FieldName == "联系方式");
        Assert.Equal("13812345678", phone.Value);
        Assert.True(phone.StartOffset < phone.EndOffset);
        Assert.Equal("13812345678", text[phone.StartOffset..phone.EndOffset]);
    }

    [Fact]
    public void SecondPhoneFieldUsesItsOwnAliases()
    {
        var text = "工单号：GD1\n联系方式：13800000001\n第二联系方式：13900000002\n投诉地址：上海市浦东新区";
        var result = _parser.Parse(text, TestTemplates.CommunicationTemplate());

        Assert.Equal("13800000001", result.GetValue("联系方式"));
        Assert.Equal("13900000002", result.GetValue("第二联系方式"));
    }

    [Fact]
    public void SpansPointToExactOriginalCharacters()
    {
        var text = "工单号：GD20240009\n投诉地址：北京市朝阳区建国路88号\n联系方式：13700000000";
        var result = _parser.Parse(text, TestTemplates.CommunicationTemplate());
        foreach (var field in result.Fields)
        {
            Assert.Equal(field.Value, text[field.StartOffset..field.EndOffset]);
        }
    }

    [Fact]
    public void AddressContainingNewlineAndCommaIsNotTruncatedByDelimiter()
    {
        var text = "工单号：GD2\n投诉地址：广东省广州市天河区\n华景路1号，2栋\n联系方式：13600000000";
        var result = _parser.Parse(text, TestTemplates.CommunicationTemplate());
        var address = result.GetValue("投诉地址");

        Assert.NotNull(address);
        Assert.Contains("华景路1号，2栋", address);
    }

    [Fact]
    public void RegexRuleCanExtractFromRawText()
    {
        var template = TestTemplates.CommunicationTemplate();
        var field = template.Fields.Single(f => f.FieldName == "客户姓名");
        field.Rules.Clear();
        field.Rules.Add(new FieldRule
        {
            Id = 99,
            TemplateFieldId = field.Id,
            RuleType = RuleType.Regex,
            Priority = 5,
            Enabled = true,
            ConfigJson = RuleConfigSerializer.Serialize(new RegexRuleConfig
            {
                Pattern = @"客户姓名[:：]\s*(?<value>[^\r\n]+)"
            })
        });

        var result = _parser.Parse("工单号：GD3\n客户姓名：李四\n投诉地址：北京市", template);
        Assert.Equal("李四", result.GetValue("客户姓名"));
    }
}
