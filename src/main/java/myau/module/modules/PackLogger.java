/*
 * Adapted for OpenMyau from LiquidBounce ModulePacketLogger.
 *
 * Original:
 *   https://github.com/CCBlueX/LiquidBounce
 *   src/main/kotlin/.../modules/misc/ModulePacketLogger.kt
 *   Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software under the GNU General Public License v3 (or later).
 * This adapted copy remains under GPL-3.0-or-later.
 *
 * Adapted for Myau (Forge 1.8.9) event/property system.
 *
 * Includes BooleanProperty "AntiCheat-Detect":
 * Heuristic fingerprinting of common anticheats via S32PacketConfirmTransaction
 * action-number patterns (publicly documented signatures).
 */
package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Logs clientbound / serverbound packets and optionally their fields.
 * Port of LiquidBounce PacketLogger concepts to Myau 1.8.9.
 *
 * Optional: AntiCheat-Detect — heuristic AC name from transaction id patterns.
 */
public class PackLogger extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final SimpleDateFormat FILE_NAME_FMT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);

    /** Minimum received transaction action ids before attempting a match */
    private static final int AC_MIN_SAMPLES = 3;
    /** Max ids kept in the rolling window */
    private static final int AC_MAX_SAMPLES = 24;

    /** All = both directions, Send = C2S, Receive = S2C */
    public final ModeProperty direction = new ModeProperty(
            "direction", 0, new String[]{"All", "Send", "Receive"}
    );

    public final BooleanProperty showFields = new BooleanProperty("show-fields", true);
    public final BooleanProperty showFieldType = new BooleanProperty("show-field-type", true, () -> this.showFields.getValue());

    /** Chat only / File only / Both */
    public final ModeProperty output = new ModeProperty(
            "output", 0, new String[]{"Chat", "File", "Both"}
    );

    /**
     * Optional case-insensitive substring filter on simple class name.
     * Empty = log everything (that passes direction / spam filters).
     * Example: "Velocity" matches S12PacketEntityVelocity.
     */
    public final TextProperty nameFilter = new TextProperty("name-filter", "");

    /** Skip very spammy movement packets */
    public final BooleanProperty ignoreMove = new BooleanProperty("ignore-move", true);
    /** Skip keep-alive / confirm-transaction spam (does NOT affect AntiCheat-Detect) */
    public final BooleanProperty ignoreKeepAlive = new BooleanProperty("ignore-keepalive", true);

    /**
     * When enabled: watch S32PacketConfirmTransaction action numbers and try to
     * fingerprint the server anticheat (Grim / Vulcan / Intave / Verus / …).
     * Independent of ignore-keepalive — transactions are still sampled for detection.
     * Default: false.
     */
    public final BooleanProperty antiCheatDetect = new BooleanProperty("AntiCheat-Detect", false);

    private File logFile;
    private BufferedWriter fileWriter;

    // ── AntiCheat-Detect state ───────────────────────────────────────────────
    private final List<Short> txIds = new ArrayList<Short>();
    private String lastDetectedAc = null;
    private long lastNotifyMs = 0L;

    public PackLogger() {
        super("PackLogger", false, true, "Prints packets and fields (debug, from LiquidBounce PacketLogger)");
    }

    @Override
    public void onEnabled() {
        this.resetAcDetect();
        if (this.output.getValue() == 1 || this.output.getValue() == 2) {
            this.openLogFile();
        }
    }

    @Override
    public void onDisabled() {
        this.closeLogFile();
        this.resetAcDetect();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }

        EventType type = event.getType();
        boolean isSend = type == EventType.SEND;
        boolean isReceive = type == EventType.RECEIVE;
        if (!isSend && !isReceive) {
            return;
        }

        Packet<?> packet = event.getPacket();
        if (packet == null) {
            return;
        }

        // AntiCheat-Detect always samples inbound transactions when the setting is on,
        // even if ignore-keepalive would skip them in the normal logger path.
        if (isReceive && this.antiCheatDetect.getValue()) {
            this.handleAcTransaction(packet);
        }

        int dir = this.direction.getValue();
        if (dir == 1 && !isSend) {
            return;
        }
        if (dir == 2 && !isReceive) {
            return;
        }

        String simpleName = packet.getClass().getSimpleName();
        if (simpleName == null || simpleName.isEmpty()) {
            simpleName = packet.getClass().getName();
        }

        if (this.shouldIgnoreSpam(simpleName)) {
            return;
        }

        String filter = this.nameFilter.getValue();
        if (filter != null && !filter.trim().isEmpty()) {
            if (!simpleName.toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT))) {
                return;
            }
        }

        boolean canceled = event.isCancelled();
        int out = this.output.getValue();

        if (out == 0 || out == 2) {
            this.logToChat(isSend, simpleName, packet, canceled);
        }
        if (out == 1 || out == 2) {
            this.logToFile(isSend, simpleName, packet, canceled);
        }
    }

    // ── AntiCheat-Detect ─────────────────────────────────────────────────────

    private void resetAcDetect() {
        this.txIds.clear();
        this.lastDetectedAc = null;
        this.lastNotifyMs = 0L;
    }

    private void handleAcTransaction(Packet<?> packet) {
        if (!(packet instanceof S32PacketConfirmTransaction)) {
            return;
        }

        short actionId = extractActionNumber((S32PacketConfirmTransaction) packet);
        this.txIds.add(actionId);
        while (this.txIds.size() > AC_MAX_SAMPLES) {
            this.txIds.remove(0);
        }

        if (this.txIds.size() < AC_MIN_SAMPLES) {
            return;
        }

        String match = matchAnticheat(this.txIds);
        if (match == null) {
            return;
        }

        // Notify once per AC name (and at most every 8s if something re-triggers)
        long now = System.currentTimeMillis();
        if (match.equals(this.lastDetectedAc) && (now - this.lastNotifyMs) < 8000L) {
            return;
        }
        this.lastDetectedAc = match;
        this.lastNotifyMs = now;

        ChatUtil.sendFormatted(
                "&8[&ePackLogger&8] &7AntiCheat-Detect: &a" + match
                        + " &8(&7samples=" + this.txIds.size() + "&8)"
        );
    }

    /**
     * Public fingerprints (transaction action short ids on join / early session).
     * Sources: community docs (e.g. PhoenixHaven/Anticheat-Detections) + known patterns.
     * Heuristic only — forks / custom configs may differ.
     */
    private static String matchAnticheat(List<Short> ids) {
        if (ids.size() < 3) {
            return null;
        }

        short a = ids.get(0);
        short b = ids.get(1);
        short c = ids.get(2);

        // Intave: -32768, -32767, -32766 (near Short.MIN_VALUE, ascending)
        if (a == -32768 && b == -32767 && c == -32766) {
            return "Intave";
        }
        // Soft Intave: stays in high-negative band near MIN_VALUE
        if (a <= -32740 && isSequentialAsc(ids, 0, Math.min(5, ids.size()))) {
            return "Intave (likely)";
        }

        // Vulcan 2.7.3+: -23767, -23766, -23765
        if (a == -23767 && b == -23766 && c == -23765) {
            return "Vulcan 2.7.3+";
        }

        // Vulcan 2.7.2-: -30767, -30766, -25767 (third jumps)
        if (a == -30767 && b == -30766 && c == -25767) {
            return "Vulcan 2.7.2-";
        }

        // Old Verus: -30767, -30766, -30765 (strict sequential, unlike Vulcan jump)
        if (a == -30767 && b == -30766 && c == -30765) {
            return "Verus (old)";
        }

        // Grim: 0, -1, -2 (descending from 0)
        if (a == 0 && b == -1 && c == -2) {
            return "Grim";
        }
        // Soft Grim: starts at 0 and counts down
        if (a == 0 && isSequentialDesc(ids, 0, Math.min(5, ids.size()))) {
            return "Grim (likely)";
        }

        // Generic Vulcan-ish: starts near -23767 or -30767 and increments by 1
        if ((a == -23767 || a == -30767) && isSequentialAsc(ids, 0, Math.min(4, ids.size()))) {
            return "Vulcan (likely)";
        }

        return null;
    }

    private static boolean isSequentialAsc(List<Short> ids, int from, int to) {
        for (int i = from + 1; i < to; i++) {
            if (ids.get(i) != ids.get(i - 1) + 1) {
                return false;
            }
        }
        return to - from >= 2;
    }

    private static boolean isSequentialDesc(List<Short> ids, int from, int to) {
        for (int i = from + 1; i < to; i++) {
            if (ids.get(i) != ids.get(i - 1) - 1) {
                return false;
            }
        }
        return to - from >= 2;
    }

    /**
     * Prefer direct accessor; fall back to reflection for obfuscated builds.
     */
    private static short extractActionNumber(S32PacketConfirmTransaction packet) {
        try {
            return packet.getActionNumber();
        } catch (Throwable ignored) {
        }
        try {
            Field f = findActionField(packet.getClass());
            if (f != null) {
                f.setAccessible(true);
                Object v = f.get(packet);
                if (v instanceof Number) {
                    return ((Number) v).shortValue();
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static Field findActionField(Class<?> clazz) {
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (field.getType() == short.class || field.getType() == Short.class) {
                    String n = field.getName().toLowerCase(Locale.ROOT);
                    if (n.contains("action") || n.contains("uid") || n.contains("id") || n.contains("148894")) {
                        return field;
                    }
                }
            }
            for (Field field : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && (field.getType() == short.class || field.getType() == Short.class)) {
                    return field;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    // ── Logging (unchanged behaviour) ────────────────────────────────────────

    private boolean shouldIgnoreSpam(String simpleName) {
        String n = simpleName.toLowerCase(Locale.ROOT);

        if (this.ignoreKeepAlive.getValue()) {
            if (n.contains("keepalive") || n.contains("confirmtransaction")) {
                return true;
            }
        }

        if (this.ignoreMove.getValue()) {
            if (n.contains("c03packetplayer")
                    || n.contains("c04packetplayerposition")
                    || n.contains("c05packetplayerlook")
                    || n.contains("c06packetplayerposlook")
                    || n.contains("s14packetentity")
                    || n.contains("s15packetentityrelmove")
                    || n.contains("s16packetentitylook")
                    || n.contains("s17packetentitylookmove")
                    || n.contains("s18packetentityteleport")
                    || n.contains("s19packetentityheadlook")) {
                return true;
            }
        }
        return false;
    }

    private void logToChat(boolean send, String name, Packet<?> packet, boolean canceled) {
        StringBuilder sb = new StringBuilder();
        if (send) {
            sb.append("&8[&7SEND&8] ");
        } else {
            sb.append("&8[&9RECV&8] ");
        }
        sb.append("&f").append(name);

        if (canceled) {
            sb.append(" &c(canceled)");
        }

        if (this.showFields.getValue()) {
            List<String> fields = this.collectFieldLines(packet);
            if (!fields.isEmpty()) {
                ChatUtil.sendFormatted(sb.toString());
                for (String line : fields) {
                    ChatUtil.sendFormatted("&8  - &b" + line);
                }
                return;
            }
        }

        ChatUtil.sendFormatted(sb.toString());
    }

    private void logToFile(boolean send, String name, Packet<?> packet, boolean canceled) {
        if (this.fileWriter == null) {
            this.openLogFile();
            if (this.fileWriter == null) {
                return;
            }
        }

        try {
            StringBuilder row = new StringBuilder();
            row.append(System.currentTimeMillis()).append(',');
            row.append(send ? "SEND" : "RECV").append(',');
            row.append(name).append(',');
            row.append(canceled).append(',');
            row.append('"');

            if (this.showFields.getValue()) {
                List<FieldEntry> entries = this.collectFields(packet);
                for (int i = 0; i < entries.size(); i++) {
                    FieldEntry e = entries.get(i);
                    if (i > 0) {
                        row.append(';');
                    }
                    row.append(e.name).append(':');
                    if (this.showFieldType.getValue()) {
                        row.append(e.typeName).append('=');
                    }
                    row.append(sanitizeCsv(e.value));
                }
            }

            row.append('"');
            this.fileWriter.write(row.toString());
            this.fileWriter.newLine();
            this.fileWriter.flush();
        } catch (IOException ignored) {
        }
    }

    private List<String> collectFieldLines(Packet<?> packet) {
        List<String> lines = new ArrayList<String>();
        for (FieldEntry e : this.collectFields(packet)) {
            if (this.showFieldType.getValue()) {
                lines.add(e.name + "&7: &e" + e.typeName + " &7= &f" + e.value);
            } else {
                lines.add(e.name + " &7= &f" + e.value);
            }
        }
        return lines;
    }

    private List<FieldEntry> collectFields(Packet<?> packet) {
        List<FieldEntry> list = new ArrayList<FieldEntry>();
        Class<?> clazz = packet.getClass();
        while (clazz != null && clazz != Object.class) {
            Field[] declared = clazz.getDeclaredFields();
            for (Field field : declared) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(packet);
                    String typeName = String.valueOf(field.getGenericType());
                    list.add(new FieldEntry(field.getName(), typeName, value == null ? "null" : String.valueOf(value)));
                } catch (Throwable t) {
                    list.add(new FieldEntry(field.getName(), "?", "<inaccessible>"));
                }
            }
            clazz = clazz.getSuperclass();
        }
        return list;
    }

    private void openLogFile() {
        this.closeLogFile();
        try {
            File dir = new File(mc.mcDataDir, "myau/packet-logger");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            this.logFile = new File(dir, FILE_NAME_FMT.format(new Date()) + ".csv");
            this.fileWriter = new BufferedWriter(new FileWriter(this.logFile, true));
            this.fileWriter.write("timestamp,direction,packet,canceled,fields");
            this.fileWriter.newLine();
            this.fileWriter.flush();
            ChatUtil.sendFormatted("&8[&ePackLogger&8] &7Logging to &f" + this.logFile.getAbsolutePath());
        } catch (IOException e) {
            this.fileWriter = null;
            this.logFile = null;
            ChatUtil.sendFormatted("&8[&ePackLogger&8] &cFailed to open log file");
        }
    }

    private void closeLogFile() {
        if (this.fileWriter != null) {
            try {
                this.fileWriter.close();
            } catch (IOException ignored) {
            }
            this.fileWriter = null;
        }
        this.logFile = null;
    }

    private static String sanitizeCsv(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace('"', '\'').replace('\n', ' ').replace('\r', ' ');
    }

    private static final class FieldEntry {
        final String name;
        final String typeName;
        final String value;

        FieldEntry(String name, String typeName, String value) {
            this.name = name;
            this.typeName = typeName;
            this.value = value;
        }
    }
}
