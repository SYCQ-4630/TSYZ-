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
        CancellationToken cancellationToken = default)
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
            var result = await Task.Run(() => AppendToFile(tempPath, cells, cancellationToken), cancellationToken).ConfigureAwait(false);

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

    private XlsxAppendResult AppendToFile(string tempPath, IReadOnlyList<XlsxCellValue> cells, CancellationToken cancellationToken)
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

                    if (reader.ElementType == typeof(SheetData))
                    {
                        writer.WriteStartElement(new SheetData());
                        var maxRow = CopyRows(reader, writer, cancellationToken);
                        insertedRow = maxRow + 1;
                        writer.WriteElement(BuildRow(insertedRow, cells));
                        writer.WriteEndElement();
                    }
                    else
                    {
                        writer.WriteElement(reader.LoadCurrentElement() ?? throw new XlsxStructureException("工作表结构异常。"));
                    }
                }
            }

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

    private static int CopyRows(OpenXmlReader reader, OpenXmlWriter writer, CancellationToken cancellationToken)
    {
        var maxRow = 0;
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
            writer.WriteElement(row);
        }
        return maxRow;
    }

    private static Row BuildRow(int rowNumber, IReadOnlyList<XlsxCellValue> cells)
    {
        var row = new Row { RowIndex = (uint)rowNumber };
        foreach (var cellValue in cells.OrderBy(c => c.ColumnIndex))
        {
            if (string.IsNullOrEmpty(cellValue.Value))
            {
                continue;
            }

            var text = new Text(cellValue.Value) { Space = SpaceProcessingModeValues.Preserve };
            row.Append(new Cell
            {
                CellReference = HeaderReader.GetColumnName(cellValue.ColumnIndex) + rowNumber.ToString(System.Globalization.CultureInfo.InvariantCulture),
                DataType = CellValues.InlineString,
                InlineString = new InlineString(text)
            });
        }
        return row;
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
