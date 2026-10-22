using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Spreadsheet;
using TicketTool.Core.Models;
using TicketTool.Core.Xlsx;

namespace TicketTool.Tests;

public sealed class XlsxWriterTests
{
    [Fact]
    public async Task AppendsInlineStringRowsAndKeepsBackup()
    {
        using var workspace = new TempWorkspace();
        var path = workspace.File("template.xlsx");
        TestXlsx.Create(path, "工单号", "投诉地址", "联系方式");

        var appender = new XlsxRowAppender();
        var first = await appender.AppendRowAsync(path, new[]
        {
            new XlsxCellValue(0, "GD20240001"),
            new XlsxCellValue(1, "广东省深圳市南山区，科技园路1号"),
            new XlsxCellValue(2, "13812345678")
        });
        var second = await appender.AppendRowAsync(path, new[]
        {
            new XlsxCellValue(0, "GD20240002"),
            new XlsxCellValue(1, "北京市朝阳区"),
            new XlsxCellValue(2, "13900000000")
        });

        Assert.Equal(2, first.RowNumber);
        Assert.Equal(3, second.RowNumber);
        Assert.True(File.Exists(path + ".backup"));

        using var document = SpreadsheetDocument.Open(path, false);
        var sheet = document.WorkbookPart!.WorksheetParts.First().Worksheet;
        var rows = sheet.Descendants<Row>().ToList();
        Assert.Equal(3, rows.Count);
        Assert.Equal("GD20240002", rows[2].Elements<Cell>().First().InlineString!.InnerText);
        Assert.Equal(CellValues.InlineString, rows[2].Elements<Cell>().First().DataType!.Value);
    }

    [Fact]
    public async Task PreservesHeaderAndOtherSheetContent()
    {
        using var workspace = new TempWorkspace();
        var path = workspace.File("template.xlsx");
        TestXlsx.Create(path, "A", "B");

        var appender = new XlsxRowAppender();
        await appender.AppendRowAsync(path, new[] { new XlsxCellValue(0, "x"), new XlsxCellValue(1, "y") });

        using var document = SpreadsheetDocument.Open(path, false);
        var sheet = document.WorkbookPart!.WorksheetParts.First().Worksheet;
        Assert.Equal("A", sheet.Descendants<Cell>().First().InlineString!.InnerText);
        Assert.Equal(2, sheet.Descendants<Row>().Count());
    }


    [Fact]
    public async Task CopiesLastRowStyleAndExpandsTableRange()
    {
        using var workspace = new TempWorkspace();
        var path = workspace.File("styled.xlsx");
        CreateStyledWorkbook(path);

        var appender = new XlsxRowAppender();
        var result = await appender.AppendRowAsync(
            path,
            new[] { new XlsxCellValue(0, "GD2"), new XlsxCellValue(1, "地址") },
            headerRow: 1);

        Assert.Equal(3, result.RowNumber);
        using var document = SpreadsheetDocument.Open(path, false);
        var workbook = document.WorkbookPart!;
        var worksheetPart = workbook.WorksheetParts.First();
        var sheet = worksheetPart.Worksheet;
        var rows = sheet.Descendants<Row>().ToList();
        Assert.Equal(3, rows.Count);
        var appended = rows[2];
        Assert.Equal(6u, appended.StyleIndex!.Value);
        Assert.Equal(22d, appended.Height!.Value);
        Assert.True(appended.CustomHeight!.Value);

        var cells = appended.Elements<Cell>().ToList();
        Assert.Equal(3u, cells[0].StyleIndex!.Value);
        Assert.Equal(4u, cells[1].StyleIndex!.Value);

        var tablePart = Assert.Single(worksheetPart.TableDefinitionParts);
        Assert.Equal("A1:C3", tablePart.Table!.Reference!.Value);
        Assert.Equal("A1:C3", tablePart.Table.GetFirstChild<AutoFilter>()!.Reference!.Value);
    }

    private static void CreateStyledWorkbook(string path)
    {
        using var document = SpreadsheetDocument.Create(path, SpreadsheetDocumentType.Workbook);
        var workbookPart = document.AddWorkbookPart();
        workbookPart.Workbook = new Workbook();
        var worksheetPart = workbookPart.AddNewPart<WorksheetPart>();
        var sheetData = new SheetData();

        var header = new Row { RowIndex = 1, StyleIndex = 5, Height = 28, CustomHeight = true };
        for (var i = 0; i < 3; i++)
        {
            header.Append(new Cell
            {
                CellReference = HeaderReader.GetColumnName(i) + "1",
                StyleIndex = 1,
                DataType = CellValues.InlineString,
                InlineString = new InlineString(new Text("标题" + i))
            });
        }

        var data = new Row { RowIndex = 2, StyleIndex = 6, Height = 22, CustomHeight = true };
        data.Append(new Cell
        {
            CellReference = "A2",
            StyleIndex = 3,
            DataType = CellValues.InlineString,
            InlineString = new InlineString(new Text("GD1"))
        });
        data.Append(new Cell
        {
            CellReference = "B2",
            DataType = CellValues.InlineString,
            InlineString = new InlineString(new Text("地址"))
        });
        data.Append(new Cell
        {
            CellReference = "C2",
            StyleIndex = 3,
            DataType = CellValues.InlineString,
            InlineString = new InlineString(new Text("13800000000"))
        });

        sheetData.Append(header);
        sheetData.Append(data);
        worksheetPart.Worksheet = new Worksheet(
            new Columns(new Column { Min = 2, Max = 2, Width = 30, Style = 4 }),
            sheetData);
        var sheets = workbookPart.Workbook.AppendChild(new Sheets());
        sheets.Append(new Sheet
        {
            Id = workbookPart.GetIdOfPart(worksheetPart),
            SheetId = 1,
            Name = "Sheet1"
        });

        var tablePart = worksheetPart.AddNewPart<TableDefinitionPart>();
        var table = new Table
        {
            Id = 1,
            Name = "Table1",
            Reference = "A1:C2",
            HeaderRowCount = 1,
            TotalsRowShown = false
        };
        table.Append(new AutoFilter { Reference = "A1:C2" });
        tablePart.Table = table;
        table.Save();

        workbookPart.Workbook.Save();
    }

    [Fact]
    public async Task MissingFileThrowsBusinessException()
    {
        using var workspace = new TempWorkspace();
        var appender = new XlsxRowAppender();
        await Assert.ThrowsAsync<XlsxFileMissingException>(() =>
            appender.AppendRowAsync(workspace.File("missing.xlsx"), Array.Empty<XlsxCellValue>()));
    }

    [Fact]
    public void DetectsWritableFile()
    {
        using var workspace = new TempWorkspace();
        var path = workspace.File("template.xlsx");
        TestXlsx.Create(path, "A");
        Assert.True(FileLockDetector.IsWritable(path));
    }
}
