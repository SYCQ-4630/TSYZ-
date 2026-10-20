using System.Text.Json;
using DingTalkChatExporter.Core.Infrastructure;

namespace DingTalkChatExporter.Tests;

public sealed class JsonFileStoreTests : IDisposable
{
    private sealed record Sample
    {
        public string Name { get; init; } = string.Empty;
        public int Count { get; init; }
    }

    private readonly string _directory = Path.Combine(
        Path.GetTempPath(),
        "dtce-tests-" + Guid.NewGuid().ToString("N"));

    public JsonFileStoreTests()
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
    public void Load_MissingFile_ReturnsDefaults()
    {
        var path = Path.Combine(_directory, "missing.json");
        var store = new JsonFileStore<Sample>(path, () => new Sample { Name = "default" });

        var value = store.Load();

        Assert.Equal("default", value.Name);
        Assert.False(File.Exists(path));
    }

    [Fact]
    public void SaveAndLoad_RoundTripsValues()
    {
        var path = Path.Combine(_directory, "settings.json");
        var store = new JsonFileStore<Sample>(path, () => new Sample());
        store.Save(new Sample { Name = "规则", Count = 3 });
        var reloaded = new JsonFileStore<Sample>(path, () => new Sample()).Load();

        Assert.Equal("规则", reloaded.Name);
        Assert.Equal(3, reloaded.Count);
    }

    [Fact]
    public void Load_CorruptFile_IsQuarantinedAndDefaultsReturned()
    {
        var path = Path.Combine(_directory, "broken.json");
        File.WriteAllText(path, "{ not valid json");
        var store = new JsonFileStore<Sample>(path, () => new Sample { Name = "ok" });

        var value = store.Load();

        Assert.Equal("ok", value.Name);
        Assert.NotNull(store.LastWarning);
        Assert.False(File.Exists(path));
        var quarantined = Directory.GetFiles(_directory, "broken.json.corrupt-*");
        Assert.Single(quarantined);
        Assert.Equal("{ not valid json", File.ReadAllText(quarantined[0]));
    }

    [Fact]
    public void Load_EmptyFile_ReturnsDefaults()
    {
        var path = Path.Combine(_directory, "empty.json");
        File.WriteAllText(path, "   ");
        var store = new JsonFileStore<Sample>(path, () => new Sample { Name = "fallback" });

        Assert.Equal("fallback", store.Load().Name);
    }

    [Fact]
    public void Save_UsesAtomicFileAndLeavesNoTempFiles()
    {
        var path = Path.Combine(_directory, "atomic.json");
        var store = new JsonFileStore<Sample>(path, () => new Sample());

        store.Save(new Sample { Name = "one" });
        store.Save(new Sample { Name = "two" });

        Assert.Equal("two", JsonSerializer.Deserialize<Sample>(File.ReadAllText(path), JsonDefaults.Indented)!.Name);
        Assert.Empty(Directory.GetFiles(_directory, ".*.tmp"));
    }
}
