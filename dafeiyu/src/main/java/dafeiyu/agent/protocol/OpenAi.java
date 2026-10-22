package dafeiyu.agent.protocol;

import com.google.gson.JsonObject;
import dafeiyu.agent.AgentException;

import java.net.http.HttpRequest;
import java.util.List;

final class OpenAi {

    static final int PROMPT_CACHE_KEY_MAX = 64;

    private OpenAi() {}

    static HttpRequest.Builder auth(HttpRequest.Builder builder, String apiKey) {
        return apiKey == null || apiKey.isBlank() ? builder : builder.header("Authorization", "Bearer " + apiKey.trim());
    }

    static List<String> listModels(String baseUrl, String apiKey) throws AgentException {
        JsonObject json = HttpJson.send(auth(HttpJson.get(HttpJson.uri(HttpJson.withDefaultVersion(baseUrl), "/models")), apiKey).build());
        return HttpJson.modelIds(json);
    }
}
