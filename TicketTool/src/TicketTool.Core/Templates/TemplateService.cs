using Serilog;
using TicketTool.Core.Data;
using TicketTool.Core.Models;
using TicketTool.Core.Parsing;
using TicketTool.Core.Xlsx;

namespace TicketTool.Core.Templates;

/// <summary>模板业务服务：CRUD、默认规则、规则版本与跨模板复用。</summary>
public sealed class TemplateService
{
    private readonly TemplateRepository _repository;
    private readonly ExpressionIndexManager _indexes;
    private readonly ILogger _logger;

    /// <summary>创建服务。</summary>
    public TemplateService(TemplateRepository repository, ExpressionIndexManager indexes, ILogger? logger = null)
    {
        _repository = repository;
        _indexes = indexes;
        _logger = logger ?? Log.ForContext<TemplateService>();
    }

    /// <summary>列出模板。</summary>
    public Task<List<Template>> ListAsync(CancellationToken cancellationToken = default)
        => _repository.ListAsync(cancellationToken);

    /// <summary>读取模板。</summary>
    public Task<Template?> GetAsync(long templateId, CancellationToken cancellationToken = default)
        => _repository.GetAsync(templateId, cancellationToken);

    /// <summary>创建模板并为每个字段生成默认规则。</summary>
    public async Task<Template> CreateAsync(Template template, CancellationToken cancellationToken = default)
    {
        await ValidateAsync(template, null, cancellationToken).ConfigureAwait(false);
        var id = await _repository.CreateAsync(template, cancellationToken).ConfigureAwait(false);
        template.Id = id;
        await EnsureDefaultRulesAsync(template, cancellationToken).ConfigureAwait(false);
        var saved = await _repository.GetAsync(id, cancellationToken).ConfigureAwait(false)
                    ?? throw new InvalidOperationException("模板创建后无法读取。");
        await _indexes.EnsureForTemplateAsync(saved, cancellationToken).ConfigureAwait(false);
        await SaveVersionAsync(saved, cancellationToken).ConfigureAwait(false);
        _logger.Information("TemplateCreated {TemplateId} {Name} {FieldCount}", id, template.Name, saved.Fields.Count);
        return saved;
    }

    /// <summary>更新模板、字段与默认规则。</summary>
    public async Task<Template> UpdateAsync(Template template, CancellationToken cancellationToken = default)
    {
        await ValidateAsync(template, template.Id, cancellationToken).ConfigureAwait(false);
        await _repository.UpdateAsync(template, cancellationToken).ConfigureAwait(false);
        await EnsureDefaultRulesAsync(template, cancellationToken).ConfigureAwait(false);
        var saved = await _repository.GetAsync(template.Id, cancellationToken).ConfigureAwait(false)
                    ?? throw new InvalidOperationException("模板更新后无法读取。");
        await _indexes.EnsureForTemplateAsync(saved, cancellationToken).ConfigureAwait(false);
        await SaveVersionAsync(saved, cancellationToken).ConfigureAwait(false);
        _logger.Information("TemplateUpdated {TemplateId} {FieldCount}", template.Id, saved.Fields.Count);
        return saved;
    }

    /// <summary>删除模板与索引。</summary>
    public async Task DeleteAsync(long templateId, CancellationToken cancellationToken = default)
    {
        await _indexes.DropForTemplateAsync(templateId, cancellationToken).ConfigureAwait(false);
        await _repository.DeleteAsync(templateId, cancellationToken).ConfigureAwait(false);
        _logger.Information("TemplateDeleted {TemplateId}", templateId);
    }

    /// <summary>保存当前规则版本。</summary>
    public async Task SaveVersionAsync(long templateId, CancellationToken cancellationToken = default)
    {
        var template = await _repository.GetAsync(templateId, cancellationToken).ConfigureAwait(false);
        if (template is not null)
        {
            await SaveVersionAsync(template, cancellationToken).ConfigureAwait(false);
        }
    }

    /// <summary>保存当前规则版本。</summary>
    public Task SaveVersionAsync(Template template, CancellationToken cancellationToken = default)
        => _repository.SaveRuleVersionAsync(template.Id, BuildVersionPayload(template), cancellationToken);

    /// <summary>保存整套字段规则，并自动生成前后两个版本以便回退。</summary>
    public async Task SaveRulesAsync(Template template, CancellationToken cancellationToken = default)
    {
        await SaveVersionAsync(template, cancellationToken).ConfigureAwait(false);
        var payload = BuildVersionPayload(template);
        await _repository.ReplaceRulesAsync(template.Id, payload.Fields, cancellationToken).ConfigureAwait(false);
        await SaveVersionAsync(template.Id, cancellationToken).ConfigureAwait(false);
        _logger.Information("RulesSaved {TemplateId} {FieldCount}", template.Id, template.Fields.Count);
    }

    private static RuleVersionPayload BuildVersionPayload(Template template) => new()
    {
        TemplateId = template.Id,
        CreatedAt = DateTimeOffset.Now,
        Fields = template.Fields.Select(field => new RuleVersionField
        {
            FieldName = field.FieldName,
            CoreType = field.CoreType,
            Rules = field.Rules.Select(rule => new RuleVersionRule
            {
                RuleType = rule.RuleType,
                ConfigJson = rule.ConfigJson,
                Priority = rule.Priority,
                Enabled = rule.Enabled
            }).ToList()
        }).ToList()
    };

    /// <summary>一键回退规则到指定版本。</summary>
    public async Task RollbackAsync(long templateId, long versionId, CancellationToken cancellationToken = default)
    {
        var version = await _repository.GetRuleVersionAsync(versionId, cancellationToken).ConfigureAwait(false)
                      ?? throw new InvalidOperationException("规则版本不存在。");
        await SaveVersionAsync(templateId, cancellationToken).ConfigureAwait(false);
        await _repository.ReplaceRulesAsync(templateId, version.Fields, cancellationToken).ConfigureAwait(false);
        await SaveVersionAsync(templateId, cancellationToken).ConfigureAwait(false);
        _logger.Information("RuleRollback {TemplateId} {VersionId}", templateId, versionId);
    }

    /// <summary>列出规则版本。</summary>
    public Task<List<RuleVersionSummary>> ListVersionsAsync(long templateId, CancellationToken cancellationToken = default)
        => _repository.ListRuleVersionsAsync(templateId, cancellationToken);

    /// <summary>从其他模板复制同名字段的规则。</summary>
    public async Task<int> CopyRulesAsync(long sourceTemplateId, long targetTemplateId, CancellationToken cancellationToken = default)
    {
        var count = await _repository.CopyRulesAsync(sourceTemplateId, targetTemplateId, cancellationToken).ConfigureAwait(false);
        await SaveVersionAsync(targetTemplateId, cancellationToken).ConfigureAwait(false);
        _logger.Information("RulesCopied {Source} {Target} {Count}", sourceTemplateId, targetTemplateId, count);
        return count;
    }

    /// <summary>为没有规则的字段生成开箱即用的默认规则。</summary>
    public static FieldRule CreateDefaultRule(TemplateField field)
    {
        var preset = PresetCatalog.Guess(field.FieldName);
        if (preset is not null)
        {
            return new FieldRule
            {
                TemplateFieldId = field.Id,
                RuleType = RuleType.Preset,
                Priority = 0,
                Enabled = true,
                ConfigJson = RuleConfigSerializer.Serialize(new PresetRuleConfig
                {
                    PresetKey = preset.Key,
                    ExtraAliases = new List<string> { field.FieldName }
                })
            };
        }

        return new FieldRule
        {
            TemplateFieldId = field.Id,
            RuleType = RuleType.Anchor,
            Priority = 0,
            Enabled = true,
            ConfigJson = RuleConfigSerializer.Serialize(new AnchorRuleConfig
            {
                Aliases = new List<string> { field.FieldName }
            })
        };
    }

    private async Task EnsureDefaultRulesAsync(Template template, CancellationToken cancellationToken)
    {
        foreach (var field in template.Fields.Where(f => f.Rules.Count == 0))
        {
            var rule = CreateDefaultRule(field);
            var ruleId = await _repository.AddRuleAsync(rule, cancellationToken).ConfigureAwait(false);
            rule.Id = ruleId;
            field.Rules.Add(rule);
        }
    }

    private static Task ValidateAsync(Template template, long? existingId, CancellationToken cancellationToken)
    {
        _ = cancellationToken;
        if (string.IsNullOrWhiteSpace(template.Name))
        {
            throw new InvalidOperationException("模板名称不能为空。");
        }

        if (string.IsNullOrWhiteSpace(template.FilePath) || !File.Exists(template.FilePath))
        {
            throw new XlsxFileMissingException(template.FilePath);
        }

        if (template.HeaderRow < 1)
        {
            throw new InvalidOperationException("表头行号必须大于 0。");
        }

        var duplicate = template.Fields
            .GroupBy(f => f.FieldName.Trim(), StringComparer.Ordinal)
            .FirstOrDefault(g => g.Count() > 1);
        if (duplicate is not null)
        {
            throw new InvalidOperationException($"字段名“{duplicate.Key}”重复。");
        }

        if (template.Fields.Any(f => string.IsNullOrWhiteSpace(f.FieldName)))
        {
            throw new InvalidOperationException("字段名不能为空。");
        }

        _ = existingId;
        return Task.CompletedTask;
    }
}
