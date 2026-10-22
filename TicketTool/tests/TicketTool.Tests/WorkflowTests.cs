using TicketTool.Core.Data;
using TicketTool.Core.Models;
using TicketTool.Core.Parsing;
using TicketTool.Core.Search;
using TicketTool.Core.Templates;

namespace TicketTool.Tests;

public sealed class WorkflowTests
{
    [Fact]
    public async Task TemplateTicketSearchAndRuleSaveWorkTogether()
    {
        using var workspace = new TempWorkspace();
        var paths = new AppPaths(workspace.Root);
        await using var database = new Database(paths);
        await new DatabaseInitializer(database).InitializeAsync();

        var xlsx = TestXlsx.Create(
            workspace.File("target.xlsx"),
            "工单号", "联系方式", "投诉地址", "备注");

        var templates = new TemplateService(new TemplateRepository(database), new ExpressionIndexManager(database));
        var template = new Template
        {
            Name = "集成测试模板",
            FilePath = xlsx,
            HeaderRow = 1,
            Fields = new List<TemplateField>
            {
                new() { FieldName = "工单号", ColumnIndex = 0, CoreType = CoreFieldType.TicketNo, SortOrder = 0 },
                new() { FieldName = "联系方式", ColumnIndex = 1, CoreType = CoreFieldType.Phone, SortOrder = 1 },
                new() { FieldName = "投诉地址", ColumnIndex = 2, CoreType = CoreFieldType.Address, SortOrder = 2 },
                new() { FieldName = "备注", ColumnIndex = 3, CoreType = CoreFieldType.None, SortOrder = 3 }
            }
        };
        var saved = await templates.CreateAsync(template);
        Assert.Equal(4, saved.Fields.Count);
        Assert.All(saved.Fields, field => Assert.NotEmpty(field.Rules));

        var tickets = new TicketRepository(database);
        var ticketId = await tickets.InsertAsync(new TicketInsert
        {
            TemplateId = saved.Id,
            RawText = "工单号：TS20261022001\n联系方式：13800138000\n投诉地址：北京市朝阳区建国路88号",
            Values = new Dictionary<string, string?>
            {
                ["工单号"] = "TS20261022001",
                ["联系方式"] = "13800138000",
                ["投诉地址"] = "北京市朝阳区建国路88号",
                ["备注"] = null
            },
            Spans = new List<FieldSpan>
            {
                new() { FieldName = "工单号", StartOffset = 4, EndOffset = 17 }
            },
            CreatedAt = DateTimeOffset.Now
        }, saved);
        Assert.True(ticketId > 0);
        Assert.Equal(1, await tickets.CountByTemplateAsync(saved.Id));

        var raw = await tickets.GetRawAsync(ticketId);
        Assert.NotNull(raw);
        var rawValue = raw!.Value;
        Assert.Contains("建国路88号", rawValue.RawText);
        Assert.Single(rawValue.Spans);

        var search = new SearchService(database, new TemplateRepository(database));
        var byPhone = await search.SearchAsync(new TicketSearchRequest { Query = "13800138000" });
        Assert.Equal(1, byPhone.TotalCount);
        Assert.Equal(SearchDomain.Phone, byPhone.EffectiveDomain);

        var byAddress = await search.SearchAsync(new TicketSearchRequest { Query = "北京市朝阳区建国路88号" });
        Assert.Equal(1, byAddress.TotalCount);
        Assert.Equal(SearchDomain.Address, byAddress.EffectiveDomain);

        var byTicket = await search.SearchAsync(new TicketSearchRequest { Query = "TS20261022001" });
        Assert.Equal(1, byTicket.TotalCount);
        Assert.Equal(SearchDomain.TicketNo, byTicket.EffectiveDomain);

        var firstField = saved.Fields.First(field => field.FieldName == "工单号");
        firstField.Rules = new List<FieldRule>
        {
            new()
            {
                TemplateFieldId = firstField.Id,
                RuleType = RuleType.Regex,
                Priority = 5,
                Enabled = true,
                ConfigJson = RuleConfigSerializer.Serialize(new RegexRuleConfig { Pattern = @"TS\d+" })
            }
        };
        await templates.SaveRulesAsync(saved);

        var reloaded = await templates.GetAsync(saved.Id);
        Assert.NotNull(reloaded);
        var reloadedRule = reloaded!.Fields.First(field => field.FieldName == "工单号").Rules.Single();
        Assert.Equal(RuleType.Regex, reloadedRule.RuleType);
        Assert.True((await templates.ListVersionsAsync(saved.Id)).Count >= 2);

        await using var queue = await database.OpenConnectionAsync();
        await using var command = queue.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM sync_queue WHERE ticket_id = $id AND status = 'pending';";
        command.Parameters.AddWithValue("$id", ticketId);
        var pending = Convert.ToInt64(await command.ExecuteScalarAsync(), System.Globalization.CultureInfo.InvariantCulture);
        Assert.Equal(1, pending);
    }
}
