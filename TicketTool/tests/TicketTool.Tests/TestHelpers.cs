using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Spreadsheet;
using TicketTool.Core.Models;
using TicketTool.Core.Parsing;

namespace TicketTool.Tests;

internal sealed class TempWorkspace : IDisposable
{
    public TempWorkspace()
    {
        Root = Path.Combine(Path.GetTempPath(), "TicketToolTests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(Root);
    }

    public string Root { get; }

    public string File(string name) => Path.Combine(Root, name);

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(Root))
            {
                Directory.Delete(Root, true);
            }
        }
        catch (IOException)
        {
        }
    }
}

internal static class TestTemplates
{
    public static Template CommunicationTemplate()
    {
        var template = new Template { Id = 7, Name = "通信工单", FilePath = "ticket.xlsx", HeaderRow = 1 };
        template.Fields = new List<TemplateField>
        {
            Field(11, "工单号", 0, CoreFieldType.TicketNo, 0, "ticket_no"),
            Field(12, "投诉地址", 1, CoreFieldType.Address, 1, "address"),
            Field(13, "联系方式", 2, CoreFieldType.Phone, 2, "phone"),
            Field(14, "第二联系方式", 3, CoreFieldType.Phone, 3, "phone"),
            Field(15, "客户姓名", 4, CoreFieldType.None, 4, null),
            Field(16, "星级", 5, CoreFieldType.None, 5, "star")
        };
        return template;
    }

    public static TemplateField Field(
        long id,
        string name,
        int column,
        CoreFieldType core,
        int sort,
        string? presetKey)
    {
        var field = new TemplateField
        {
            Id = id,
            TemplateId = 7,
            FieldName = name,
            ColumnIndex = column,
            CoreType = core,
            SortOrder = sort
        };

        var rule = new FieldRule
        {
            Id = id * 10,
            TemplateFieldId = id,
            RuleType = presetKey is null ? RuleType.Anchor : RuleType.Preset,
            Priority = 0,
            Enabled = true
        };
        rule.ConfigJson = presetKey is null
            ? RuleConfigSerializer.Serialize(new AnchorRuleConfig { Aliases = new List<string> { name } })
            : RuleConfigSerializer.Serialize(new PresetRuleConfig
            {
                PresetKey = presetKey,
                ExtraAliases = new List<string> { name }
            });
        field.Rules.Add(rule);
        return field;
    }
}

internal static class TestXlsx
{
    public static string Create(string path, params string[] headers)
    {
        using var document = SpreadsheetDocument.Create(path, SpreadsheetDocumentType.Workbook);
        var workbookPart = document.AddWorkbookPart();
        workbookPart.Workbook = new Workbook();
        var worksheetPart = workbookPart.AddNewPart<WorksheetPart>();
        var sheetData = new SheetData();
        worksheetPart.Worksheet = new Worksheet(sheetData);
        var sheets = workbookPart.Workbook.AppendChild(new Sheets());
        sheets.Append(new Sheet
        {
            Id = workbookPart.GetIdOfPart(worksheetPart),
            SheetId = 1,
            Name = "Sheet1"
        });

        var row = new Row { RowIndex = 1 };
        for (var i = 0; i < headers.Length; i++)
        {
            row.Append(new Cell
            {
                CellReference = TicketTool.Core.Xlsx.HeaderReader.GetColumnName(i) + "1",
                DataType = CellValues.InlineString,
                InlineString = new InlineString(new Text(headers[i]))
            });
        }
        sheetData.Append(row);
        workbookPart.Workbook.Save();
        return path;
    }
}
