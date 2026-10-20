using DingTalkChatExporter.Core.Models;

namespace DingTalkChatExporter.Export;

public enum ExportWriteMode
{
    Append,
    Overwrite
}

public sealed record ExportRow(
    RawMessage Message,
    IReadOnlyDictionary<string, ExtractionResult> Fields);

public sealed record ExportOptions(
    string TemplatePath,
    string OutputPath,
    string? SheetName,
    ExportWriteMode Mode,
    IReadOnlyList<string> FieldNames,
    bool HighlightLowConfidence,
    bool BackupExisting,
    string SourceName,
    DateTimeOffset? FetchedAt,
    int RawCount,
    int DedupedCount,
    string RunId);

public sealed record ExportPreflight(
    string SheetName,
    int StartRow,
    int ExistingRowsToOverwrite,
    int RowsToWrite,
    IReadOnlyList<string> UnmappedFields,
    string Description);

public sealed record ExportResult(
    string OutputPath,
    string SheetName,
    int StartRow,
    int WrittenRows,
    int LowConfidenceCells,
    string? BackupPath,
    TimeSpan Duration);
