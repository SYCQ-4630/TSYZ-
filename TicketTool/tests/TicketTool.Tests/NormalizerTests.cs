using TicketTool.Core.Models;
using TicketTool.Core.Parsing;

namespace TicketTool.Tests;

public sealed class NormalizerTests
{
    [Fact]
    public void TicketNumber_IsTrimmedAndUpperInvariant()
    {
        Assert.Equal("GD20240001", TextNormalizer.NormalizeTicketNo("  gd20240001 "));
    }

    [Fact]
    public void Phone_RemovesSpacesHyphensAndConvertsFullWidth()
    {
        Assert.Equal("13812345678", TextNormalizer.NormalizePhone("１３８-１２３４ ５６７８"));
        Assert.Equal("057188881111", TextNormalizer.NormalizePhone("0571-8888 1111"));
    }

    [Fact]
    public void Address_KeepsInnerPunctuationAndOnlyTrimsEnds()
    {
        var value = TextNormalizer.NormalizeAddress("　广东省深圳市南山区，科技园路1号　");
        Assert.Equal("广东省深圳市南山区,科技园路1号", value);
    }

    [Fact]
    public void AllCoreTypes_UseSingleImplementation()
    {
        Assert.Equal("A1", TextNormalizer.Normalize(CoreFieldType.TicketNo, " a1 "));
        Assert.Equal("13800000000", TextNormalizer.Normalize(CoreFieldType.Phone, "138-0000-0000"));
        Assert.Equal("北京 市", TextNormalizer.Normalize(CoreFieldType.Address, "北京 市 "));
    }
}
