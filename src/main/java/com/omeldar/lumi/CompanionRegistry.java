package com.omeldar.lumi;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Lumi Link: other mods in the pack, and their tools.
 *
 * <p>Lumi can see the world and talk about it, but it cannot walk anywhere or press a key.
 * Companion mods can. The question is how a Claude Code session gets at both without the
 * player maintaining a list of ports in two config files, and without a session started
 * somewhere else seeing only half the pack.
 *
 * <p>The answer here is that Lumi proxies. A companion writes a descriptor into
 * {@code config/lumi-link/}, saying what it is called, where its own MCP endpoint is and what
 * token to send. Lumi reads that directory, health-checks what it finds, and merges those
 * tools into its own {@code tools/list} under the descriptor's prefix. One MCP server for the
 * session; the companions keep their own endpoints and stay useful on their own.
 *
 * <p>Deliberately not a shared Java interface. That is the obvious design - Lumi publishes an
 * interface, companions implement it, Lumi looks them up through an entrypoint - and it was
 * rejected because it makes every companion compile against a particular Lumi version, so the
 * first time either side updates, the other fails to load. A hard load failure in a mod pack
 * is worse than a missing feature. A file and an HTTP call cannot do that: a mismatch is at
 * worst a stale descriptor that fails a health check and is skipped.
 *
 * <p>Everything here is best effort. A companion that is slow, broken, half-upgraded or simply
 * not running must never make Lumi's own tools unavailable, so every failure path ends in
 * "skip it and carry on".
 */
public final class CompanionRegistry {
    private static final Gson GSON = new Gson();
    private static final int SUPPORTED_VERSION = 1;

    /** Loopback is fast; a companion that cannot answer in a second is not usable anyway. */
    private static final Duration HEALTH_TIMEOUT = Duration.ofMillis(900);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(20);
    /** How long a tool list is reused before asking again. */
    private static final long CACHE_MILLIS = 30_000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(700))
            .build();

    private static final Map<String, Companion> COMPANIONS = new LinkedHashMap<>();
    private static long lastScan;
    private static long lastToolFetch;
    private static JsonArray cachedTools;

    private CompanionRegistry() {}

    /** One companion mod, as its descriptor describes it. */
    public static final class Companion {
        public String modId = "";
        public String name = "";
        public String version = "";
        public String summary = "";
        public String prefix = "";
        public String mcpUrl = "";
        public String healthUrl = "";
        public String tokenHeader = "";
        public String token = "";
        public String hint = "";
        public boolean alive;
        public String lastError = "";
    }

    // ---------------------------------------------------------------- scanning

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("lumi-link");
    }

    /** Re-read the descriptor directory, at most every few seconds. */
    public static synchronized void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastScan < 5_000) return;
        lastScan = now;

        Map<String, Companion> found = new LinkedHashMap<>();
        Path dir = dir();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted()
                        .forEach(p -> {
                            Companion c = read(p);
                            if (c != null) found.put(c.prefix, c);
                        });
            } catch (Exception e) {
                LumiClient.LOG.debug("could not list {}", dir, e);
            }
        }

        // Keep the previous liveness, so a refresh does not make everything look dead until
        // the next health check.
        for (Companion c : found.values()) {
            Companion before = COMPANIONS.get(c.prefix);
            if (before != null && before.mcpUrl.equals(c.mcpUrl)) c.alive = before.alive;
        }
        COMPANIONS.clear();
        COMPANIONS.putAll(found);
    }

    private static Companion read(Path file) {
        try {
            JsonObject o = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();

            int version = o.has("lumiLink") ? o.get("lumiLink").getAsInt() : 0;
            if (version != SUPPORTED_VERSION) {
                LumiClient.LOG.warn("skipping {}: lumi-link version {}, this Lumi speaks {}",
                        file.getFileName(), version, SUPPORTED_VERSION);
                return null;
            }

            Companion c = GSON.fromJson(o, Companion.class);
            if (c == null || c.mcpUrl == null || c.mcpUrl.isBlank()) return null;
            if (c.prefix == null || c.prefix.isBlank()) {
                c.prefix = c.modId == null ? "" : c.modId;
            }
            c.prefix = c.prefix.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (c.prefix.isBlank()) return null;

            // These endpoints can hold the player's keys down. A descriptor that points off
            // this machine is either a mistake or something worse, and either way is refused.
            if (!loopback(c.mcpUrl) || (c.healthUrl != null && !c.healthUrl.isBlank()
                    && !loopback(c.healthUrl))) {
                LumiClient.LOG.warn("refusing {}: {} is not on loopback",
                        file.getFileName(), c.mcpUrl);
                return null;
            }
            if (c.tokenHeader == null || c.tokenHeader.isBlank()) {
                c.tokenHeader = "X-Lumi-Token";
            }
            return c;
        } catch (Exception e) {
            LumiClient.LOG.warn("skipping unreadable descriptor {}", file.getFileName(), e);
            return null;
        }
    }

    private static boolean loopback(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            return host.equals("127.0.0.1") || host.equals("::1") || host.equals("localhost");
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ health

    private static boolean healthy(Companion c) {
        if (c.healthUrl == null || c.healthUrl.isBlank()) return true;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(c.healthUrl))
                    .timeout(HEALTH_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            c.alive = res.statusCode() == 200;
            c.lastError = c.alive ? "" : "health returned " + res.statusCode();
        } catch (Exception e) {
            c.alive = false;
            c.lastError = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        return c.alive;
    }

    // ------------------------------------------------------------------- tools

    /**
     * Every live companion's tools, with names prefixed. Cached briefly, because a session
     * calls {@code tools/list} more often than a mod pack changes.
     */
    public static synchronized JsonArray tools(JsonArray ownTools) {
        long now = System.currentTimeMillis();
        if (cachedTools != null && now - lastToolFetch < CACHE_MILLIS) return cachedTools;

        refresh(false);
        JsonArray out = new JsonArray();

        List<String> ownNames = new ArrayList<>();
        for (var el : ownTools) ownNames.add(el.getAsJsonObject().get("name").getAsString());

        for (Companion c : COMPANIONS.values()) {
            if (!healthy(c)) {
                LumiClient.LOG.debug("companion {} is not answering: {}", c.prefix, c.lastError);
                continue;
            }
            JsonArray theirs = fetchTools(c);
            if (theirs == null) continue;

            for (var el : theirs) {
                JsonObject tool = el.getAsJsonObject().deepCopy();
                String bare = tool.get("name").getAsString();
                String full = c.prefix + "_" + bare;

                // Lumi's own tools win, always. A companion must not be able to shadow
                // get_context and quietly answer for the world.
                if (ownNames.contains(full)) {
                    LumiClient.LOG.warn("companion {} tool {} collides with one of Lumi's own "
                            + "- skipping it", c.prefix, full);
                    continue;
                }
                tool.addProperty("name", full);
                String desc = tool.has("description") ? tool.get("description").getAsString() : "";
                tool.addProperty("description",
                        "[" + (c.name == null || c.name.isBlank() ? c.prefix : c.name) + "] "
                        + desc);
                out.add(tool);
            }
        }

        cachedTools = out;
        lastToolFetch = now;
        return out;
    }

    private static JsonArray fetchTools(Companion c) {
        try {
            JsonObject req = new JsonObject();
            req.addProperty("jsonrpc", "2.0");
            req.addProperty("id", 1);
            req.addProperty("method", "tools/list");
            JsonObject res = rpc(c, req, HEALTH_TIMEOUT.plusSeconds(2));
            if (res == null || !res.has("result")) return null;
            JsonObject result = res.getAsJsonObject("result");
            return result.has("tools") ? result.getAsJsonArray("tools") : null;
        } catch (Exception e) {
            LumiClient.LOG.warn("could not read tools from companion {}", c.prefix, e);
            return null;
        }
    }

    /** True when this tool name belongs to a companion rather than to Lumi. */
    public static synchronized boolean handles(String toolName) {
        refresh(false);
        return find(toolName) != null;
    }

    private static Companion find(String toolName) {
        for (Companion c : COMPANIONS.values()) {
            if (toolName.startsWith(c.prefix + "_")) return c;
        }
        return null;
    }

    /**
     * Forward a call to whichever companion owns the name, and hand back its text unchanged.
     *
     * <p>Unchanged matters: a companion's refusals are written for the model that is reading
     * them, and rewording them here would lose the one thing that keeps a session from looping
     * - the part that says what to do instead.
     */
    public static String call(String toolName, JsonObject args) {
        Companion c;
        synchronized (CompanionRegistry.class) {
            refresh(false);
            c = find(toolName);
        }
        if (c == null) return null;
        String bare = toolName.substring(c.prefix.length() + 1);

        try {
            JsonObject params = new JsonObject();
            params.addProperty("name", bare);
            params.add("arguments", args == null ? new JsonObject() : args);
            JsonObject req = new JsonObject();
            req.addProperty("jsonrpc", "2.0");
            req.addProperty("id", 2);
            req.addProperty("method", "tools/call");
            req.add("params", params);

            JsonObject res = rpc(c, req, CALL_TIMEOUT);
            if (res == null) {
                return "The " + label(c) + " mod did not answer. It may have been closed, or "
                     + "the player may have left the world. Do not retry in a loop.";
            }
            if (res.has("error")) {
                JsonObject err = res.getAsJsonObject("error");
                String message = err.has("message") ? err.get("message").getAsString() : "?";
                return "The " + label(c) + " mod refused: " + message;
            }
            if (!res.has("result")) return "The " + label(c) + " mod sent an empty answer.";

            JsonObject result = res.getAsJsonObject("result");
            if (result.has("content")) {
                StringBuilder text = new StringBuilder();
                for (var el : result.getAsJsonArray("content")) {
                    JsonObject item = el.getAsJsonObject();
                    if (item.has("text")) {
                        if (text.length() > 0) text.append('\n');
                        text.append(item.get("text").getAsString());
                    }
                }
                if (text.length() > 0) return text.toString();
            }
            return GSON.toJson(result);
        } catch (Exception e) {
            LumiClient.LOG.warn("companion call {} failed", toolName, e);
            return "Calling the " + label(c) + " mod failed: " + e;
        }
    }

    private static JsonObject rpc(Companion c, JsonObject body, Duration timeout)
            throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(c.mcpUrl))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header(c.tokenHeader, c.token == null ? "" : c.token)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401) {
            throw new IllegalStateException("the token in "
                    + c.modId + ".json was not accepted - restart the game so the descriptor "
                    + "is rewritten");
        }
        if (res.statusCode() >= 400) {
            throw new IllegalStateException("http " + res.statusCode());
        }
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private static String label(Companion c) {
        return c.name == null || c.name.isBlank() ? c.prefix : c.name;
    }

    // ---------------------------------------------------------------- reporting

    public static synchronized List<Companion> known() {
        refresh(false);
        return new ArrayList<>(COMPANIONS.values());
    }

    /** A line per companion, for the MCP instructions and for {@code /lumi companions}. */
    public static synchronized String describe() {
        refresh(true);
        if (COMPANIONS.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Companion c : COMPANIONS.values()) {
            boolean up = healthy(c);
            sb.append("\n- ").append(label(c)).append(" (").append(c.prefix).append("_*)");
            if (!up) {
                sb.append(" - installed but not answering right now");
            } else if (c.hint != null && !c.hint.isBlank()) {
                sb.append(": ").append(c.hint);
            } else if (c.summary != null && !c.summary.isBlank()) {
                sb.append(": ").append(c.summary);
            }
        }
        return sb.toString();
    }
}
