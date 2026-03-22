package com.mcsets.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.logging.Logger;

/**
 * Handles incoming HTTP requests to the webhook endpoint.
 *
 * <p>Expected request format (POST, Content-Type: application/json):</p>
 * <pre>
 * {
 *   "command": "say Hello World",          // single command  (mutually exclusive with "commands")
 *   "commands": ["say Hi", "time set day"] // multiple commands (mutually exclusive with "command")
 * }
 * </pre>
 *
 * <p>If a non-empty {@code secret} is configured, every request must include
 * an {@code X-Secret} HTTP header whose value matches the configured secret.
 * Requests that fail authentication receive a {@code 401 Unauthorized}
 * response and are not executed.</p>
 */
public class WebhookHandler implements HttpHandler {

    private final McSetsPlugin plugin;
    private final String secret;
    private final boolean debugMode;
    private final Logger logger;

    public WebhookHandler(McSetsPlugin plugin, String secret) {
        this(plugin, secret, false);
    }

    public WebhookHandler(McSetsPlugin plugin, String secret, boolean debugMode) {
        this.plugin = plugin;
        this.secret = secret == null ? "" : secret;
        this.debugMode = debugMode;
        this.logger = plugin.getLogger();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Only accept POST requests.
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        // Validate the shared secret when one is configured.
        if (!secret.isEmpty()) {
            String requestSecret = exchange.getRequestHeaders().getFirst("X-Secret");
            if (!secret.equals(requestSecret)) {
                sendResponse(exchange, 401, "Unauthorized");
                return;
            }
        }

        // Read request body.
        String rawBody;
        try (InputStream is = exchange.getRequestBody()) {
            rawBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        if (debugMode) {
            logger.info("Webhook raw request body: " + rawBody);
        }

        String body = rawBody.trim();

        if (body.isEmpty()) {
            sendResponse(exchange, 400, "Bad Request: empty body");
            return;
        }

        // Parse payload and extract command(s).
        List<String> commands;
        try {
            commands = parseCommands(body);
        } catch (JsonSyntaxException | IllegalArgumentException e) {
            logger.warning("Invalid webhook payload: " + e.getMessage());
            sendResponse(exchange, 400, "Bad Request: " + e.getMessage());
            return;
        }

        if (commands.isEmpty()) {
            sendResponse(exchange, 400, "Bad Request: no command(s) provided");
            return;
        }

        // Dispatch each command on the main server thread.
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (String cmd : commands) {
                if (!cmd.isEmpty()) {
                    logger.info("Executing webhook command: " + cmd);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                }
            }
        });

        sendResponse(exchange, 200, "OK");
    }

    /**
     * Parses the payload and returns the list of commands to execute.
     *
     * <p>Accepted formats:</p>
     * <ul>
     *   <li>JSON object with "command" or "commands" keys.</li>
     *   <li>Plain text purchase payload in key/value lines, such as:
     *       {@code Player: Name} and {@code Package(s): Rank}.</li>
     * </ul>
     *
     * @param payload raw request body.
     * @return non-null list of command strings (may be empty).
     * @throws IllegalArgumentException if the payload cannot be interpreted,
     *                                  or values have unexpected types.
     */
    List<String> parseCommands(String payload) {
        JsonElement root;
        try {
            root = JsonParser.parseString(payload);
        } catch (JsonSyntaxException e) {
            return parseCommandsFromPlainTextPayload(payload);
        }

        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }

        JsonObject obj = root.getAsJsonObject();
        List<String> result = new ArrayList<>();

        if (obj.has("commands")) {
            JsonElement commandsEl = obj.get("commands");
            if (!commandsEl.isJsonArray()) {
                throw new IllegalArgumentException("\"commands\" must be a JSON array");
            }
            JsonArray arr = commandsEl.getAsJsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("each entry in \"commands\" must be a string");
                }
                result.add(el.getAsString().trim());
            }
        } else if (obj.has("command")) {
            JsonElement commandEl = obj.get("command");
            if (!commandEl.isJsonPrimitive() || !commandEl.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("\"command\" must be a string");
            }
            result.add(commandEl.getAsString().trim());
        } else {
            throw new IllegalArgumentException("payload must contain a \"command\" or \"commands\" key");
        }

        return result;
    }

    private List<String> parseCommandsFromPlainTextPayload(String payload) {
        Map<String, String> fields = extractPlainTextFields(payload);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("invalid payload: expected JSON command(s) or key/value purchase data");
        }

        List<String> templates = resolveRuleTemplates(fields);
        if (templates.isEmpty()) {
            templates = new ArrayList<>(plugin.getConfig().getStringList("webhook.textPayload.commands"));
            String singleTemplate = plugin.getConfig().getString("webhook.textPayload.command", "").trim();
            if (!singleTemplate.isEmpty()) {
                templates.add(singleTemplate);
            }
        }

        if (templates.isEmpty()) {
            templates.add("say Purchase completed for {player}: {packages} ({amount} {currency})");
        }

        List<String> commands = new ArrayList<>();
        for (String template : templates) {
            String expanded = applyTemplate(template, fields).trim();
            if (!expanded.isEmpty()) {
                commands.add(expanded);
            }
        }

        if (debugMode) {
            logger.info("Extracted webhook fields: " + fields);
        }

        return commands;
    }

    private List<String> resolveRuleTemplates(Map<String, String> fields) {
        List<String> templates = new ArrayList<>();
        List<Map<?, ?>> rules = plugin.getConfig().getMapList("webhook.textPayload.rules");

        for (Map<?, ?> rule : rules) {
            Map<?, ?> when = asMap(rule.get("when"));
            if (!when.isEmpty() && !matchesAllConditions(fields, when)) {
                continue;
            }

            templates.addAll(toStringList(rule.get("commands")));

            Object single = rule.get("command");
            if (single instanceof String singleCommand && !singleCommand.trim().isEmpty()) {
                templates.add(singleCommand);
            }
        }

        return templates;
    }

    private boolean matchesAllConditions(Map<String, String> fields, Map<?, ?> conditions) {
        for (Map.Entry<?, ?> condition : conditions.entrySet()) {
            String rawKey = String.valueOf(condition.getKey()).trim();
            String expected = String.valueOf(condition.getValue()).trim();
            if (rawKey.isEmpty()) {
                continue;
            }

            if (rawKey.endsWith("_contains")) {
                String fieldKey = normalizeFieldKey(rawKey.substring(0, rawKey.length() - "_contains".length()));
                String actual = fields.get(fieldKey);
                if (actual == null || !actual.toLowerCase().contains(expected.toLowerCase())) {
                    return false;
                }
                continue;
            }

            if (rawKey.endsWith("_regex")) {
                String fieldKey = normalizeFieldKey(rawKey.substring(0, rawKey.length() - "_regex".length()));
                String actual = fields.get(fieldKey);
                if (actual == null) {
                    return false;
                }
                try {
                    if (!Pattern.compile(expected, Pattern.CASE_INSENSITIVE).matcher(actual).find()) {
                        return false;
                    }
                } catch (PatternSyntaxException e) {
                    logger.warning("Invalid webhook rule regex for key \"" + rawKey + "\": " + expected);
                    return false;
                }
                continue;
            }

            String fieldKey = normalizeFieldKey(rawKey);
            String actual = fields.get(fieldKey);
            if (actual == null || !actual.equalsIgnoreCase(expected)) {
                return false;
            }
        }

        return true;
    }

    private Map<?, ?> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        return Map.of();
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        for (Object entry : list) {
            if (entry == null) {
                continue;
            }
            String asString = String.valueOf(entry).trim();
            if (!asString.isEmpty()) {
                out.add(asString);
            }
        }
        return out;
    }

    Map<String, String> extractPlainTextFields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        String[] lines = payload.split("\\r?\\n");

        String title = "";
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                title = trimmed;
                break;
            }
        }
        if (!title.isEmpty()) {
            fields.put("title", title);
        }

        for (String line : lines) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }

            String rawKey = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (rawKey.isEmpty()) {
                continue;
            }

            String normalizedKey = normalizeFieldKey(rawKey);
            fields.put(normalizedKey, value);
        }

        return fields;
    }

    private String normalizeFieldKey(String key) {
        String normalized = key.toLowerCase()
                .replace("(", "")
                .replace(")", "")
                .replace(" ", "_")
                .replace("-", "_");
        return normalized;
    }

    private String applyTemplate(String template, Map<String, String> fields) {
        String out = template;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return out;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
