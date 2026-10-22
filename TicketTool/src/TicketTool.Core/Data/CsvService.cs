using System.Text;
using TicketTool.Core.Models;

namespace TicketTool.Core.Data;

/// <summary>CSV / TSV 兼容导入导出。首行视为字段名。</summary>
public sealed class CsvService
{
    /// <summary>导入预览。</summary>
    public sealed class ImportPreview
    {
        /// <summary>文件头。</summary>
        public List<string> Headers { get; set; } = new();

        /// <summary>无法匹配到模板字段的表头。</summary>
        public List<string> UnmappedHeaders { get; set; } = new();

        /// <summary>已映射的模板字段。</summary>
        public List<string> MappedFields { get; set; } = new();

        /// <summary>数据行。</summary>
        public List<Dictionary<string, string>> Rows { get; set; } = new();

        /// <summary>使用的分隔符。</summary>
        public char Delimiter { get; set; } = ',';
    }

    /// <summary>读取并预览 CSV/TSV。</summary>
    public async Task<ImportPreview> PreviewAsync(string filePath, Template template, CancellationToken cancellationToken = default)
    {
        var text = await File.ReadAllTextAsync(filePath, Encoding.UTF8, cancellationToken).ConfigureAwait(false);
        var delimiter = DetectDelimiter(text);
        var table = Parse(text, delimiter);
        var preview = new ImportPreview { Delimiter = delimiter };
        if (table.Count == 0)
        {
            return preview;
        }

        preview.Headers = table[0].Select(value => value.Trim()).ToList();
        var fieldByName = template.Fields.ToDictionary(f => f.FieldName, f => f, StringComparer.Ordinal);
        var mapped = new List<int>();
        for (var i = 0; i < preview.Headers.Count; i++)
        {
            if (fieldByName.ContainsKey(preview.Headers[i]))
            {
                mapped.Add(i);
                preview.MappedFields.Add(preview.Headers[i]);
            }
            else if (!string.IsNullOrWhiteSpace(preview.Headers[i]))
            {
                preview.UnmappedHeaders.Add(preview.Headers[i]);
            }
        }

        for (var rowIndex = 1; rowIndex < table.Count; rowIndex++)
        {
            var row = table[rowIndex];
            if (row.All(string.IsNullOrWhiteSpace))
            {
                continue;
            }

            var values = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var index in mapped)
            {
                values[preview.Headers[index]] = index < row.Count ? row[index].Trim() : string.Empty;
            }
            preview.Rows.Add(values);
        }

        return preview;
    }

    /// <summary>将导入行转换为待入库工单，并重建原文与偏移。</summary>
    public static TicketInsert ToTicket(Template template, IReadOnlyDictionary<string, string> row)
    {
        var builder = new StringBuilder();
        var spans = new List<FieldSpan>();
        var values = new Dictionary<string, string?>(StringComparer.Ordinal);
        foreach (var field in template.Fields.OrderBy(f => f.SortOrder).ThenBy(f => f.Id))
        {
            var value = row.TryGetValue(field.FieldName, out var found) ? found : string.Empty;
            values[field.FieldName] = value;
            if (value.Length == 0)
            {
                continue;
            }

            builder.Append(field.FieldName).Append('：');
            var start = builder.Length;
            builder.Append(value);
            spans.Add(new FieldSpan
            {
                FieldName = field.FieldName,
                StartOffset = start,
                EndOffset = start + value.Length
            });
            builder.Append('\n');
        }

        return new TicketInsert
        {
            TemplateId = template.Id,
            Values = values,
            RawText = builder.ToString(),
            Spans = spans,
            CreatedAt = DateTimeOffset.Now
        };
    }

    /// <summary>导出工单字段值。</summary>
    public async Task ExportAsync(
        string filePath,
        Template template,
        IReadOnlyList<IReadOnlyDictionary<string, string?>> rows,
        bool tabSeparated,
        CancellationToken cancellationToken = default)
    {
        var delimiter = tabSeparated ? '\t' : ',';
        var builder = new StringBuilder();
        builder.AppendLine(string.Join(delimiter, template.Fields
            .OrderBy(f => f.SortOrder)
            .Select(f => Escape(f.FieldName, delimiter))));
        foreach (var row in rows)
        {
            cancellationToken.ThrowIfCancellationRequested();
            builder.AppendLine(string.Join(delimiter, template.Fields
                .OrderBy(f => f.SortOrder)
                .Select(f => Escape(row.TryGetValue(f.FieldName, out var value) ? value ?? string.Empty : string.Empty, delimiter))));
        }

        await File.WriteAllTextAsync(filePath, builder.ToString(), new UTF8Encoding(true), cancellationToken).ConfigureAwait(false);
    }

    private static char DetectDelimiter(string text)
    {
        var firstLine = text.Split('\n', 2)[0];
        var tabs = firstLine.Count(ch => ch == '\t');
        var commas = firstLine.Count(ch => ch == ',');
        return tabs > commas ? '\t' : ',';
    }

    private static List<List<string>> Parse(string text, char delimiter)
    {
        var rows = new List<List<string>>();
        var row = new List<string>();
        var field = new StringBuilder();
        var inQuotes = false;

        for (var i = 0; i < text.Length; i++)
        {
            var ch = text[i];
            if (inQuotes)
            {
                if (ch == '"')
                {
                    if (i + 1 < text.Length && text[i + 1] == '"')
                    {
                        field.Append('"');
                        i++;
                    }
                    else
                    {
                        inQuotes = false;
                    }
                }
                else
                {
                    field.Append(ch);
                }
                continue;
            }

            if (ch == '"' && field.Length == 0)
            {
                inQuotes = true;
            }
            else if (ch == delimiter)
            {
                row.Add(field.ToString());
                field.Clear();
            }
            else if (ch == '\r')
            {
                if (i + 1 < text.Length && text[i + 1] == '\n')
                {
                    i++;
                }
                FinishRow();
            }
            else if (ch == '\n')
            {
                FinishRow();
            }
            else
            {
                field.Append(ch);
            }
        }

        if (field.Length > 0 || row.Count > 0)
        {
            row.Add(field.ToString());
            rows.Add(row);
        }

        return rows;

        void FinishRow()
        {
            row.Add(field.ToString());
            field.Clear();
            rows.Add(row);
            row = new List<string>();
        }
    }

    private static string Escape(string value, char delimiter)
    {
        if (value.Contains('"')
            || value.Contains(delimiter)
            || value.Contains('\n')
            || value.Contains('\r'))
        {
            return "\"" + value.Replace("\"", "\"\"", StringComparison.Ordinal) + "\"";
        }
        return value;
    }
}
