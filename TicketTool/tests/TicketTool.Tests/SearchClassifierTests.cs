using TicketTool.Core.Models;
using TicketTool.Core.Search;

namespace TicketTool.Tests;

public sealed class SearchClassifierTests
{
    [Theory]
    [InlineData("13812345678", SearchDomain.Phone)]
    [InlineData("0571-88881111", SearchDomain.Phone)]
    [InlineData("GD20240001", SearchDomain.TicketNo)]
    [InlineData("广东省深圳市南山区", SearchDomain.Address)]
    public void DetectsDomain(string query, SearchDomain expected)
    {
        Assert.Equal(expected, QueryClassifier.Classify(query, SearchDomain.Auto).Domain);
    }

    [Fact]
    public void NormalizesByDomain()
    {
        Assert.Equal("13812345678", QueryClassifier.Classify("138-1234-5678", SearchDomain.Phone).NormalizedValue);
        Assert.Equal("GD1", QueryClassifier.Classify(" gd1 ", SearchDomain.TicketNo).NormalizedValue);
    }
}
