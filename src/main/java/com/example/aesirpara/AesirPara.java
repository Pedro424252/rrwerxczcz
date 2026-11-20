package com.example.aesirpara;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.text.Normalizer;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aesir Para - Gaks_v310
 *
 * Yakindaki oyuncular icin sunucuya arka planda "/bal <isim>" komutunu gonderir,
 * sohbetteki cevabi ("Gaks_v310 bakiyesi 460m") yakalar, sohbetten gizler ve
 * parayi oyuncunun kafasinin ustundeki isim etiketine yazar.
 *
 * - Her turda en fazla 10 oyuncuya sorgu gider (komutlar arasi ~1 sn), sonra 5.5 sn beklenir.
 * - Yeni gorulen / henuz yuklenmemis oyuncular once sorgulanir (en yakin olan ilk).
 * - Yuklenmis oyuncularin parasi her 10 sn'de bir yenilenir.
 * - Cevap gelene kadar etikette "Yukleniyor..." yazar.
 */
public class AesirPara implements ClientModInitializer {

    /** Cevap mesaji: 1. grup = oyuncu adi, 2. grup = para (460m, 1.5k, 12,345 ...) */
    private static final String DEFAULT_REGEX =
            "(?i)([A-Za-z0-9_]{3,16})\\s+bakiyesi\\s*[:=]?\\s*\\$?\\s*(\\d[\\d.,]*[kmbt]?)";
    private static final String DEFAULT_COMMAND = "bal {isim}";
    private static final int DEFAULT_BATCH_DELAY = 5500;
    private static final int DEFAULT_SEND_DELAY = 1000;
    /** Sohbete/komuta gonderilmesine izin verilen oyuncu adi (Bedrock icin basta nokta olabilir) */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_.]{1,20}");

    public static volatile boolean enabled = true;
    public static volatile String symbol = "$";
    public static volatile String regex = DEFAULT_REGEX;
    public static volatile String command = DEFAULT_COMMAND;
    public static volatile int batchSize = 10;
    public static volatile int batchDelayMs = DEFAULT_BATCH_DELAY;
    /** Komutlar arasi ortalama bekleme (gercek bekleme %70-%130 arasi rastgele) */
    public static volatile int sendDelayMs = DEFAULT_SEND_DELAY;
    public static volatile int refreshMs = 10000;
    /** Mod sadece dunya adinda bu yazi gecen yerde calisir (bos = her yerde) */
    public static volatile String worldKey = "asmp";

    private static volatile Pattern pattern = Pattern.compile(DEFAULT_REGEX);

    private static final class Entry {
        volatile String name;
        volatile String money;      // null = henuz yuklenmedi
        volatile long lastQuery;    // en son sorgu gonderilme zamani (0 = hic)
        volatile long lastUpdate;   // en son cevap gelme zamani
        volatile long lastSeen;
        volatile double dist;
        Entry(String name) { this.name = name; }
    }

    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();
    /** cevabi beklenen oyuncular: kucuk harfli isim -> gonderilme zamani */
    private static final Map<String, Long> PENDING = new ConcurrentHashMap<>();
    private static final Queue<String[]> SEND_QUEUE = new ConcurrentLinkedQueue<>();   // {kucuk harfli isim, komut}
    private static long nextBatchAt = 0;
    private static long nextSendAt = 0;

    private static KeyBinding openKey;
    private static KeyBinding toggleKey;

    // ---------------------------------------------------------------- ayar dosyasi

    private static Path cfgPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("aesirpara.properties");
    }

    public static void save() {
        try (OutputStream out = Files.newOutputStream(cfgPath())) {
            Properties p = new Properties();
            p.setProperty("enabled", String.valueOf(enabled));
            p.setProperty("symbol", symbol);
            p.setProperty("regex", regex);
            p.setProperty("command", command);
            p.setProperty("batchSize", String.valueOf(batchSize));
            p.setProperty("batchDelayMs", String.valueOf(batchDelayMs));
            p.setProperty("sendDelayMs", String.valueOf(sendDelayMs));
            p.setProperty("refreshMs", String.valueOf(refreshMs));
            p.setProperty("worldKey", worldKey);
            p.setProperty("cfgVersion", "3");
            p.store(out, "Aesir Para");
        } catch (Throwable ignored) {
        }
    }

    private static int parseInt(String s, int def, int min) {
        try { return Math.max(min, Integer.parseInt(s.trim())); } catch (Throwable t) { return def; }
    }

    private static void load() {
        Path path = cfgPath();
        if (!Files.exists(path)) return;
        try (InputStream in = Files.newInputStream(path)) {
            Properties p = new Properties();
            p.load(in);
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
            symbol = p.getProperty("symbol", "$");
            command = p.getProperty("command", DEFAULT_COMMAND);
            batchSize = parseInt(p.getProperty("batchSize", "10"), 10, 1);
            String ver = p.getProperty("cfgVersion", "1");
            // eski surumde kayitli (kisa) bekleme sureleri yeni varsayilanlari ezmesin
            if (ver.equals("3")) {
                batchDelayMs = parseInt(p.getProperty("batchDelayMs", String.valueOf(DEFAULT_BATCH_DELAY)), DEFAULT_BATCH_DELAY, 0);
                sendDelayMs = parseInt(p.getProperty("sendDelayMs", String.valueOf(DEFAULT_SEND_DELAY)), DEFAULT_SEND_DELAY, 100);
            } else {
                batchDelayMs = DEFAULT_BATCH_DELAY;
                sendDelayMs = DEFAULT_SEND_DELAY;
            }
            refreshMs = parseInt(p.getProperty("refreshMs", "10000"), 10000, 1000);
            worldKey = p.getProperty("worldKey", "asmp");
            // eski surumde kayitli regex yeni varsayilani ezmesin
            if (ver.equals("2") || ver.equals("3")) setRegex(p.getProperty("regex", DEFAULT_REGEX));
            else setRegex(DEFAULT_REGEX);
        } catch (Throwable ignored) {
        }
    }

    private static boolean setRegex(String r) {
        try {
            pattern = Pattern.compile(r);
            regex = r;
            return true;
        } catch (Throwable t) {
            pattern = Pattern.compile(DEFAULT_REGEX);
            regex = DEFAULT_REGEX;
            return false;
        }
    }

    // ---------------------------------------------------------------- isim etiketi

    /** Mixin'den cagrilir: isim etiketinin yanina parayi ekler. Eklenecek bir sey yoksa null. */
    public static Text decorate(Entity e, Text original) {
        if (!enabled || original == null) return null;
        Entry en = ENTRIES.get(e.getUuid());
        if (en == null) return null;
        Text label = en.money == null
                ? Text.literal("Y\u00FCkleniyor...").formatted(Formatting.GRAY)
                : Text.literal(symbol + en.money).formatted(Formatting.GOLD);
        return Text.empty().append(original).append(Text.literal(" ")).append(label);
    }

    // ---------------------------------------------------------------- sohbetten cevap yakalama

    private static volatile String lastBalanceMsg = "";
    private static volatile String lastBalanceChannel = "";
    private static volatile int gameSeen = 0;
    private static volatile int chatSeen = 0;

    /** Gorunmez / ozel bosluk karakterlerini temizler. */
    private static String clean(String in) {
        return in.replaceAll("[\\u200B-\\u200D\\uFEFF\\u00AD]", "")
                .replaceAll("[\\u00A0\\u2007\\u202F\\u2009\\u200A]", " ");
    }

    private static String escape(String in) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c >= 32 && c < 127) sb.append(c);
            else sb.append(String.format("\\u%04X", (int) c));
        }
        return sb.toString();
    }

    /** false dondurursek mesaj sohbette gorunmez. */
    private static boolean handleMessage(Text message, String channel) {
        if (!enabled) return true;
        try {
            if (channel.equals("game")) gameSeen++; else chatSeen++;
            String text = clean(message.getString());
            Matcher m = pattern.matcher(text);
            if (!m.find()) {
                if (normalize(text).contains("bakiye")) {
                    lastBalanceMsg = text;
                    lastBalanceChannel = channel;
                }
                return true;
            }
            String name = m.group(1);
            String amount = m.group(2).trim();
            if (name == null || amount == null || amount.isEmpty()) return true;

            long now = System.currentTimeMillis();
            boolean ours = PENDING.remove(name.toLowerCase(Locale.ROOT)) != null;
            for (Entry en : ENTRIES.values()) {
                if (en.name.equalsIgnoreCase(name)) {
                    en.money = amount;
                    en.lastUpdate = now;
                }
            }
            // sadece bizim gonderdigimiz sorgunun cevabini gizle; elle yazilan /bal gorunsun
            return !ours;
        } catch (Throwable t) {
            return true;
        }
    }

    // ---------------------------------------------------------------- dunya kontrolu

    private static String dimensionId(MinecraftClient mc) {
        try { return mc.world.getRegistryKey().getValue().toString(); } catch (Throwable t) { return ""; }
    }

    private static String sidebarTitle(MinecraftClient mc) {
        try {
            ScoreboardObjective o = mc.world.getScoreboard().getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            return o == null ? "" : o.getDisplayName().getString();
        } catch (Throwable t) { return ""; }
    }

    private static final String SMALL_CAPS = "\u1D00\u0299\u1D04\u1D05\u1D07\uA730\u0262\u029C\u026A\u1D0A\u1D0B\u029F\u1D0D\u0274\u1D0F\u1D18\u01EB\u0280\uA731\u1D1B\u1D1C\u1D20\u1D21\u0078\u028F\u1D22";
    private static final String LATIN = "abcdefghijklmnopqrstuvwxyz";

    /** Kucuk-buyuk harf (small caps), tam genislik, kalin vb. ozel yazi tiplerini duz a-z'ye cevirir. */
    static String normalize(String in) {
        if (in == null) return "";
        String s = Normalizer.normalize(in, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int idx = SMALL_CAPS.indexOf(c);
            if (idx >= 0) sb.append(LATIN.charAt(idx));
            else if (Character.getType(c) != Character.NON_SPACING_MARK) sb.append(c);
        }
        return sb.toString();
    }

    /** Dunya adi (boyut kimligi) veya scoreboard basligi worldKey'i iceriyorsa true. */
    private static boolean inTargetWorld(MinecraftClient mc) {
        String key = normalize(worldKey.trim());
        if (key.isEmpty()) return true;
        return normalize(dimensionId(mc)).contains(key)
                || normalize(sidebarTitle(mc)).contains(key);
    }

    // ---------------------------------------------------------------- sorgu zamanlayici

    private static void tick(MinecraftClient mc) {
        ClientPlayNetworkHandler nh = mc.getNetworkHandler();
        if (nh == null) return;
        long now = System.currentTimeMillis();

        // yakindaki oyunculari guncelle
        List<Entry> present = new ArrayList<>();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (nh.getPlayerListEntry(p.getUuid()) == null) continue;   // NPC / sahte oyuncu
            // "Illegal characters in chat" kick'ini onlemek icin: ozel/gecersiz karakterli adlari atla
            if (!VALID_NAME.matcher(p.getName().getString()).matches()) continue;
            Entry en = ENTRIES.computeIfAbsent(p.getUuid(), k -> new Entry(p.getName().getString()));
            en.name = p.getName().getString();
            en.lastSeen = now;
            en.dist = mc.player.squaredDistanceTo(p);
            present.add(en);
        }
        // 30 sn'dir gorulmeyenleri sil, cevap gelmeyen bekleyenleri 5 sn sonra birak
        ENTRIES.values().removeIf(en -> now - en.lastSeen > 30000);
        PENDING.values().removeIf(t -> now - t > 20000);   // sira uzayabildigi icin 20 sn

        // sirada bekleyen komutlari tick basina 1 tane gonder
        if (!SEND_QUEUE.isEmpty()) {
            if (now < nextSendAt) return;
            String[] item = SEND_QUEUE.poll();
            if (item != null) {
                if (isSafe(item[1])) {
                    PENDING.put(item[0], now);   // sure komut gercekten gittigi andan baslasin (cevap gizlensin)
                    try { nh.sendChatCommand(item[1]); } catch (Throwable ignored) { }
                } else {
                    PENDING.remove(item[0]);
                }
            }
            // komutlar ayni anda gitmesin: aralarinda sendDelayMs'e yakin rastgele bosluk
            int lo = (int) (sendDelayMs * 0.7), hi = (int) (sendDelayMs * 1.3);
            nextSendAt = now + ThreadLocalRandom.current().nextInt(lo, Math.max(lo + 1, hi + 1));
            // son komut da gittiyse batchDelayMs kadar bekleme basliyor
            if (SEND_QUEUE.isEmpty()) nextBatchAt = now + batchDelayMs;
            return;
        }
        if (now < nextBatchAt) return;

        // yeni tur: sorgulanmasi gerekenleri sec
        List<Entry> due = new ArrayList<>();
        for (Entry en : present) {
            if (PENDING.containsKey(en.name.toLowerCase(Locale.ROOT))) continue;
            if (en.lastQuery == 0 || now - en.lastQuery >= refreshMs) due.add(en);
        }
        if (due.isEmpty()) return;

        // once yuklenmemisler (en yakin ilk), sonra en eski guncellenenler
        due.sort(Comparator
                .comparing((Entry en) -> en.money != null)
                .thenComparingDouble(en -> en.money == null ? en.dist : en.lastQuery));

        int n = Math.min(batchSize, due.size());
        for (int i = 0; i < n; i++) {
            Entry en = due.get(i);
            en.lastQuery = now;
            PENDING.put(en.name.toLowerCase(Locale.ROOT), now);
            SEND_QUEUE.add(new String[]{en.name.toLowerCase(Locale.ROOT), command.replace("{isim}", en.name)});
        }
        nextSendAt = 0;
    }

    /** Sunucunun "Illegal characters in chat" dedigi karakterler: kontrol karakterleri, DEL ve paragraf isareti. */
    private static boolean isSafe(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 127 || c == '\u00A7') return false;
        }
        return true;
    }

    private static void reset() {
        ENTRIES.clear();
        PENDING.clear();
        SEND_QUEUE.clear();
        nextBatchAt = 0;
        nextSendAt = 0;
    }

    // ---------------------------------------------------------------- komutlar

    private static void say(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.inGameHud.getChatHud().addMessage(Text.literal(msg));
    }

    private static void debug() {
        say("\u00A7e== Aesir Para debug ==");
        say("\u00A77Komut: /" + command + "  |  parti: " + batchSize + "  |  bekleme: " + batchDelayMs
                + "ms  |  komut arasi: " + sendDelayMs + "ms  |  yenileme: " + refreshMs + "ms");
        MinecraftClient mc = MinecraftClient.getInstance();
        say("\u00A77Dunya: " + dimensionId(mc) + "  |  scoreboard basligi: [" + sidebarTitle(mc)
                + "]  |  duzlestirilmis: [" + normalize(sidebarTitle(mc)) + "]  |  filtre: [" + worldKey + "]  |  aktif: " + inTargetWorld(mc));
        say("\u00A77Gelen mesaj sayaci -> game: " + gameSeen + "  chat: " + chatSeen);
        say("\u00A77Eslesmeyen son 'bakiye' mesaji [" + lastBalanceChannel + "]: " + escape(lastBalanceMsg));
        say("\u00A77Takip edilen: " + ENTRIES.size() + "  |  cevap bekleyen: " + PENDING.size()
                + "  |  gonderilmeyi bekleyen: " + SEND_QUEUE.size());
        for (Entry en : ENTRIES.values()) {
            say(" - " + en.name + " -> " + (en.money == null ? "yukleniyor" : en.money));
        }
        say("\u00A77Para gelmiyorsa elle /bal <isim> yazip cevabi bana at; regex: /aesirpara regex <desen>");
    }

    @Override
    public void onInitializeClient() {
        load();
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("aesirpara", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aesirpara.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_CONTROL, category));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aesirpara.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || handleMessage(message, "game"));
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, ts) -> handleMessage(message, "chat"));

        // Oyun menusune ayar butonu (LootGuard butonuyla ust uste binmesin diye biraz asagida)
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof GameMenuScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Aesir Para"),
                        b -> MinecraftClient.getInstance().setScreen(new AesirParaScreen()))
                        .dimensions(5, 30, 110, 20).build());
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                client.setScreen(new AesirParaScreen());
            }
            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                save();
                if (client.player != null) {
                    client.inGameHud.setOverlayMessage(Text.literal("Aesir Para: " + (enabled ? "A\u00E7\u0131k" : "Kapal\u0131")), false);
                }
            }
            if (client.player == null || client.world == null || !enabled || !inTargetWorld(client)) {
                reset();
                return;
            }
            try {
                tick(client);
            } catch (Throwable ignored) {
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("aesirpara")
                        .then(ClientCommandManager.literal("debug").executes(ctx -> {
                            debug();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("dunya")
                                .then(ClientCommandManager.argument("ad", StringArgumentType.greedyString()).executes(ctx -> {
                                    worldKey = StringArgumentType.getString(ctx, "ad").trim();
                                    save();
                                    say("\u00A7aDunya filtresi: [" + worldKey + "] (bos birakirsan her yerde calisir)");
                                    return 1;
                                })))
                        .then(ClientCommandManager.literal("komut")
                                .then(ClientCommandManager.argument("sablon", StringArgumentType.greedyString()).executes(ctx -> {
                                    String c = StringArgumentType.getString(ctx, "sablon").trim();
                                    if (c.startsWith("/")) c = c.substring(1);
                                    command = c;
                                    save();
                                    say("\u00A7aKomut: /" + command + "  ({isim} = oyuncu adi)");
                                    return 1;
                                })))
                        .then(ClientCommandManager.literal("regex")
                                .then(ClientCommandManager.argument("desen", StringArgumentType.greedyString()).executes(ctx -> {
                                    boolean ok = setRegex(StringArgumentType.getString(ctx, "desen"));
                                    save();
                                    say(ok ? "\u00A7aRegex ayarlandi (1. grup = oyuncu adi, 2. grup = para)"
                                            : "\u00A7cGecersiz regex, varsayilana donuldu");
                                    return 1;
                                })))
                        .then(ClientCommandManager.literal("sembol")
                                .then(ClientCommandManager.argument("s", StringArgumentType.greedyString()).executes(ctx -> {
                                    symbol = StringArgumentType.getString(ctx, "s");
                                    save();
                                    say("\u00A7aSembol: " + symbol);
                                    return 1;
                                })))
                        .then(ClientCommandManager.literal("sifirla").executes(ctx -> {
                            symbol = "$";
                            command = DEFAULT_COMMAND;
                            batchSize = 10;
                            batchDelayMs = DEFAULT_BATCH_DELAY;
                            sendDelayMs = DEFAULT_SEND_DELAY;
                            refreshMs = 10000;
                            worldKey = "asmp";
                            setRegex(DEFAULT_REGEX);
                            save();
                            say("\u00A7aAyarlar sifirlandi");
                            return 1;
                        }))));
    }
}
