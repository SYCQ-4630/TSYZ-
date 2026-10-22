using TicketTool.Core.Models;
using TicketTool.Core.Parsing;
using TicketTool.Core.Templates;
using TicketTool.Core.Xlsx;

namespace TicketTool.Tests;

public sealed class SampleFilesTests
{
    [Fact]
    public async Task SampleWorkbookAndTextCanBeParsed()
    {
        var root = FindRepositoryRoot();
        var workbook = Path.Combine(root, "samples", "通信工单示例.xlsx");
        var sampleText = Path.Combine(root, "samples", "示例工单.txt");
        Assert.True(File.Exists(workbook), "示例表格不存在。");
        Assert.True(File.Exists(sampleText), "示例工单文本不存在。");

        var headers = await HeaderReader.ReadAsync(workbook, 1);
        Assert.Equal(6, headers.Fields.Count);
        Assert.Equal("工单号", headers.Fields[0].FieldName);
        Assert.Equal("联系方式", headers.Fields[1].FieldName);
        Assert.Equal(CoreFieldType.TicketNo, headers.Fields[0].CoreType);
        Assert.Equal(CoreFieldType.Phone, headers.Fields[1].CoreType);
        Assert.Equal(CoreFieldType.Address, headers.Fields[2].CoreType);

        var template = new Template { Id = 31, Name = "示例", FilePath = workbook, HeaderRow = 1 };
        long fieldId = 100;
        foreach (var draft in headers.Fields)
        {
            var field = new TemplateField
            {
                Id = fieldId++,
                TemplateId = template.Id,
                FieldName = draft.FieldName,
                ColumnIndex = draft.ColumnIndex,
                CoreType = draft.CoreType,
                SortOrder = draft.SortOrder
            };
            field.Rules.Add(TemplateService.CreateDefaultRule(field));
            template.Fields.Add(field);
        }

        var parsed = new TicketParser().Parse(await File.ReadAllTextAsync(sampleText), template);
        Assert.Empty(parsed.MissingFields);
        Assert.Equal("TS20261022001", parsed.GetValue("工单号"));
        Assert.Equal("13800138000", parsed.GetValue("联系方式"));
        Assert.Contains("北京市朝阳区", parsed.GetValue("投诉地址"));
        Assert.Equal("5星", parsed.GetValue("星级"));
        Assert.Equal("否", parsed.GetValue("是否重投"));
        Assert.Equal("2026-10-22", parsed.GetValue("受理日期"));
    }

    private static string FindRepositoryRoot()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        while (directory is not null)
        {
            if (File.Exists(Path.Combine(directory.FullName, "TicketTool.sln")))
            {
                return directory.FullName;
            }
            directory = directory.Parent;
        }
        throw new InvalidOperationException("无法定位仓库根目录。");
    }
}
