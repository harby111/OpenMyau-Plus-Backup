package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.event.HoverEvent;
import net.minecraft.network.Packet;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;

public class LatencyAlerts extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final FloatProperty interval = new FloatProperty("alert-interval", 3.0F, 0.0F, 5.0F);
    public final FloatProperty highLatency = new FloatProperty("high-latency", 0.5F, 0.1F, 5.0F);
    public final BooleanProperty ignoreLimbo = new BooleanProperty("ignore-limbo", true);

    private long lastPacketTime = 0L;
    private long lastAlert = 0L;
    private Packet<?> lastPacket = null;

    public LatencyAlerts() {
        super("LatencyAlerts", false, true, "Alerts when inbound packets stop (packet loss)");
    }

    @Override
    public void onEnabled() {
        long now = System.currentTimeMillis();
        this.lastPacketTime = now;
        this.lastAlert = now;
        this.lastPacket = null;
    }

    @Override
    public void onDisabled() {
        this.lastPacketTime = 0L;
        this.lastAlert = 0L;
        this.lastPacket = null;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (event.getType() != EventType.RECEIVE) {
            return;
        }
        this.lastPacketTime = System.currentTimeMillis();
        this.lastPacket = event.getPacket();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }

        if (mc.thePlayer == null || mc.theWorld == null) {
            long now = System.currentTimeMillis();
            this.lastPacketTime = now;
            this.lastAlert = now;
            this.lastPacket = null;
            return;
        }

        if (mc.isSingleplayer() || (this.ignoreLimbo.getValue() && inLimbo())) {
            long now = System.currentTimeMillis();
            this.lastPacketTime = now;
            this.lastAlert = now;
            return;
        }

        long currentMs = System.currentTimeMillis();
        long highLatencyMs = (long) (this.highLatency.getValue() * 1000.0F);
        long intervalMs = (long) (this.interval.getValue() * 1000.0F);

        if (currentMs - this.lastPacketTime >= highLatencyMs
                && currentMs - this.lastAlert >= intervalMs) {
            long msSinceLastPacket = Math.abs(currentMs - this.lastPacketTime);
            sendAlert(msSinceLastPacket);
            this.lastAlert = currentMs;
        }
    }

    private void sendAlert(long msSinceLastPacket) {
        String packetName = this.lastPacket == null ? "Unknown" : this.lastPacket.getClass().getSimpleName();
        String timeString = new SimpleDateFormat("h:mm:ss a").format(new Date(this.lastPacketTime));

        String hoverPlain = "Last packet: " + packetName + "\nReceived at: " + timeString;

        ChatComponentText root = new ChatComponentText("");
        root.appendSibling(colorText("[LatencyAlerts] ", EnumChatFormatting.GRAY));
        root.appendSibling(colorText("Packet loss detected: ", EnumChatFormatting.GRAY));

        ChatComponentText msPart = colorText(String.valueOf(msSinceLastPacket), EnumChatFormatting.RED);
        ChatStyle style = new ChatStyle();
        style.setChatHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText(hoverPlain)
        ));
        msPart.setChatStyle(style);

        root.appendSibling(msPart);
        root.appendSibling(colorText("ms", EnumChatFormatting.GRAY));

        ChatUtil.send(root);
    }

    private static ChatComponentText colorText(String text, EnumChatFormatting color) {
        ChatComponentText c = new ChatComponentText(text);
        c.getChatStyle().setColor(color);
        return c;
    }

    private boolean inLimbo() {
        if (mc.theWorld == null) {
            return false;
        }
        try {
            if ("The End".equals(mc.theWorld.provider.getDimensionName())) {
                Scoreboard board = mc.theWorld.getScoreboard();
                if (board == null) {
                    return true;
                }
                ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
                if (objective == null) {
                    return true;
                }
                Collection<?> scores = board.getSortedScores(objective);
                if (scores == null || scores.isEmpty()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}