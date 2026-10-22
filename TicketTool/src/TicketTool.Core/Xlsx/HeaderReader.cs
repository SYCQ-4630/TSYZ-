using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Spreadsheet;
using TicketTool.Core.Models;
using TicketTool.Core.Parsing;

namespace TicketTool.Core.Xlsx;

/// <summary>表头读取结果。</summary>
public sealed class HeaderReadResult
{
    /// <summary>字段草案。</summary>
    public List<HeaderFieldDraft> Fields { get; set; } = new();

    /// <summary>最大列号。</summary>
    public int MaxColumnIndex { get; set; }

    /// <summary>表头行号。</summary>
    public int HeaderRow { get; set; }

    /// <summary>文件最后一行行号。</summary>
    public int LastRowNumber { get; set; }
}

/// <summary>以 SAX 方式读取 XLSX 表头并自动识别核心字段。</summary>
public static class HeaderReader
{
    /// <summary>读取指定表头行，跳过空列，重复列名自动加后缀。</summary>
    public static Task<HeaderReadResult> ReadAsync(string filePath, int headerRow, CancellationToken cancellationToken = default)
        => Task.Run(() => Read(filePath, headerRow, cancellationToken), cancellationToken);

    /// <summary>读取最后一行行号，用于写入预览。</summary>
    public static Task<int> GetLastRowNumberAsync(string filePath, CancellationToken cancellationToken = default)
        => Task.Run(() => GetLastRowNumber(filePath, cancellationToken), cancellationToken);

    private static HeaderReadResult Read(string filePath, int headerRow, CancellationToken cancellationToken)
    {
        if (!File.Exists(filePath))
        {
            throw new XlsxFileMissingException(filePath);
        }

        using var document = SpreadsheetDocument.Open(filePath, false);
        var workbookPart = document.WorkbookPart ?? throw new XlsxStructureException("表格缺少工作簿结构。");
        var worksheetPart = GetFirstWorksheetPart(workbookPart);
        var sharedStrings = workbookPart.SharedStringTablePart?.SharedStringTable?.Elements<SharedStringItem>()
            .Select(item => item.InnerText)
            .ToList() ?? new List<string>();

        var drafts = new List<HeaderFieldDraft>();
        var usedNames = new HashSet<string>(StringComparer.Ordinal);
        var lastRow = 0;
        var maxColumn = -1;

        using var reader = OpenXmlReader.Create(worksheetPart);
        while (reader.Read())
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (!reader.IsStartElement || reader.ElementType != typeof(Row))
            {
                continue;
            }

            var rowIndex = GetRowIndex(reader) ?? lastRow + 1;
            lastRow = Math.Max(lastRow, rowIndex);
            if (rowIndex != headerRow)
            {
                reader.Skip();
                continue;
            }

            var row = reader.LoadCurrentElement() as Row ?? throw new XlsxStructureException("表头行结构异常。");
            var fallbackColumn = 0;
            foreach (var cell in row.Elements<Cell>())
            {
                cancellationToken.ThrowIfCancellationRequested();
                var column = ParseColumnIndex(cell.CellReference?.Value) ?? fallbackColumn;
                fallbackColumn = column + 1;
                maxColumn = Math.Max(maxColumn, column);

                var text = ReadCellText(cell, sharedStrings).Trim();
                if (text.Length == 0)
                {
                    continue;
                }

                var name = MakeUniqueName(text, usedNames);
                drafts.Add(new HeaderFieldDraft
                {
                    FieldName = name,
                    ColumnIndex = column,
                    SortOrder = drafts.Count,
                    CoreType = GuessCoreType(name)
                });
            }

        }

        return new HeaderReadResult
        {
            Fields = drafts,
            HeaderRow = headerRow,
            MaxColumnIndex = maxColumn,
            LastRowNumber = lastRow
        };
    }

    private static int GetLastRowNumber(string filePath, CancellationToken cancellationToken)
    {
        if (!File.Exists(filePath))
        {
            throw new XlsxFileMissingException(filePath);
        }

        using var document = SpreadsheetDocument.Open(filePath, false);
        var workbookPart = document.WorkbookPart ?? throw new XlsxStructureException("表格缺少工作簿结构。");
        var worksheetPart = GetFirstWorksheetPart(workbookPart);
        var last = 0;
        var count = 0;
        using var reader = OpenXmlReader.Create(worksheetPart);
        while (reader.Read())
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (!reader.IsStartElement || reader.ElementType != typeof(Row))
            {
                continue;
            }

            count++;
            last = Math.Max(last, GetRowIndex(reader) ?? count);
        }
        return last;
    }

    private static WorksheetPart GetFirstWorksheetPart(WorkbookPart workbookPart)
    {
        var sheet = workbookPart.Workbook.Sheets?.Elements<Sheet>().FirstOrDefault()
                    ?? throw new XlsxStructureException("工作簿中没有工作表。");
        return workbookPart.GetPartById(sheet.Id?.Value ?? throw new XlsxStructureException("工作表缺少关系标识。")) as WorksheetPart
               ?? throw new XlsxStructureException("无法打开第一个工作表。");
    }

    private static int? GetRowIndex(OpenXmlReader reader)
    {
        foreach (var attribute in reader.Attributes)
        {
            if (string.Equals(attribute.LocalName, "r", StringComparison.Ordinal)
                && uint.TryParse(attribute.Value, out var value))
            {
                return (int)value;
            }
        }
        return null;
    }

    private static string ReadCellText(Cell cell, IReadOnlyList<string> sharedStrings)
    {
        if (cell.DataType?.Value == CellValues.InlineString)
        {
            return cell.InlineString?.InnerText ?? string.Empty;
        }

        if (cell.DataType?.Value == CellValues.SharedString)
        {
            if (int.TryParse(cell.CellValue?.Text, out var index)
                && index >= 0 && index < sharedStrings.Count)
            {
                return sharedStrings[index];
            }
            return string.Empty;
        }

        return cell.CellValue?.Text ?? cell.InlineString?.InnerText ?? string.Empty;
    }

    /// <summary>解析 A1 引用中的列号。</summary>
    public static int? ParseColumnIndex(string? reference)
    {
        if (string.IsNullOrWhiteSpace(reference))
        {
            return null;
        }

        var index = 0;
        var found = false;
        foreach (var ch in reference)
        {
            if (ch is >= 'A' and <= 'Z')
            {
                index = index * 26 + (ch - 'A' + 1);
                found = true;
            }
            else if (ch is >= 'a' and <= 'z')
            {
                index = index * 26 + (ch - 'a' + 1);
                found = true;
            }
            else
            {
                break;
            }
        }
        return found ? index - 1 : null;
    }

    /// <summary>列号转字母。</summary>
    public static string GetColumnName(int columnIndex)
    {
        var index = columnIndex + 1;
        var chars = new List<char>();
        while (index > 0)
        {
            var remainder = (index - 1) % 26;
            chars.Add((char)('A' + remainder));
            index = (index - 1) / 26;
        }
        chars.Reverse();
        return new string(chars.ToArray());
    }

    private static string MakeUniqueName(string name, HashSet<string> used)
    {
        if (used.Add(name))
        {
            return name;
        }

        var suffix = 2;
        while (!used.Add($"{name}（{suffix}）"))
        {
            suffix++;
        }
        return $"{name}（{suffix}）";
    }

    private static CoreFieldType GuessCoreType(string fieldName)
    {
        var preset = PresetCatalog.Guess(fieldName);
        return preset?.Key switch
        {
            "ticket_no" => CoreFieldType.TicketNo,
            "phone" => CoreFieldType.Phone,
            "address" => CoreFieldType.Address,
            _ => CoreFieldType.None
        };
    }
}
