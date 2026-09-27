package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.util.ChatUtil;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * Misc module: while enabled, prints every received S32PacketConfirmTransaction
 * action id to chat.
 */
public class Transactions extends Module {

    public Transactions() {
        super("Transactions", false, true, "Logs inbound ConfirmTransaction action ids");
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (event.getType() != EventType.RECEIVE) {
            return;
        }

        Packet<?> packet = event.getPacket();
        if (!(packet instanceof S32PacketConfirmTransaction)) {
            return;
        }

        short transactionId = extractActionNumber((S32PacketConfirmTransaction) packet);
        ChatUtil.sendFormatted(
                "&c[Transaction ID]: &f" + transactionId
        );
    }

    /**
     * Prefer getActionNumber(); fall back to reflection (obfuscated / SRG builds).
     * Equivalent of the original packet.func_148890_d().
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
                    if (n.contains("action") || n.contains("uid") || n.contains("id")
                            || n.contains("148890") || n.contains("148894")) {
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
}
