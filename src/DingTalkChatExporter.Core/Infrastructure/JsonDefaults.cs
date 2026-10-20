using System.Text.Json;
using System.Text.Json.Serialization;

namespace DingTalkChatExporter.Core.Infrastructure;

public static class JsonDefaults
{
    public static JsonSerializerOptions Create(bool indented = true)
    {
        var options = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = true,
            WriteIndented = indented,
            DefaultIgnoreCondition = JsonIgnoreCondition.Never
        };
        options.Converters.Add(new JsonStringEnumConverter());
        return options;
    }

    public static JsonSerializerOptions Indented { get; } = Create(true);

    public static JsonSerializerOptions Compact { get; } = Create(false);
}
