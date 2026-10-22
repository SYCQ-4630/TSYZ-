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
