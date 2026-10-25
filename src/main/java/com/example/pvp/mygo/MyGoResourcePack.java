package com.example.pvp.mygo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackRemoveS2CPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * MyGO 的枪械和瞄准镜依赖客户端资源，故在排队前确认实际加载成功，而非只接受下载提示。
 * 默认使用独立 HTTPS 下载地址，内置 HTTP 仅供本机、局域网测试。
 * 所有状态在服务器线程管理，退包仅指定 MyGO UUID，不影响服务器已有的其他资源包。
 */
public final class MyGoResourcePack {
    static final String DEFAULT_PACK_URL = "https://qfmaker.com/upload/files/nyiz2u77.zip";
    private static final String PREVIOUS_DEFAULT_PACK_URL = "https://qfmaker.com/upload/files/lf192hqd.zip";
    private static final String CLASSIC_PACK_URL = "https://qfmaker.com/upload/files/uxv5wsbc.zip";
    private static final String TEXTURE_FIX_PACK_URL = "https://qfmaker.com/upload/files/zevadwr3.zip";
    private static final String PREVIOUS_PACK_URL = "https://qfmaker.com/upload/files/5df4ab7h.zip";
    private static final String ORIGINAL_PACK_URL = "https://qfmaker.com/upload/files/wmcvlnck.zip";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, LoadState> STATES = new HashMap<>();
    private static MinecraftServer server;
    private static Config config = new Config();
    private static byte[] packBytes;
    private static String sha1 = "";
    private static UUID packId;
    private static HttpServer httpServer;
    private static ExecutorService httpExecutor;
    private static Consumer<ServerPlayerEntity> failureHandler = player -> { };

    private MyGoResourcePack() {
    }

    public static void start(MinecraftServer minecraftServer) {
        stop();
        server = minecraftServer;
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("pvp/mygo");
        try {
            Files.createDirectories(directory);
            Path configPath = directory.resolve("resource-pack.json");
            boolean writeConfig = !Files.exists(configPath);
            if (Files.exists(configPath)) {
                Config parsed = GSON.fromJson(Files.readString(configPath), Config.class);
                config = parsed == null ? new Config() : parsed;
            } else {
                config = new Config();
            }
            config.validate();
            // 升级旧空配置与上一版默认 CDN；已显式选择的局域网 HTTP、自定义 CDN 保留。
            writeConfig |= config.migrate();
            if (writeConfig) Files.writeString(configPath, GSON.toJson(config));
            try (InputStream input = MyGoResourcePack.class.getResourceAsStream("/pvp/mygo/mygo-pack.zip")) {
                if (input == null) {
                    throw new IOException("mod 缺少 /pvp/mygo/mygo-pack.zip");
                }
                packBytes = input.readAllBytes();
            }
            sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(packBytes));
            packId = UUID.nameUUIDFromBytes(("pvp:mygo:" + sha1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.write(directory.resolve("mygo-pack.zip"), packBytes);
            Files.writeString(directory.resolve("mygo-pack.sha1"), sha1 + "\n");
            if (config.packUrl.isBlank()) {
                startHttp();
            }
            LOGGER.info("[MyGO] 资源包已导出到 {}，SHA-1={}，UUID={}", directory, sha1, packId);
        } catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
            LOGGER.error("[MyGO] 无法初始化资源包；MyGO 排队将保持关闭", exception);
            closeHttp();
            packBytes = null;
        }
    }

    private static void startHttp() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(config.bindAddress, config.httpPort), 16);
        httpServer.createContext("/mygo-pack.zip", MyGoResourcePack::servePack);
        httpExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mygo-resource-pack-http");
            thread.setDaemon(true);
            return thread;
        });
        httpServer.setExecutor(httpExecutor);
        httpServer.start();
        LOGGER.info("[MyGO] 本机/局域网资源包 HTTP 已启动：{}:{}；公网请配置 HTTPS packUrl",
                config.bindAddress, httpServer.getAddress().getPort());
    }

    private static void servePack(HttpExchange exchange) throws IOException {
        try (exchange) {
            boolean head = "HEAD".equals(exchange.getRequestMethod());
            if (!"/mygo-pack.zip".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!head && !"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] data = packBytes;
            if (data == null) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(data.length));
            exchange.getResponseHeaders().set("ETag", "\"" + sha1 + "\"");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            exchange.sendResponseHeaders(200, head ? -1 : data.length);
            if (!head) {
                exchange.getResponseBody().write(data);
            }
        }
    }

    /** @return true only if this connection has confirmed SUCCESSFULLY_LOADED. */
    public static boolean request(ServerPlayerEntity player) {
        if (isReady(player)) {
            return true;
        }
        if (packBytes == null || packId == null || server == null) {
            player.sendMessage(Text.literal("MyGO 资源包服务不可用，请联系管理员检查服务器日志。")
                    .formatted(Formatting.RED), false);
            return false;
        }
        if (STATES.containsKey(player.getUuid())) {
            player.sendMessage(Text.literal("MyGO 资源包正在加载，完成后请再次选择 MyGO。")
                    .formatted(Formatting.YELLOW), false);
            return false;
        }
        String url;
        try {
            url = config.packUrl.isBlank() ? localUrl(player) : config.packUrl;
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.warn("[MyGO] 无法生成 {} 的资源包地址", player.getGameProfile().getName(), exception);
            player.sendMessage(Text.literal("无法确定资源包下载地址，请管理员配置 packUrl。")
                    .formatted(Formatting.RED), false);
            return false;
        }
        STATES.put(player.getUuid(), new LoadState(false,
                System.currentTimeMillis() + config.loadTimeoutSeconds * 1000L));
        player.networkHandler.sendPacket(new ResourcePackSendS2CPacket(packId, url, sha1, false,
                Optional.of(Text.literal("MyGO 枪械、枪声与南瓜瞄准镜资源包；仅 MyGO 模式需要。"))));
        player.sendMessage(Text.literal("请接受 MyGO 资源包；加载成功后再次选择 MyGO 即可排队。")
                .formatted(Formatting.YELLOW), false);
        return false;
    }

    private static String localUrl(ServerPlayerEntity player) throws IOException {
        if (httpServer == null) {
            throw new IOException("HTTP service is not running");
        }
        String host = config.advertisedHost;
        if (host.isBlank()) {
            // Integrated-server players use a local connection with no IP socket address.
            InetAddress remote = player.networkHandler.getConnectionAddress() instanceof InetSocketAddress address
                    ? address.getAddress() : InetAddress.getLoopbackAddress();
            if (remote.isLoopbackAddress() || remote.isAnyLocalAddress()) {
                host = "127.0.0.1";
            } else if (!server.getServerIp().isBlank()
                    && !InetAddress.getByName(server.getServerIp()).isAnyLocalAddress()) {
                host = server.getServerIp();
            } else {
                // Selecting a route sends no datagrams; it discovers the local NIC facing this player.
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.connect(remote, 9);
                    host = socket.getLocalAddress().getHostAddress();
                }
            }
        }
        try {
            return new URI("http", null, host, httpServer.getAddress().getPort(),
                    "/mygo-pack.zip", "v=" + sha1, null).toASCIIString();
        } catch (java.net.URISyntaxException exception) {
            throw new IOException("Invalid advertisedHost", exception);
        }
    }

    public static boolean isReady(ServerPlayerEntity player) {
        LoadState state = STATES.get(player.getUuid());
        return state != null && state.ready;
    }

    /** Called on failure or timeout, never on a normal mode exit or disconnect. */
    public static void setFailureHandler(Consumer<ServerPlayerEntity> handler) {
        failureHandler = java.util.Objects.requireNonNull(handler);
    }

    public static void onStatus(ServerPlayerEntity player, ResourcePackStatusC2SPacket packet) {
        if (packId == null || !packId.equals(packet.id())) {
            return;
        }
        LoadState state = STATES.get(player.getUuid());
        if (state == null) {
            return;
        }
        switch (packet.status()) {
            case SUCCESSFULLY_LOADED -> {
                STATES.put(player.getUuid(), new LoadState(true, Long.MAX_VALUE));
                player.sendMessage(Text.literal("MyGO 资源包加载成功！再次选择 MyGO 加入队列。")
                        .formatted(Formatting.GREEN), false);
            }
            case ACCEPTED, DOWNLOADED -> {
                // Download completion does not mean models/sounds have finished reloading.
            }
            default -> {
                remove(player);
                failureHandler.accept(player);
                player.sendMessage(Text.literal("MyGO 资源包未成功加载（" + packet.status()
                                + "），你仍在大厅，可重试或选择其他模式。")
                        .formatted(Formatting.RED), false);
            }
        }
    }

    public static void tick(MinecraftServer minecraftServer) {
        if (minecraftServer != server || minecraftServer.getTicks() % 20 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        // Callbacks may end a match and remove several states; iterate a snapshot.
        for (Map.Entry<UUID, LoadState> entry : new ArrayList<>(STATES.entrySet())) {
            ServerPlayerEntity player = minecraftServer.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                STATES.remove(entry.getKey());
            } else if (!entry.getValue().ready && now >= entry.getValue().deadline) {
                STATES.remove(entry.getKey());
                pop(player);
                failureHandler.accept(player);
                player.sendMessage(Text.literal("MyGO 资源包加载超时；请检查下载地址后再次选择 MyGO。")
                        .formatted(Formatting.RED), false);
            }
        }
    }

    /** Safe to call repeatedly, including after the player's connection has closed. */
    public static void remove(ServerPlayerEntity player) {
        if (STATES.remove(player.getUuid()) != null) {
            pop(player);
        }
    }

    private static void pop(ServerPlayerEntity player) {
        if (packId != null && player.networkHandler != null && !player.isDisconnected()) {
            player.networkHandler.sendPacket(new ResourcePackRemoveS2CPacket(Optional.of(packId)));
        }
    }

    public static void stop() {
        closeHttp();
        STATES.clear();
        packBytes = null;
        packId = null;
        server = null;
    }

    private static void closeHttp() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
    }

    private record LoadState(boolean ready, long deadline) {
    }

    static final class Config {
        int configVersion = 0;
        String packUrl = DEFAULT_PACK_URL;
        String bindAddress = "0.0.0.0";
        int httpPort = 25566;
        String advertisedHost = "";
        int loadTimeoutSeconds = 120;

        boolean migrate() {
            if (configVersion >= 6) return false;
            // 新脚步/投掷音效需要新版 ZIP，旧34枪默认地址必须与内置 SHA-1 一起升级。
            // 显式本地 HTTP、自定义 CDN 以及第4版起自行指定的历史资源地址继续保留。
            if ((configVersion < 1 && packUrl.isBlank()) || PREVIOUS_DEFAULT_PACK_URL.equals(packUrl)
                    || CLASSIC_PACK_URL.equals(packUrl)
                    || configVersion < 4 && (PREVIOUS_PACK_URL.equals(packUrl)
                    || ORIGINAL_PACK_URL.equals(packUrl) || TEXTURE_FIX_PACK_URL.equals(packUrl))) {
                packUrl = DEFAULT_PACK_URL;
            }
            configVersion = 6;
            return true;
        }

        void validate() {
            packUrl = packUrl == null ? "" : packUrl.trim();
            bindAddress = bindAddress == null ? "0.0.0.0" : bindAddress.trim();
            advertisedHost = advertisedHost == null ? "" : advertisedHost.trim();
            if (httpPort < 0 || httpPort > 65535 || loadTimeoutSeconds < 10 || loadTimeoutSeconds > 600) {
                throw new IllegalArgumentException("httpPort 必须为 0..65535；loadTimeoutSeconds 必须为 10..600");
            }
            if (!packUrl.isBlank()) {
                URI uri = URI.create(packUrl);
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null) {
                    throw new IllegalArgumentException("packUrl 必须为直接下载 ZIP 的 HTTP(S) 地址");
                }
                if (!"https".equalsIgnoreCase(uri.getScheme())) {
                    LOGGER.warn("[MyGO] packUrl 使用 HTTP；公网部署请改用 HTTPS。");
                }
            }
        }
    }
}
