using DingTalkChatExporter.Core.Services;

namespace DingTalkChatExporter.Tests;

public sealed class IndexStateHasherTests : IDisposable
{
    private readonly string _directory = Path.Combine(
        Path.GetTempPath(),
        "dtce-hash-" + Guid.NewGuid().ToString("N"));

    public IndexStateHasherTests()
    {
        Directory.CreateDirectory(_directory);
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, recursive: true);
        }
    }

    [Fact]
    public void TryCompute_MissingFile_ReturnsNull()
    {
        Assert.Null(IndexStateHasher.TryCompute(Path.Combine(_directory, "absent.xlsx")));
    }

    [Fact]
    public void TryCompute_ReturnsStableValueForUnchangedFile()
    {
        var path = Path.Combine(_directory, "table.xlsx");
        File.WriteAllText(path, "one");

        Assert.Equal(IndexStateHasher.TryCompute(path), IndexStateHasher.TryCompute(path));
    }

    [Fact]
    public void TryCompute_ChangesAfterFileContentChanges()
    {
        var path = Path.Combine(_directory, "table.xlsx");
        File.WriteAllText(path, "one");
        var first = IndexStateHasher.TryCompute(path);

        File.WriteAllText(path, "two, longer content");
        var second = IndexStateHasher.TryCompute(path);

        Assert.NotEqual(first, second);
    }
}
