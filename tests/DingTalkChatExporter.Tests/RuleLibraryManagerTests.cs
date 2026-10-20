using DingTalkChatExporter.Core.Models;
using DingTalkChatExporter.Core.Services;

namespace DingTalkChatExporter.Tests;

public sealed class RuleLibraryManagerTests : IDisposable
{
    private readonly string _directory;

    public RuleLibraryManagerTests()
    {
        _directory = Path.Combine(Path.GetTempPath(), "dtce-rule-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_directory);
    }

    [Fact]
    public void CreateAndSave_PersistsOneFilePerLibrary()
    {
        var manager = new RuleLibraryManager(_directory);
        var library = manager.Create("采购规则");

        Assert.True(File.Exists(manager.GetLibraryPath(library.Id)));
        var loaded = manager.LoadAll();
        Assert.Single(loaded);
        Assert.Equal("采购规则", loaded[0].Name);
    }

    [Fact]
    public void Save_KeepsPreviousVersionAndBackupFile()
    {
        var manager = new RuleLibraryManager(_directory);
        var library = manager.Create("版本库");
        library = manager.Save(library with
        {
            Rules = new List<ExtractionRule> { new("A", "regex", "v1") }
        });

        library = manager.Save(library with
        {
            Rules = new List<ExtractionRule> { new("A", "regex", "v2") }
        });

        Assert.Equal(2, library.Versions.Count);
        Assert.Equal("v1", library.Versions[^1].Rules[0].Pattern);
        Assert.NotEmpty(Directory.EnumerateFiles(_directory, "rules-*.bak.json"));
    }

    [Fact]
    public void Duplicate_IsIndependentFromSource()
    {
        var manager = new RuleLibraryManager(_directory);
        var source = manager.Create("源库");
        source = manager.Save(source with
        {
            Rules = new List<ExtractionRule> { new("Field", "regex", "source") }
        });

        var copy = manager.Duplicate(source.Id, "副本库");
        manager.Save(copy with
        {
            Rules = new List<ExtractionRule> { new("Field", "regex", "changed") }
        });

        var sourceReloaded = manager.Load(source.Id)!;
        var copyReloaded = manager.Load(copy.Id)!;

        Assert.Equal("source", sourceReloaded.Rules[0].Pattern);
        Assert.Equal("changed", copyReloaded.Rules[0].Pattern);
        Assert.False(copyReloaded.IsDefault);
    }

    [Fact]
    public void Delete_RemovesFromListButKeepsFileInTrash()
    {
        var manager = new RuleLibraryManager(_directory);
        var library = manager.Create("待删除库");
        var path = manager.GetLibraryPath(library.Id);

        var deleted = manager.Delete(library.Id);

        Assert.True(deleted);
        Assert.False(File.Exists(path));
        Assert.Empty(manager.LoadAll());
        Assert.NotEmpty(Directory.EnumerateFiles(Path.Combine(_directory, ".trash"), "*.json"));
    }

    [Fact]
    public void SetDefault_LeavesExactlyOneDefault()
    {
        var manager = new RuleLibraryManager(_directory);
        var first = manager.Create("A");
        var second = manager.Create("B");

        manager.SetDefault(second.Id);

        Assert.False(manager.Load(first.Id)!.IsDefault);
        Assert.True(manager.Load(second.Id)!.IsDefault);
    }

    [Fact]
    public void RestoreVersion_RestoresRulesAndBacksUpCurrent()
    {
        var manager = new RuleLibraryManager(_directory);
        var library = manager.Create("可恢复库");
        library = manager.Save(library with
        {
            Rules = new List<ExtractionRule> { new("Field", "regex", "old") }
        });
        library = manager.Save(library with
        {
            Rules = new List<ExtractionRule> { new("Field", "regex", "new") }
        });
        Assert.Equal("new", library.Rules[0].Pattern);
        var versionId = library.Versions.Last(v => v.Rules.Count == 1 && v.Rules[0].Pattern == "old").VersionId;

        var restored = manager.RestoreVersion(library.Id, versionId)!;

        Assert.Equal("old", restored.Rules[0].Pattern);
    }

    [Fact]
    public void LoadAll_SkipsCorruptFileAndRecordsError()
    {
        var manager = new RuleLibraryManager(_directory);
        manager.Create("正常库");
        File.WriteAllText(Path.Combine(_directory, "broken.json"), "{ not-valid json");

        var loaded = manager.LoadAll();

        Assert.Single(loaded);
        Assert.Contains(manager.LoadErrors, error => error.Contains("损坏"));
    }

    [Fact]
    public void SkeletonFromHeaders_ProducesEmptyRegexRules()
    {
        var rules = TemplateRuleSkeletonBuilder.FromHeaders(new[] { "订单号", "金额" });

        Assert.Equal(2, rules.Count);
        Assert.All(rules, rule =>
        {
            Assert.Equal("regex", rule.MatchType);
            Assert.Equal(string.Empty, rule.Pattern);
            Assert.Equal(1, rule.GroupIndex);
        });
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_directory))
            {
                Directory.Delete(_directory, recursive: true);
            }
        }
        catch (IOException)
        {
        }
    }
}
