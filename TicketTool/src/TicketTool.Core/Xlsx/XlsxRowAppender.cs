using System.Diagnostics;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Spreadsheet;
using Serilog;
using TicketTool.Core.Models;

namespace TicketTool.Core.Xlsx;

/// <summary>写入目标表格的一个单元格值。</summary>
public sealed record XlsxCellValue(int ColumnIndex, string? Value);

/// <summary>
/// SAX 流式 XLSX 追加器：逐行读取旧表、写入临时文件、File.Replace 原子替换。
/// 任一时刻内存中只保留单行对象。
/// </summary>
public sealed class XlsxRowAppender
{
    private readonly ILogger _logger;

    /// <summary>创建追加器。</summary>
    public XlsxRowAppender(ILogger? logger = null)
    {
        _logger = logger ?? Log.ForContext<XlsxRowAppender>();
    }

    /// <summary>计算下一次写入的行号与列值预览。</summary>
    public async Task<XlsxWritePreview> BuildPreviewAsync(
        string filePath,
        IReadOnlyList<XlsxCellValue> cells,
        IReadOnlyList<TemplateField> fields,
        CancellationToken cancellationToken = default)
    {
        if (!File.Exists(filePath))
        {
            throw new XlsxFileMissingException(filePath);
        }

        var lastRow = await HeaderReader.GetLastRowNumberAsync(filePath, cancellationToken).ConfigureAwait(false);
        var cellsByColumn = cells.ToDictionary(c => c.ColumnIndex, c => c.Value);
        return new XlsxWritePreview
        {
            FilePath = filePath,
            RowNumber = lastRow + 1,
            Cells = fields
                .OrderBy(f => f.ColumnIndex)
                .Select(f => (f.ColumnIndex, f.FieldName, cellsByColumn.TryGetValue(f.ColumnIndex, out var value) ? value : null))
                .ToList()
        };
    }

    /// <summary>追加一行并原子替换目标文件。</summary>
    public async Task<XlsxAppendResult> AppendRowAsync(
        string filePath,
        IReadOnlyList<XlsxCellValue> cells,
        CancellationToken cancellationToken = default,
        int headerRow = 0)
    {
        if (!File.Exists(filePath))
        {
            throw new XlsxFileMissingException(filePath);
        }

        if (!FileLockDetector.IsWritable(filePath))
        {
            throw new XlsxFileLockedException(filePath);
        }

        var stopwatch = Stopwatch.StartNew();
        var directory = Path.GetDirectoryName(Path.GetFullPath(filePath))
                        ?? throw new XlsxStructureException("无法确定表格所在目录。");
        var tempPath = Path.Combine(directory, $".{Path.GetFileName(filePath)}.{Guid.NewGuid():N}.tmp");
        var backupPath = filePath + ".backup";

        try
        {
            File.Copy(filePath, tempPath, true);
            cancellationToken.ThrowIfCancellationRequested();
            var result = await Task.Run(() => AppendToFile(tempPath, cells, headerRow, cancellationToken), cancellationToken).ConfigureAwait(false);

            cancellationToken.ThrowIfCancellationRequested();
            File.Replace(tempPath, filePath, backupPath, true);
            stopwatch.Stop();
            result.Elapsed = stopwatch.Elapsed;
            _logger.Information(
                "XlsxAppend {Path} {Row} {ElapsedMs}",
                filePath,
                result.RowNumber,
                stopwatch.Elapsed.TotalMilliseconds);
            return result;
        }
        finally
        {
            TryDelete(tempPath);
        }
    }

    private XlsxAppendResult AppendToFile(
        string tempPath,
        IReadOnlyList<XlsxCellValue> cells,
        int headerRow,
        CancellationToken cancellationToken)
    {
        var sourcePath = tempPath + ".source.xlsx";
        File.Copy(tempPath, sourcePath, true);
        try
        {
            using var sourceDocument = SpreadsheetDocument.Open(sourcePath, false);
            var sourceWorkbook = sourceDocument.WorkbookPart ?? throw new XlsxStructureException("表格缺少工作簿结构。");
            var sourceWorksheet = GetFirstWorksheetPart(sourceWorkbook);

            using var targetDocument = SpreadsheetDocument.Open(tempPath, true);
            var targetWorkbook = targetDocument.WorkbookPart ?? throw new XlsxStructureException("表格缺少工作簿结构。");
            var targetWorksheet = GetFirstWorksheetPart(targetWorkbook);

            var columnStyles = new Dictionary<int, uint>();
            var insertedRow = 0;
            using (var reader = OpenXmlReader.Create(sourceWorksheet))
            using (var writer = OpenXmlWriter.Create(targetWorksheet))
            {
                var rootStarted = false;
                while (reader.Read())
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    if (reader.IsStartElement && reader.ElementType == typeof(Worksheet))
                    {
                        writer.WriteStartElement(new Worksheet());
                        rootStarted = true;
                        continue;
                    }

                    if (reader.IsEndElement && reader.ElementType == typeof(Worksheet))
                    {
                        writer.WriteEndElement();
                        break;
                    }

                    if (!rootStarted || !reader.IsStartElement)
                    {
                        continue;
                    }

                    if (reader.ElementType == typeof(Columns))
                    {
                        var columns = reader.LoadCurrentElement() as Columns;
                        if (columns is not null)
                        {
                            CollectColumnStyles(columns, columnStyles);
                            writer.WriteElement(columns);
                        }
                        continue;
                    }

                    if (reader.ElementType == typeof(SheetData))
                    {
                        writer.WriteStartElement(new SheetData());
                        var copied = CopyRows(reader, writer, cancellationToken);
                        insertedRow = copied.MaxRow + 1;

                        var styleSource = copied.LastRowFormat;
                        if (headerRow > 0 && insertedRow - 1 <= headerRow)
                        {
                            styleSource = null;
                        }
                        else if (headerRow <= 0 && copied.RowCount <= 1)
                        {
                            styleSource = null;
                        }

                        writer.WriteElement(BuildRow(insertedRow, cells, styleSource, columnStyles));
                        writer.WriteEndElement();
                    }
                    else
                    {
                        writer.WriteElement(reader.LoadCurrentElement() ?? throw new XlsxStructureException("工作表结构异常。"));
                    }
                }
            }

            ExpandTableRanges(targetWorkbook, insertedRow);

            return new XlsxAppendResult
            {
                RowNumber = insertedRow,
                Cells = cells.ToDictionary(c => c.ColumnIndex, c => c.Value)
            };
        }
        finally
        {
            TryDelete(sourcePath);
        }
    }

    private sealed record RowFormat(
        uint? RowStyleIndex,
        double? Height,
        bool CustomHeight,
        Dictionary<int, uint> CellStyles);

    private sealed record RowCopyResult(int MaxRow, int RowCount, RowFormat? LastRowFormat);

    private static RowCopyResult CopyRows(OpenXmlReader reader, OpenXmlWriter writer, CancellationToken cancellationToken)
    {
        var maxRow = 0;
        var rowCount = 0;
        RowFormat? lastRowFormat = null;

        while (reader.Read())
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (reader.IsEndElement && reader.ElementType == typeof(SheetData))
            {
                break;
            }

            if (!reader.IsStartElement || reader.ElementType != typeof(Row))
            {
                continue;
            }

            var row = reader.LoadCurrentElement() as Row ?? throw new XlsxStructureException("数据行结构异常。");
            var rowIndex = row.RowIndex?.Value ?? (uint)(maxRow + 1);
            maxRow = Math.Max(maxRow, (int)rowIndex);
            rowCount++;

            var cellStyles = new Dictionary<int, uint>();
            var fallbackColumn = 0;
            foreach (var cell in row.Elements<Cell>())
            {
                var column = HeaderReader.ParseColumnIndex(cell.CellReference?.Value) ?? fallbackColumn;
                fallbackColumn = column + 1;
                if (cell.StyleIndex?.Value is uint styleIndex)
                {
                    cellStyles[column] = styleIndex;
                }
            }

            lastRowFormat = new RowFormat(
                row.StyleIndex?.Value,
                row.Height?.Value,
                row.CustomHeight?.Value ?? false,
                cellStyles);

            writer.WriteElement(row);
        }

        return new RowCopyResult(maxRow, rowCount, lastRowFormat);
    }

    private static void CollectColumnStyles(Columns columns, Dictionary<int, uint> columnStyles)
    {
        foreach (var column in columns.Elements<Column>())
        {
            if (column.Style?.Value is not uint style)
            {
                continue;
            }

            var min = (int)(column.Min?.Value ?? 0);
            var max = (int)(column.Max?.Value ?? (uint)min);
            if (min <= 0 || max < min)
            {
                continue;
            }

            for (var index = min; index <= max && index - min < 16384; index++)
            {
                columnStyles[index - 1] = style;
            }
        }
    }

    private static Row BuildRow(
        int rowNumber,
        IReadOnlyList<XlsxCellValue> cells,
        RowFormat? format,
        IReadOnlyDictionary<int, uint> columnStyles)
    {
        var row = new Row { RowIndex = (uint)rowNumber };
        if (format?.RowStyleIndex is uint rowStyle)
        {
            row.StyleIndex = rowStyle;
        }
        if (format?.Height is double height)
        {
            row.Height = height;
            row.CustomHeight = true;
        }

        var values = cells.ToDictionary(c => c.ColumnIndex, c => c.Value);
        var columns = new HashSet<int>(values.Keys);
        if (format is not null)
        {
            foreach (var column in format.CellStyles.Keys)
            {
                columns.Add(column);
            }
        }

        foreach (var column in columns.OrderBy(c => c))
        {
            uint? style = null;
            if (format is not null && format.CellStyles.TryGetValue(column, out var sourceStyle))
            {
                style = sourceStyle;
            }
            style ??= columnStyles.TryGetValue(column, out var columnStyle) ? columnStyle : null;

            var hasValue = values.TryGetValue(column, out var value) && !string.IsNullOrEmpty(value);
            if (!hasValue && style is null)
            {
                continue;
            }

            var cell = new Cell
            {
                CellReference = HeaderReader.GetColumnName(column) + rowNumber.ToString(System.Globalization.CultureInfo.InvariantCulture)
            };
            if (style is uint cellStyle)
            {
                cell.StyleIndex = cellStyle;
            }
            if (hasValue)
            {
                cell.DataType = CellValues.InlineString;
                cell.InlineString = new InlineString(new Text(value!) { Space = SpaceProcessingModeValues.Preserve });
            }
            row.Append(cell);
        }

        return row;
    }

    private static void ExpandTableRanges(WorkbookPart workbookPart, int insertedRow)
    {
        var worksheetPart = GetFirstWorksheetPart(workbookPart);
        foreach (var tablePart in worksheetPart.TableDefinitionParts)
        {
            var table = tablePart.Table;
            if (table is null)
            {
                continue;
            }

            if ((table.TotalsRowCount?.Value ?? 0) > 0 || table.TotalsRowShown?.Value == true)
            {
                continue;
            }

            if (!TryParseRange(table.Reference?.Value, out var startRow, out var endRow, out var startColumn, out var endColumn))
            {
                continue;
            }

            if (endRow != insertedRow - 1)
            {
                continue;
            }

            table.Reference = new StringValue(
                $"{HeaderReader.GetColumnName(startColumn)}{startRow}:{HeaderReader.GetColumnName(endColumn)}{insertedRow}");

            var autoFilter = table.GetFirstChild<AutoFilter>();
            if (autoFilter is not null
                && TryParseRange(autoFilter.Reference?.Value, out var filterStartRow, out var filterEndRow, out var filterStartColumn, out var filterEndColumn)
                && filterEndRow == endRow)
            {
                autoFilter.Reference = new StringValue(
                    $"{HeaderReader.GetColumnName(filterStartColumn)}{filterStartRow}:{HeaderReader.GetColumnName(filterEndColumn)}{insertedRow}");
            }

            table.Save();
        }
    }

    private static bool TryParseRange(string? reference, out int startRow, out int endRow, out int startColumn, out int endColumn)
    {
        startRow = 0;
        endRow = 0;
        startColumn = 0;
        endColumn = 0;
        if (string.IsNullOrWhiteSpace(reference))
        {
            return false;
        }

        var parts = reference.Split(':');
        if (parts.Length != 2)
        {
            return false;
        }

        if (!TryParseCell(parts[0], out startRow, out startColumn)
            || !TryParseCell(parts[1], out endRow, out endColumn))
        {
            return false;
        }

        return startRow > 0 && endRow >= startRow && endColumn >= startColumn;
    }

    private static bool TryParseCell(string text, out int row, out int column)
    {
        row = 0;
        column = 0;
        var value = text.Replace("$", string.Empty, StringComparison.Ordinal).Trim();
        var index = 0;
        var columnNumber = 0;
        while (index < value.Length && char.IsLetter(value[index]))
        {
            columnNumber = columnNumber * 26 + (char.ToUpperInvariant(value[index]) - 'A' + 1);
            index++;
        }

        if (columnNumber == 0 || index >= value.Length)
        {
            return false;
        }

        if (!int.TryParse(value[index..], System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out row)
            || row <= 0)
        {
            return false;
        }

        column = columnNumber - 1;
        return true;
    }

    private static WorksheetPart GetFirstWorksheetPart(WorkbookPart workbookPart)
    {
        var sheet = workbookPart.Workbook.Sheets?.Elements<Sheet>().FirstOrDefault()
                    ?? throw new XlsxStructureException("工作簿中没有工作表。");
        return workbookPart.GetPartById(sheet.Id?.Value ?? throw new XlsxStructureException("工作表缺少关系标识。")) as WorksheetPart
               ?? throw new XlsxStructureException("无法打开第一个工作表。");
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (IOException)
        {
            // 临时文件清理失败不影响主流程，操作系统后续会回收。
        }
    }
}
