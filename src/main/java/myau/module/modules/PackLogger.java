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
 */
public class PackLogger extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final SimpleDateFormat FILE_NAME_FMT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);

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
    /** Skip keep-alive / confirm-transaction spam */
    public final BooleanProperty ignoreKeepAlive = new BooleanProperty("ignore-keepalive", true);

    private File logFile;
    private BufferedWriter fileWriter;

    public PackLogger() {
        super("PackLogger", false, true, "Prints packets and fields (debug, from LiquidBounce PacketLogger)");
    }

    @Override
    public void onEnabled() {
        if (this.output.getValue() == 1 || this.output.getValue() == 2) {
            this.openLogFile();
        }
    }

    @Override
    public void onDisabled() {
        this.closeLogFile();
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

        int dir = this.direction.getValue();
        if (dir == 1 && !isSend) {
            return;
        }
        if (dir == 2 && !isReceive) {
            return;
        }

        Packet<?> packet = event.getPacket();
        if (packet == null) {
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

    private boolean shouldIgnoreSpam(String simpleName) {
        String n = simpleName.toLowerCase(Locale.ROOT);

        if (this.ignoreKeepAlive.getValue()) {
            if (n.contains("keepalive") || n.contains("confirmtransaction")) {
                return true;
            }
        }

        if (this.ignoreMove.getValue()) {
            // Client movement (C03–C06) and common entity move updates
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
