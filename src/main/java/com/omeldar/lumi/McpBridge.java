package com.omeldar.lumi;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Exposes the running client to Claude as MCP tools, so it can look at the world directly
 * instead of guessing - and, when the player is in creative with commands enabled, build in it.
 *
 * <p>Reads are always available. Writes require {@link CommandPermissions} to say commands can
 * run <em>and</em> the player to be in creative, and they still go through
 * {@link CommandQueue} - so the deny list, the player's own permissions and the tick spacing
 * all still apply, and {@code /lumi cancel} still stops everything.
 *
 * <p>Speaks JSON-RPC 2.0 over POST at {@code /mcp}.
 */
public final class McpBridge {
    private static final Gson GSON = new Gson();
    private static final String PROTOCOL = "2025-06-18";
    private static final int MAX_FIND_RADIUS = 48;
    private static final int MAX_RESULTS = 128;

    private McpBridge() {}

    // ---------------------------------------------------------------- transport

    public static void handle(HttpExchange ex) throws IOException {
        if (!authorised(ex)) {
            respond(ex, 401, error(null, -32001, "bad token"));
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, error(null, -32600, "use POST"));
            return;
        }

        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        JsonObject req;
        try {
            req = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            respond(ex, 400, error(null, -32700, "parse error"));
            return;
        }

        String method = req.has("method") ? req.get("method").getAsString() : "";
        JsonElement id = req.get("id");

        // Notifications carry no id and expect no body.
        if (id == null || id.isJsonNull()) {
            ex.sendResponseHeaders(202, -1);
            ex.close();
            return;
        }

        try {
            JsonObject result = switch (method) {
                case "initialize" -> initialize(req);
                case "tools/list" -> toolList();
                case "tools/call" -> callTool(req.getAsJsonObject("params"));
                case "ping" -> new JsonObject();
                default -> null;
            };
            if (result == null) {
                respond(ex, 200, error(id, -32601, "unknown method: " + method));
            } else {
                JsonObject envelope = new JsonObject();
                envelope.addProperty("jsonrpc", "2.0");
                envelope.add("id", id);
                envelope.add("result", result);
                respond(ex, 200, GSON.toJson(envelope));
            }
        } catch (Exception e) {
            LumiClient.LOG.warn("mcp call failed", e);
            respond(ex, 200, error(id, -32603,
                    e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private static boolean authorised(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("X-Lumi-Token");
        return header != null && header.equals(Config.get().bridgeToken);
    }

    private static String error(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0");
        if (id != null) envelope.add("id", id); else envelope.add("id", JsonParser.parseString("null"));
        envelope.add("error", err);
        return GSON.toJson(envelope);
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---------------------------------------------------------------- handshake

    private static JsonObject initialize(JsonObject req) {
        String requested = PROTOCOL;
        try {
            JsonObject params = req.getAsJsonObject("params");
            if (params != null && params.has("protocolVersion")) {
                requested = params.get("protocolVersion").getAsString();
            }
        } catch (Exception ignored) {}

        JsonObject tools = new JsonObject();
        JsonObject caps = new JsonObject();
        caps.add("tools", tools);

        JsonObject info = new JsonObject();
        info.addProperty("name", "lumipet");
        info.addProperty("version", "0.1.0");

        String companions = CompanionRegistry.describe();

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", requested);
        result.add("capabilities", caps);
        result.add("serverInfo", info);
        result.addProperty("instructions",
                "Tools for the Minecraft client this session is attached to. Reads work everywhere, "
              + "but for a survival or adventure player without command permission on a server "
              + "they only return blocks the player can see. "
              + "run_command and run_commands only work in creative with commands enabled; "
              + "check can_build in get_context before relying on them."
              + (companions.isEmpty() ? "" :
                "\n\nOther mods in this pack answer through here too. Their tools carry a "
              + "prefix, and they answer for themselves - trust what they say about their own "
              + "limits over anything you would otherwise assume:" + companions));
        return result;
    }

    // ---------------------------------------------------------------- tool list

    private static JsonObject tool(String name, String description, String schemaJson) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        t.add("inputSchema", JsonParser.parseString(schemaJson).getAsJsonObject());
        return t;
    }

    private static JsonObject toolList() {
        JsonArray tools = new JsonArray();

        tools.add(tool("get_context",
                "Where the player is right now: position, facing, dimension, biome, game mode, "
              + "the block they are looking at, installed build mods, and whether commands can "
              + "be run (can_build). Call this before anything positional.",
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}"));

        tools.add(tool("get_block",
                "The block at one exact coordinate, with its full block state. Returns hidden:true "
              + "when the block is limited to what the player can see and they cannot see it.",
                "{\"type\":\"object\",\"properties\":{"
              + "\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"}},"
              + "\"required\":[\"x\",\"y\",\"z\"]}"));

        tools.add(tool("scan_area",
                "Scan a cylinder around the player, three y-layers deep, and return every "
              + "non-air block as run-length spans with exact coordinates. Air is never "
              + "listed, so even a 256 radius stays readable. Use this to see what is actually "
              + "built rather than guessing. The output says if it is limited to visible blocks "
              + "(radius 64 at most), where anything absent may be air or hidden.",
                "{\"type\":\"object\",\"properties\":{"
              + "\"radius\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":256,"
              + "\"description\":\"horizontal radius in blocks; large values are fine, the "
              + "output is sparse\"}},\"required\":[\"radius\"]}"));

        tools.add(tool("find_blocks",
                "Find where a given block type occurs near the player. Returns up to 128 "
              + "coordinates plus the bounding box they span. visibleOnly:true means only "
              + "blocks the player can see were searched.",
                "{\"type\":\"object\",\"properties\":{"
              + "\"block\":{\"type\":\"string\",\"description\":\"block id, e.g. minecraft:deepslate_tiles\"},"
              + "\"radius\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":48},"
              + "\"yRange\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":64,"
              + "\"description\":\"how many blocks above and below the player to search, default 8\"}},"
              + "\"required\":[\"block\"]}"));

        tools.add(tool("run_command",
                "Run one command in the game as the player. Creative plus command permission "
              + "only. WorldEdit commands keep both slashes, e.g. //cyl stone 10 1.",
                "{\"type\":\"object\",\"properties\":{"
              + "\"command\":{\"type\":\"string\"}},\"required\":[\"command\"]}"));

        tools.add(tool("run_commands",
                "Run several commands in order, a few ticks apart. Creative plus command "
              + "permission only. Prefer this over many run_command calls.",
                "{\"type\":\"object\",\"properties\":{"
              + "\"commands\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"maxItems\":64}},"
              + "\"required\":[\"commands\"]}"));

        tools.add(tool("say",
                "Print a line to the player's chat, tagged with the mod's mark. Use it to "
              + "narrate what you are doing during a long build.",
                "{\"type\":\"object\",\"properties\":{"
              + "\"message\":{\"type\":\"string\"}},\"required\":[\"message\"]}"));

        // Whatever else is in the pack, under its own prefix. Lumi's own tools are added
        // first and win any name collision, so a companion cannot shadow get_context.
        for (var el : CompanionRegistry.tools(tools)) {
            tools.add(el);
        }

        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    // ---------------------------------------------------------------- dispatch

    private static JsonObject callTool(JsonObject params) throws Exception {
        String name = params.get("name").getAsString();
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();

        return switch (name) {
            case "get_context"  -> text(onClient(ContextSnapshot::json));
            case "get_block"    -> text(getBlock(args));
            case "scan_area"    -> text(scanArea(args));
            case "find_blocks"  -> text(findBlocks(args));
            case "run_command"  -> text(runCommands(new String[]{args.get("command").getAsString()}));
            case "run_commands" -> text(runCommands(stringArray(args.getAsJsonArray("commands"))));
            case "say"          -> { Chat.info(args.get("message").getAsString()); yield text("said"); }
            default -> {
                // Not one of ours: it may belong to a companion mod in the pack.
                String forwarded = CompanionRegistry.call(name, args);
                if (forwarded == null) throw new IllegalArgumentException("unknown tool: " + name);
                yield text(forwarded);
            }
        };
    }

    private static JsonObject text(String body) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", body);
        JsonArray content = new JsonArray();
        content.add(item);
        JsonObject result = new JsonObject();
        result.add("content", content);
        result.addProperty("isError", false);
        return result;
    }

    // ---------------------------------------------------------------- tools

    private static String getBlock(JsonObject args) throws Exception {
        int x = args.get("x").getAsInt();
        int y = args.get("y").getAsInt();
        int z = args.get("z").getAsInt();
        return onClient(mc -> {
            if (mc.world == null) return "not in a world";
            BlockPos pos = new BlockPos(x, y, z);
            JsonObject o = new JsonObject();
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            if (BlockSight.limited(mc) && !BlockSight.canSee(mc, pos)) {
                o.addProperty("hidden", true);
                o.addProperty("note", "The player cannot see this block and has no command "
                        + "permission on this server, so it is not available. Do not guess.");
                return GSON.toJson(o);
            }
            var state = mc.world.getBlockState(pos);
            o.addProperty("block", Registries.BLOCK.getId(state.getBlock()).toString());
            o.addProperty("state", state.toString());
            o.addProperty("isAir", state.isAir());
            return GSON.toJson(o);
        });
    }

    private static String scanArea(JsonObject args) throws Exception {
        int radius = args.has("radius") ? args.get("radius").getAsInt()
                                        : Config.get().defaultScanRadius;
        return onClient(mc -> {
            var path = WorldScan.dump(mc, radius);
            if (path == null) return "scan failed - not in a world?";
            try {
                String body = java.nio.file.Files.readString(path, StandardCharsets.UTF_8);
                // Keep a very large scan from swallowing the context window.
                return body.length() > 60_000 ? body.substring(0, 60_000) + "\n...(truncated)" : body;
            } catch (Exception e) {
                return "scan written to " + path + " but could not be read back";
            }
        });
    }

    private static String findBlocks(JsonObject args) throws Exception {
        String wanted = args.get("block").getAsString();
        int radius = Math.min(MAX_FIND_RADIUS,
                args.has("radius") ? args.get("radius").getAsInt() : 24);
        int yRange = Math.min(64, args.has("yRange") ? args.get("yRange").getAsInt() : 8);

        return onClient(mc -> {
            if (mc.world == null || mc.player == null) return "not in a world";
            boolean limited = BlockSight.limited(mc);
            BlockPos feet = mc.player.getBlockPos();
            JsonArray hits = new JsonArray();
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            int total = 0;
            BlockPos.Mutable cursor = new BlockPos.Mutable();

            for (int dy = -yRange; dy <= yRange; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                        String id;
                        try {
                            id = Registries.BLOCK.getId(mc.world.getBlockState(cursor).getBlock()).toString();
                        } catch (Exception e) {
                            continue;
                        }
                        if (!id.equals(wanted)) continue;
                        if (limited && !BlockSight.canSee(mc, cursor)) continue;
                        total++;
                        minX = Math.min(minX, cursor.getX()); maxX = Math.max(maxX, cursor.getX());
                        minY = Math.min(minY, cursor.getY()); maxY = Math.max(maxY, cursor.getY());
                        minZ = Math.min(minZ, cursor.getZ()); maxZ = Math.max(maxZ, cursor.getZ());
                        if (hits.size() < MAX_RESULTS) {
                            JsonObject h = new JsonObject();
                            h.addProperty("x", cursor.getX());
                            h.addProperty("y", cursor.getY());
                            h.addProperty("z", cursor.getZ());
                            hits.add(h);
                        }
                    }
                }
            }

            JsonObject o = new JsonObject();
            o.addProperty("block", wanted);
            o.addProperty("searchRadius", radius);
            if (limited) {
                o.addProperty("visibleOnly", true);
                o.addProperty("note", "Only blocks the player can see were searched. "
                        + "Hidden ones may exist. Do not guess where.");
            }
            o.addProperty("totalFound", total);
            o.addProperty("returned", hits.size());
            if (total > 0) {
                JsonObject box = new JsonObject();
                box.addProperty("minX", minX); box.addProperty("maxX", maxX);
                box.addProperty("minY", minY); box.addProperty("maxY", maxY);
                box.addProperty("minZ", minZ); box.addProperty("maxZ", maxZ);
                o.add("boundingBox", box);
            }
            o.add("positions", hits);
            return GSON.toJson(o);
        });
    }

    private static String runCommands(String[] commands) throws Exception {
        return onClient(mc -> {
            String refusal = refuseBuild(mc);
            if (refusal != null) return refusal;

            int queued = 0;
            StringBuilder skipped = new StringBuilder();
            for (String c : commands) {
                if (c == null || c.isBlank()) continue;
                if (Config.get().isBlocked(c)) {
                    skipped.append("\n  refused (deny list): ").append(c);
                    continue;
                }
                CommandQueue.enqueue(c);
                queued++;
            }
            Chat.overlay("running " + queued + " command" + (queued == 1 ? "" : "s"));
            return "queued " + queued + " command" + (queued == 1 ? "" : "s")
                 + ", spaced " + Config.get().commandDelayTicks + " ticks apart"
                 + skipped
                 + "\nThey run as the player. Call get_block or scan_area afterwards to check "
                 + "the result - allow a moment for the queue to drain.";
        });
    }

    /** Null when building is allowed, otherwise the reason to hand back to Claude. */
    private static String refuseBuild(MinecraftClient mc) {
        if (!CommandPermissions.allowed(mc)) {
            return "REFUSED: no command permission here (" + CommandPermissions.authority(mc) + "). "
                 + CommandPermissions.explain(mc)
                 + " Answer with advice instead and do not retry this tool.";
        }
        GameMode mode = mc.interactionManager == null ? null
                : mc.interactionManager.getCurrentGameMode();
        if (mode == null || !mode.isCreative()) {
            return "REFUSED: the player is in " + (mode == null ? "an unknown mode" : mode.asString())
                 + ". Direct building is creative-only. Propose the commands in a json block "
                 + "instead so the player can approve them, or ask them to switch to creative.";
        }
        return null;
    }

    // ---------------------------------------------------------------- plumbing

    private static <T> T onClient(Function<MinecraftClient, T> fn) throws Exception {
        MinecraftClient mc = MinecraftClient.getInstance();
        CompletableFuture<T> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(fn.apply(mc));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(15, TimeUnit.SECONDS);
    }

    private static String[] stringArray(JsonArray array) {
        if (array == null) return new String[0];
        String[] out = new String[array.size()];
        for (int i = 0; i < array.size(); i++) out[i] = array.get(i).getAsString();
        return out;
    }
}
