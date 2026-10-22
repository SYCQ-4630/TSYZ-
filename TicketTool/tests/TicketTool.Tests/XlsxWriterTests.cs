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
    public async Task KeepsDominantDataRowStyleAndExpandsTableRange()
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
        Assert.Equal(30d, sheet.GetFirstChild<Columns>()!.Elements<Column>().First().Width!.Value);
    }

    [Fact]
    public async Task UsesDominantDataRowFormat()
    {
        using var workspace = new TempWorkspace();
        var path = workspace.File("variants.xlsx");
        CreateFormatVariantsWorkbook(path);

        var appender = new XlsxRowAppender();
        await appender.AppendRowAsync(
            path,
            new[] { new XlsxCellValue(0, "A"), new XlsxCellValue(1, "B"), new XlsxCellValue(2, "C") },
            headerRow: 1);

        using var document = SpreadsheetDocument.Open(path, false);
        var row = document.WorkbookPart!.WorksheetParts.First().Worksheet.Descendants<Row>().Last();
        Assert.Equal(6u, row.StyleIndex!.Value);
        Assert.Equal(22d, row.Height!.Value);
        var cells = row.Elements<Cell>().ToList();
        Assert.Equal(3u, cells[0].StyleIndex!.Value);
        Assert.Equal(4u, cells[1].StyleIndex!.Value);
        Assert.Equal(3u, cells[2].StyleIndex!.Value);
    }

    [Fact]
    public async Task ReplicatesHorizontalMergeAndMovesValue()
    {
        using var workspace = new TempWorkspace();
        var path = workspace.File("merged.xlsx");
        CreateMergedWorkbook(path);

        using (var probe = SpreadsheetDocument.Open(path, false))
        {
            var profile = XlsxFormatAccumulator.ReadMergeProfile(
                probe.WorkbookPart!.WorksheetParts.First(),
                1,
                3,
                CancellationToken.None);
            Assert.Single(profile.Spans);
            Assert.Equal(0, profile.Spans[0].StartColumn);
            Assert.Equal(1, profile.Spans[0].EndColumn);
        }

        var appender = new XlsxRowAppender();
        await appender.AppendRowAsync(
            path,
            new[] { new XlsxCellValue(0, "first"), new XlsxCellValue(1, "second"), new XlsxCellValue(2, "three") },
            headerRow: 1);

        using (var document = SpreadsheetDocument.Open(path, false))
        {
            var worksheet = document.WorkbookPart!.WorksheetParts.First().Worksheet;
            var row = worksheet.Descendants<Row>().Last();
            var cells = row.Elements<Cell>().ToList();
            Assert.Equal("A4", cells[0].CellReference!.Value);
            Assert.Equal("first", cells[0].InlineString!.InnerText);
            Assert.Equal("C4", cells[1].CellReference!.Value);
            Assert.DoesNotContain(cells, cell => cell.CellReference!.Value == "B4");
            var mergeCells = worksheet.GetFirstChild<MergeCells>();
            Assert.NotNull(mergeCells);
            Assert.Equal(new[] { "A2:B2", "A4:B4" }, mergeCells!.Elements<MergeCell>().Select(m => m.Reference!.Value).ToArray());
            Assert.Equal("A1:C4", worksheet.GetFirstChild<AutoFilter>()!.Reference!.Value);
        }

        await appender.AppendRowAsync(
            path,
            new[] { new XlsxCellValue(0, null), new XlsxCellValue(1, "moved"), new XlsxCellValue(2, "next") },
            headerRow: 1);

        using (var document = SpreadsheetDocument.Open(path, false))
        {
            var worksheet = document.WorkbookPart!.WorksheetParts.First().Worksheet;
            var lastRow = worksheet.Descendants<Row>().Last();
            var firstCell = lastRow.Elements<Cell>().First();
            Assert.Equal("A5", firstCell.CellReference!.Value);
            Assert.Equal("moved", firstCell.InlineString!.InnerText);
            var mergeCells = worksheet.GetFirstChild<MergeCells>()!;
            Assert.Equal(3u, mergeCells.Count!.Value);
            Assert.Equal("A1:C5", worksheet.GetFirstChild<AutoFilter>()!.Reference!.Value);
        }
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

    [Fact]
    public async Task KeepsDetectedFontFillAlignmentAndWrap()
    {
        using var workspace = new TempWorkspace();
        var path = workspace.File("rich.xlsx");
        CreateRichStyleWorkbook(path);

        var appender = new XlsxRowAppender();
        await appender.AppendRowAsync(path, new[] { new XlsxCellValue(0, "value") }, headerRow: 1);

        using var document = SpreadsheetDocument.Open(path, false);
        var row = document.WorkbookPart!.WorksheetParts.First().Worksheet.Descendants<Row>().Last();
        var cell = row.Elements<Cell>().First();
        Assert.Equal("A3", cell.CellReference!.Value);
        var styleIndex = (int)cell.StyleIndex!.Value;
        var stylesheet = document.WorkbookPart.WorkbookStylesPart!.Stylesheet!;
        var format = stylesheet.CellFormats!.Elements<CellFormat>().ElementAt(styleIndex);
        Assert.Equal(1u, format.FontId!.Value);
        Assert.Equal(2u, format.FillId!.Value);
        Assert.True(format.ApplyFont!.Value);
        Assert.True(format.ApplyFill!.Value);
        Assert.Equal(HorizontalAlignmentValues.Center, format.Alignment!.Horizontal!.Value);
        Assert.Equal(VerticalAlignmentValues.Center, format.Alignment.Vertical!.Value);
        Assert.True(format.Alignment.WrapText!.Value);

        var font = stylesheet.Fonts!.Elements<Font>().ElementAt(1);
        Assert.NotNull(font.Bold);
        Assert.Equal(12d, font.FontSize!.Val!.Value);
        Assert.Equal("FFFF0000", font.Color!.Rgb!.Value);
        var fill = stylesheet.Fills!.Elements<Fill>().ElementAt(2);
        Assert.Equal(PatternValues.Solid, fill.PatternFill!.PatternType!.Value);
        Assert.Equal("FFFFFF00", fill.PatternFill.ForegroundColor!.Rgb!.Value);
    }

    private static void CreateFormatVariantsWorkbook(string path)
    {
        using var document = SpreadsheetDocument.Create(path, SpreadsheetDocumentType.Workbook);
        var workbookPart = document.AddWorkbookPart();
        workbookPart.Workbook = new Workbook();
        var worksheetPart = workbookPart.AddNewPart<WorksheetPart>();
        var sheetData = new SheetData();

        sheetData.Append(CreateRow(1, 1, 30, ("A", 1), ("B", 1), ("C", 1)));
        sheetData.Append(CreateRow(2, 6, 22, ("A", 3), ("C", 3)));
        sheetData.Append(CreateRow(3, 6, 22, ("A", 3), ("C", 3)));
        sheetData.Append(CreateRow(4, 8, 26, ("A", 3), ("C", 8)));

        worksheetPart.Worksheet = new Worksheet(
            new Columns(new Column { Min = 2, Max = 2, Width = 14, Style = 4 }),
            sheetData);
        AddSheet(workbookPart, worksheetPart);
        workbookPart.Workbook.Save();
    }

    private static void CreateMergedWorkbook(string path)
    {
        using var document = SpreadsheetDocument.Create(path, SpreadsheetDocumentType.Workbook);
        var workbookPart = document.AddWorkbookPart();
        workbookPart.Workbook = new Workbook();
        var worksheetPart = workbookPart.AddNewPart<WorksheetPart>();
        var sheetData = new SheetData();
        sheetData.Append(CreateRow(1, 1, 28, ("A", 1), ("B", 1), ("C", 1)));
        sheetData.Append(CreateRow(2, 2, 21, ("A", 2), ("C", 2)));
        sheetData.Append(CreateRow(3, 2, 21, ("A", 2), ("C", 2)));

        worksheetPart.Worksheet = new Worksheet(
            sheetData,
            new AutoFilter { Reference = "A1:C3" },
            new MergeCells(new MergeCell { Reference = "A2:B2" }));
        AddSheet(workbookPart, worksheetPart);
        workbookPart.Workbook.Save();
    }

    private static void CreateRichStyleWorkbook(string path)
    {
        using var document = SpreadsheetDocument.Create(path, SpreadsheetDocumentType.Workbook);
        var workbookPart = document.AddWorkbookPart();
        workbookPart.Workbook = new Workbook();
        var worksheetPart = workbookPart.AddNewPart<WorksheetPart>();
        var sheetData = new SheetData();
        sheetData.Append(CreateRow(1, 0, 28, ("A", 1)));
        sheetData.Append(CreateRow(2, 1, 22, ("A", 1)));
        worksheetPart.Worksheet = new Worksheet(sheetData);

        var richFont = new Font(
            new Bold(),
            new FontSize { Val = 12 },
            new Color { Rgb = "FFFF0000" },
            new FontName { Val = "Arial" });
        var richFill = new Fill(new PatternFill(
            new ForegroundColor { Rgb = "FFFFFF00" },
            new BackgroundColor { Rgb = "FFFFFF00" })
        {
            PatternType = PatternValues.Solid
        });
        var richFormat = new CellFormat
        {
            FontId = 1,
            FillId = 2,
            BorderId = 0,
            ApplyFont = true,
            ApplyFill = true,
            ApplyAlignment = true,
            Alignment = new Alignment
            {
                Horizontal = HorizontalAlignmentValues.Center,
                Vertical = VerticalAlignmentValues.Center,
                WrapText = true
            }
        };

        var stylesheet = new Stylesheet();
        stylesheet.Append(
            new Fonts(
                new Font(new FontSize { Val = 11 }, new FontName { Val = "Calibri" }),
                richFont),
            new Fills(
                new Fill(new PatternFill { PatternType = PatternValues.None }),
                new Fill(new PatternFill { PatternType = PatternValues.Gray125 }),
                richFill),
            new Borders(new Border()),
            new CellStyleFormats(new CellFormat()),
            new CellFormats(new CellFormat(), richFormat),
            new CellStyles(new CellStyle { Name = "Normal", FormatId = 0, BuiltinId = 0 }));

        var stylesPart = workbookPart.AddNewPart<WorkbookStylesPart>();
        stylesPart.Stylesheet = stylesheet;
        stylesPart.Stylesheet.Save();

        AddSheet(workbookPart, worksheetPart);
        workbookPart.Workbook.Save();
    }

    private static Row CreateRow(uint rowIndex, uint styleIndex, double height, params (string Column, uint Style)[] cells)
    {
        var row = new Row
        {
            RowIndex = rowIndex,
            StyleIndex = styleIndex,
            Height = height,
            CustomHeight = true
        };
        foreach (var (column, style) in cells)
        {
            row.Append(new Cell
            {
                CellReference = column + rowIndex.ToString(System.Globalization.CultureInfo.InvariantCulture),
                StyleIndex = style,
                DataType = CellValues.InlineString,
                InlineString = new InlineString(new Text(column + rowIndex.ToString(System.Globalization.CultureInfo.InvariantCulture)))
            });
        }
        return row;
    }

    private static void AddSheet(WorkbookPart workbookPart, WorksheetPart worksheetPart)
    {
        var sheets = workbookPart.Workbook.AppendChild(new Sheets());
        sheets.Append(new Sheet
        {
            Id = workbookPart.GetIdOfPart(worksheetPart),
            SheetId = 1,
            Name = "Sheet1"
        });
    }
}
