package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.time.LocalDate;

final class WebSearchTool implements Tool {

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "query": {"type": "string", "description": "Search query"},
              "numResults": {"type": "number", "description": "Number of search results to return (default: 8, maximum: 15)"}
            }, "required": ["query"]}""");

    @Override
    public String name() {
        return "websearch";
    }

    @Override
    public String description() {
        return "Search the web and return the top results with title, URL and a snippet of each page.\n\n"
                + "Use this for anything you are not sure about or that may have changed: news, recent events, prices, versions, "
                + "release dates, documentation, how-tos, unfamiliar names and slang. The current date is " + LocalDate.now()
                + ", so include the year when searching for recent information. Chinese queries search Chinese sites, English queries search "
                + "the wider web. Snippets are short; use the webfetch tool on a result URL to read the full page before relying on details.";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    public String run(JsonObject args, ToolContext context) throws ToolFailure {
        String query = Args.string(args, "query");
        Integer count = Args.optInt(args, "numResults");
        WebSearch.Results results = WebSearch.search(query, count == null || count <= 0 ? WebSearch.DEFAULT_RESULTS : count);
        return WebSearch.render(query.strip(), results);
    }
}
