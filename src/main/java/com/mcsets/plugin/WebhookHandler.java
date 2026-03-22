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
import java.util.List;
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
    private final Logger logger;

    public WebhookHandler(McSetsPlugin plugin, String secret) {
        this.plugin = plugin;
        this.secret = secret == null ? "" : secret;
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
        String body;
        try (InputStream is = exchange.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }

        if (body.isEmpty()) {
            sendResponse(exchange, 400, "Bad Request: empty body");
            return;
        }

        // Parse JSON and extract command(s).
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
     * Parses the JSON payload and returns the list of commands to execute.
     *
     * @param json raw JSON string from the request body.
     * @return non-null list of command strings (may be empty).
     * @throws IllegalArgumentException if the body is not valid JSON, is not a JSON object,
     *                                  neither "command" nor "commands" keys are present,
     *                                  or a value has an unexpected type.
     */
    List<String> parseCommands(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getMessage(), e);
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

    private void sendResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
