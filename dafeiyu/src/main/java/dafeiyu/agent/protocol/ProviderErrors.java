package dafeiyu.agent.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.AgentException.Kind;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;

public final class ProviderErrors {

    private static final List<Pattern> CONTEXT_OVERFLOW = compile(
            "prompt is too long",
            "input is too long for requested model",
            "exceeded this model context window limit",
            "exceeds the context window",
            "exceeds (?:the )?(?:model'?s )?maximum context length(?: of [\\d,]+ tokens?|\\s*\\([\\d,]+\\))",
            "input token count.*exceeds the maximum",
            "number of input tokens exceeds maximum length",
            "tokens in request more than max tokens allowed",
            "maximum prompt length is \\d+",
            "reduce the length of the messages",
            "requested input length \\d+ exceeds maximum input length",
            "maximum context length is \\d+ tokens",
            "exceeds (?:the )?maximum allowed input length of [\\d,]+ tokens?",
            "input(?: \\(\\d+ tokens\\))? is longer than the model'?s context length",
            "exceeds the limit of \\d+",
            "exceeds the available context size",
            "greater than the context length",
            "`inputs` tokens \\+ `max_new_tokens` must be <= \\d+",
            "context window exceeds limit",
            "exceeded model token limit",
            "context[_ ]length[_ ]exceeded",
            "context length is only \\d+ tokens",
            "input length.*exceeds.*context length",
            "prompt (?:too long|超长)",
            "too large for model with \\d+ maximum context length",
            "prompt has [\\d,]+ tokens?, but the configured context size is [\\d,]+ tokens?",
            "model_context_window_exceeded",
            "range of input length should be",
            "too many tokens",
            "token limit exceeded",
            "request_too_large");

    private static final List<Pattern> PAYLOAD_TOO_LARGE = compile("request entity too large", "payload too large", "request too large");

    private static final List<Pattern> OVERFLOW_EXCLUSIONS = compile(
            "^(throttling error|service unavailable):",
            "rate limit",
            "too many requests",
            "max[_ ]tokens must be less than");

    private static final Pattern NO_BODY = Pattern.compile("^4(?:00|13)\\s*(status code)?\\s*\\(no body\\)", Pattern.CASE_INSENSITIVE);

    private static final Set<String> QUOTA_CODES = Set.of(
            "insufficient_quota", "usage_not_included", "billing_error", "gousagelimiterror", "freeusagelimiterror",
            "creditlimitexceeded");
    private static final Set<String> AUTH_CODES = Set.of(
            "authentication_error", "permission_error", "permission_denied", "api_key_invalid");
    private static final Set<String> SERVER_CODES = Set.of(
            "api_error", "internal_error", "internalserverexception", "modelstreamerrorexception", "overloaded_error",
            "server_error", "server_is_overloaded", "slow_down", "serviceunavailableexception");
    private static final Set<String> INVALID_REQUEST_CODES = Set.of(
            "model_not_found", "invalid_prompt", "invalid_request", "invalid_request_error", "validationexception");
    private static final Set<String> CONTENT_POLICY_CODES = Set.of(
            "content_filter", "content_moderation", "responsibleaipolicyviolation", "content_policy_violation",
            "image_content_policy_violation", "refusal");

    private static final Pattern GATEWAY_CODE_LABEL = Pattern.compile("^[^:\\n]+: \\[([A-Za-z0-9_.-]+)]");
    private static final Pattern AUTH_TEXT = Pattern.compile("incorrect api key provided", Pattern.CASE_INSENSITIVE);
    private static final Pattern RATE_LIMIT_TEXT = Pattern.compile(
            "rate increased too quickly|rate[-_\\s]?limit|too[_\\s]?many[_\\s]?requests", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTA_TEXT = Pattern.compile(
            "insufficient[-_\\s]?(?:quota|balance)|quota[-_\\s]?exceeded|budget exceeded|usage limit|limit exhausted"
                    + "|package has expired|plan does not yet include", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_POLICY_TEXT = Pattern.compile(
            "violating our usage policy|blocked by content filtering policy|content[-_\\s]?policy"
                    + "|rejected as a result of our safety system|detected potentially unsafe or sensitive content",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVER_ERROR_TEXT = Pattern.compile(
            "\\b(?:try again|(?:please |you can )?retry (?:the |this |your )?request|try (?:the |this |your )?request again"
                    + "|(?:currently |temporarily )?at capacity|overloaded|temporarily unavailable|service[-_\\s]?unavailable"
                    + "|(?:server|internal)[-_\\s]?error|server (?:is )?busy|provider returned (?:an )?error|resource[-_\\s]?exhausted"
                    + "|upstream (?:connect|connection|request)|request buffer limit while retrying upstream)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final long RETRY_AFTER_MAX_MS = 15 * 60 * 1000L;

    private ProviderErrors() {}

    public static boolean isContextOverflow(String message) {
        if (message == null) {
            return false;
        }
        return OVERFLOW_EXCLUSIONS.stream().noneMatch(p -> p.matcher(message).find())
                && (CONTEXT_OVERFLOW.stream().anyMatch(p -> p.matcher(message).find()) || NO_BODY.matcher(message).find());
    }

    static AgentException classify(Integer status, String message, String body, Map<String, List<String>> headers) {
        String raw = body == null ? "" : body;
        List<String> codes = new ArrayList<>();
        codes.addAll(codes(HttpJson.parseObject(raw)));
        codes.addAll(codes(HttpJson.parseObject(message)));
        Matcher label = GATEWAY_CODE_LABEL.matcher(message);
        if (label.find()) {
            codes.add(label.group(1));
        }
        codes.replaceAll(c -> c.toLowerCase(Locale.ROOT));
        String text = raw.isEmpty() || raw.equals(message) ? message : message + "\n" + raw;
        boolean clientScoped = status == null || (status >= 400 && status < 500);
        long retryAfter = retryAfterMs(headers);
        Boolean shouldRetry = shouldRetry(headers);

        Kind kind;
        if (clientScoped && (codes.contains("context_length_exceeded") || codes.contains("model_context_window_exceeded")
                || codes.contains("request_too_large") || isContextOverflow(text))) {
            kind = Kind.CONTEXT_OVERFLOW;
        } else if (Integer.valueOf(413).equals(status) || matches(PAYLOAD_TOO_LARGE, text)) {
            kind = Kind.PAYLOAD_TOO_LARGE;
        } else if (codes.stream().anyMatch(CONTENT_POLICY_CODES::contains) || (clientScoped && CONTENT_POLICY_TEXT.matcher(message).find())) {
            kind = Kind.CONTENT_POLICY;
        } else if (Integer.valueOf(402).equals(status) || codes.stream().anyMatch(QUOTA_CODES::contains)
                || (Integer.valueOf(429).equals(status) && QUOTA_TEXT.matcher(text).find())) {
            kind = Kind.QUOTA;
        } else if (Integer.valueOf(401).equals(status) || Integer.valueOf(403).equals(status)
                || codes.stream().anyMatch(AUTH_CODES::contains)
                || (Integer.valueOf(400).equals(status) && AUTH_TEXT.matcher(text).find())) {
            kind = Kind.AUTH;
        } else if (Integer.valueOf(429).equals(status)
                || codes.stream().anyMatch(c -> c.contains("rate_limit") || c.equals("too_many_requests") || c.equals("throttlingexception"))
                || RATE_LIMIT_TEXT.matcher(text).find()) {
            kind = Kind.RATE_LIMIT;
        } else if (Integer.valueOf(408).equals(status) || Integer.valueOf(409).equals(status) || (status != null && status >= 500)
                || ((status == null || status < 400)
                    && ((codes.stream().noneMatch(INVALID_REQUEST_CODES::contains) && SERVER_ERROR_TEXT.matcher(text).find())
                        || codes.stream().anyMatch(c -> SERVER_CODES.contains(c) || c.contains("exhausted") || c.contains("unavailable"))))) {
            kind = Kind.PROVIDER_INTERNAL;
        } else if (codes.stream().anyMatch(INVALID_REQUEST_CODES::contains) || (status != null && status >= 400 && status < 500)) {
            kind = Kind.INVALID_REQUEST;
        } else {
            kind = Kind.UNKNOWN;
        }
        boolean honorsRetryAfter = kind == Kind.RATE_LIMIT || kind == Kind.PROVIDER_INTERNAL;
        return new AgentException(kind, message, honorsRetryAfter ? retryAfter : -1, shouldRetry);
    }

    static String message(JsonObject json) {
        if (json == null) {
            return "";
        }
        JsonObject error = optObject(json, "error");
        if (error != null && !optString(error, "message").isBlank()) {
            return optString(error, "message");
        }
        for (String key : List.of("error", "message", "Message", "detail")) {
            JsonElement e = json.get(key);
            if (e != null && e.isJsonPrimitive() && !e.getAsString().isBlank()) {
                return e.getAsString();
            }
        }
        JsonArray errors = HttpJson.array(json, "errors");
        if (!errors.isEmpty() && errors.get(0).isJsonObject()) {
            return optString(errors.get(0).getAsJsonObject(), "message");
        }
        return "";
    }

    private static List<String> codes(JsonObject json) {
        List<String> codes = new ArrayList<>();
        if (json == null) {
            return codes;
        }
        JsonObject error = optObject(json, "error");
        JsonObject response = optObject(json, "response");
        JsonObject responseError = response == null ? null : optObject(response, "error");
        JsonObject exception = optObject(json, "exception");
        add(codes, json, "code");
        if (json.has("errors") && json.get("errors").isJsonArray()) {
            add(codes, json, "name");
        }
        add(codes, json, "error_type");
        if (error != null) {
            add(codes, error, "code");
            add(codes, error, "type");
            add(codes, error, "status");
            add(codes, error, "error_type");
            for (JsonElement detail : HttpJson.array(error, "details")) {
                if (detail.isJsonObject()) {
                    add(codes, detail.getAsJsonObject(), "reason");
                }
            }
            JsonObject inner = optObject(error, "innererror");
            if (inner != null) {
                add(codes, inner, "code");
            }
            JsonObject metadata = optObject(error, "metadata");
            if (metadata != null) {
                add(codes, metadata, "error_type");
            }
        }
        if (responseError != null) {
            add(codes, responseError, "code");
        }
        if (response != null) {
            add(codes, response, "error_type");
        }
        if (exception != null) {
            add(codes, exception, "type");
        }
        return codes;
    }

    private static void add(List<String> codes, JsonObject json, String key) {
        JsonElement e = json.get(key);
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            codes.add(e.getAsString());
        }
    }

    private static long retryAfterMs(Map<String, List<String>> headers) {
        String millis = header(headers, "retry-after-ms");
        if (millis != null) {
            try {
                return clamp((long) Math.ceil(Double.parseDouble(millis)));
            } catch (NumberFormatException ignored) {
            }
        }
        String value = header(headers, "retry-after");
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return clamp((long) Math.ceil(Double.parseDouble(value) * 1000));
        } catch (NumberFormatException ignored) {
        }
        try {
            return clamp(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis());
        } catch (DateTimeParseException e) {
            return -1;
        }
    }

    private static Boolean shouldRetry(Map<String, List<String>> headers) {
        String value = header(headers, "x-should-retry");
        return value == null ? null : value.equals("true") ? Boolean.TRUE : value.equals("false") ? Boolean.FALSE : null;
    }

    private static String header(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().getFirst().trim();
            }
        }
        return null;
    }

    private static long clamp(long ms) {
        return Math.min(RETRY_AFTER_MAX_MS, Math.max(0, ms));
    }

    private static boolean matches(List<Pattern> patterns, String text) {
        return patterns.stream().anyMatch(p -> p.matcher(text).find());
    }

    private static List<Pattern> compile(String... patterns) {
        return java.util.Arrays.stream(patterns).map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE)).toList();
    }
}
