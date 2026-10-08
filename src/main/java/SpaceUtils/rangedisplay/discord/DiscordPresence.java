package SpaceUtils.rangedisplay.Discord;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import com.sun.jna.Library;
import com.sun.jna.Native;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/** Discord Rich Presence via an in-process Windows DLL and Discord IPC. */
public final class DiscordPresence {
    /** Application ID already configured for this project. */
    public static final String APPLICATION_ID = "1557377627084554290";
    private static final String DLL_RESOURCE = "/SpaceUtils/native/win/DiscordRPC.dll";
    private static final Logger LOGGER = LoggerFactory.getLogger("RangeDisplay");
    private static final long UPDATE_INTERVAL_MS = 15_000L;
    private static volatile boolean started;
    private static volatile DiscordRpcApi api;
    private static long lastUpdateMs;
    private static String lastState = "";
    private static String lastStatusMessage = "";

    private DiscordPresence() { }

    public static synchronized void start() {
        if (started) return;
        started = true;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            LOGGER.info("[Discord] Rich Presence is available on Windows only.");
            return;
        }
        try {
            Path dll = extractDll();
            DiscordRpcApi loaded = Native.load(dll.toAbsolutePath().toString(), DiscordRpcApi.class,
                    Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
            if (loaded.DiscordRPC_Start(APPLICATION_ID) == 0) {
                LOGGER.warn("[Discord] Native RPC DLL failed to initialize.");
                return;
            }
            api = loaded;
            ClientTickEvents.END_CLIENT_TICK.register(client -> updateOnClientThread());
            Runtime.getRuntime().addShutdownHook(new Thread(DiscordPresence::stop, "RangeDisplay-DiscordRPC-Stop"));
            LOGGER.info("[Discord] Native RPC DLL loaded; connecting to the Discord desktop client.");
        } catch (Throwable error) {
            LOGGER.warn("[Discord] Could not start Rich Presence: {}", error.toString());
        }
    }

    private static Path extractDll() throws IOException {
        Path requiredDirectory = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize().resolve("SpaceUtils").resolve("native").resolve("win");
        Path target = requiredDirectory.resolve("DiscordRPC.dll");
        Files.createDirectories(requiredDirectory);
        try (InputStream in = DiscordPresence.class.getResourceAsStream(DLL_RESOURCE)) {
            if (in == null) throw new IOException("Bundled DiscordRPC.dll is missing from mod resources");
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Path realDirectory = requiredDirectory.toRealPath();
        Path realDll = target.toRealPath();
        if (!realDll.getParent().equals(realDirectory)
                || !realDirectory.endsWith(Path.of("SpaceUtils", "native", "win"))) {
            throw new IOException("DiscordRPC.dll must be located at <game folder>/SpaceUtils/native/win/DiscordRPC.dll; refusing to load: " + realDll);
        }
        return realDll;
    }

    private static void updateOnClientThread() {
        DiscordRpcApi current = api;
        if (current == null) return;
        long now = System.currentTimeMillis();
        if (now - lastUpdateMs < 2_000L) return;
        int status = current.DiscordRPC_GetStatus();
        String statusMessage = current.DiscordRPC_GetLastError();
        String statusKey = status + ":" + statusMessage;
        if (!statusKey.equals(lastStatusMessage)) {
            lastStatusMessage = statusKey;
            if (status == 2) LOGGER.info("[Discord] {}", statusMessage);
            else if (status == 3) LOGGER.warn("[Discord] {}", statusMessage);
            else LOGGER.info("[Discord] {}", statusMessage);
        }
        String[] state = buildState(Minecraft.getInstance());
        String key = state[0] + "\n" + state[1];
        if (!key.equals(lastState) || now - lastUpdateMs >= UPDATE_INTERVAL_MS) {
            try {
                current.DiscordRPC_Update(state[0], state[1]);
                lastState = key;
                lastUpdateMs = now;
            } catch (Throwable error) {
                LOGGER.debug("[Discord] RPC update failed: {}", error.toString());
            }
        }
    }

    private static String[] buildState(Minecraft mc) {
        if (mc.level == null) return new String[] { "Range Display", "Download on Modrinth" };
        if (mc.getCurrentServer() != null) {
            String ip = mc.getCurrentServer().ip;
            if (ip != null && ip.length() > 100) ip = ip.substring(0, 100);
            return new String[] { "Server: " + (ip == null ? "" : ip), "Playing Minecraft" };
        }
        return new String[] { "Singleplayer", "Playing Minecraft" };
    }

    private static synchronized void stop() {
        DiscordRpcApi current = api;
        api = null;
        if (current != null) {
            try { current.DiscordRPC_Stop(); }
            catch (Throwable error) { LOGGER.debug("[Discord] RPC shutdown failed: {}", error.toString()); }
        }
    }

    public interface DiscordRpcApi extends Library {
        int DiscordRPC_Start(String clientId);
        void DiscordRPC_Update(String details, String state);
        int DiscordRPC_GetStatus();
        String DiscordRPC_GetLastError();
        void DiscordRPC_Stop();
    }
}
