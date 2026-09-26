package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.ChatUtil;
import myau.util.ItemUtil;
import myau.util.KeyBindUtil;
import myau.util.RandomUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

/**
 * One-shot ender pearl throw: find pearl in hotbar, switch, throw looking direction,
 * optionally switch back, then auto-disable. Timing mimics a normal player.
 */
public class AutoPearl extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private enum Stage {
        IDLE,
        SWITCH_TO,
        WAIT_THROW,
        THROW,
        WAIT_SWITCH_BACK,
        SWITCH_BACK,
        FINISH
    }

    /** Extra ticks after selecting the pearl before throwing (human reaction). */
    public final IntProperty switchDelay = new IntProperty("switch-delay", 2, 0, 10);
    /** Extra ticks after the throw before switching back. */
    public final IntProperty switchBackDelay = new IntProperty("switch-back-delay", 2, 0, 10);
    /** Restore the previous hotbar slot after throwing. */
    public final BooleanProperty switchBack = new BooleanProperty("switch-back", true);
    /** Randomize delays by ±1 tick for a more human rhythm. */
    public final BooleanProperty humanize = new BooleanProperty("humanize", true);

    private Stage stage = Stage.IDLE;
    private int stageTicks;
    private int waitTarget;
    private int originalSlot = -1;
    private int pearlSlot = -1;

    public AutoPearl() {
        super("AutoPearl", false, false, "Throw an ender pearl from the hotbar once, then disable");
    }

    @Override
    public void onEnabled() {
        this.resetState();
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.setEnabled(false);
            return;
        }
        if (mc.currentScreen != null) {
            ChatUtil.sendFormatted("&8[&eAutoPearl&8] &cClose any GUI first");
            this.setEnabled(false);
            return;
        }

        int slot = this.findPearlHotbarSlot();
        if (slot == -1) {
            ChatUtil.sendFormatted("&8[&eAutoPearl&8] &cNo ender pearl in hotbar");
            this.setEnabled(false);
            return;
        }

        this.pearlSlot = slot;
        this.originalSlot = mc.thePlayer.inventory.currentItem;

        if (slot == this.originalSlot) {
            // Already holding pearl — wait a short human beat then throw
            this.stage = Stage.WAIT_THROW;
            this.stageTicks = 0;
            this.waitTarget = this.rollDelay(this.switchDelay.getValue());
        } else {
            this.stage = Stage.SWITCH_TO;
            this.stageTicks = 0;
            this.waitTarget = 0;
        }
    }

    @Override
    public void onDisabled() {
        // Release use key if somehow still held
        if (mc.thePlayer != null) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        }
        this.resetState();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.setEnabled(false);
            return;
        }
        if (mc.currentScreen != null) {
            ChatUtil.sendFormatted("&8[&eAutoPearl&8] &cCancelled (GUI open)");
            this.setEnabled(false);
            return;
        }

        switch (this.stage) {
            case IDLE:
                // Should not stay enabled in IDLE
                this.setEnabled(false);
                break;

            case SWITCH_TO:
                this.selectSlot(this.pearlSlot);
                this.stage = Stage.WAIT_THROW;
                this.stageTicks = 0;
                this.waitTarget = this.rollDelay(this.switchDelay.getValue());
                break;

            case WAIT_THROW:
                this.stageTicks++;
                // Keep selection stable while waiting
                if (mc.thePlayer.inventory.currentItem != this.pearlSlot) {
                    this.selectSlot(this.pearlSlot);
                }
                if (this.stageTicks >= this.waitTarget) {
                    this.stage = Stage.THROW;
                    this.stageTicks = 0;
                }
                break;

            case THROW:
                if (!ItemUtil.isEnderPearl(mc.thePlayer.getHeldItem())) {
                    // Pearl gone or wrong item
                    ChatUtil.sendFormatted("&8[&eAutoPearl&8] &cPearl missing, abort");
                    this.setEnabled(false);
                    break;
                }
                // Single right-click — same path a player uses (throws looking direction)
                KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindUseItem.getKeyCode());

                if (this.switchBack.getValue()
                        && this.originalSlot >= 0
                        && this.originalSlot != this.pearlSlot) {
                    this.stage = Stage.WAIT_SWITCH_BACK;
                    this.stageTicks = 0;
                    this.waitTarget = this.rollDelay(this.switchBackDelay.getValue());
                } else {
                    this.stage = Stage.FINISH;
                }
                break;

            case WAIT_SWITCH_BACK:
                this.stageTicks++;
                if (this.stageTicks >= this.waitTarget) {
                    this.stage = Stage.SWITCH_BACK;
                }
                break;

            case SWITCH_BACK:
                this.selectSlot(this.originalSlot);
                this.stage = Stage.FINISH;
                break;

            case FINISH:
                this.setEnabled(false);
                break;

            default:
                this.setEnabled(false);
                break;
        }
    }

    private void selectSlot(int slot) {
        if (slot < 0 || slot > 8 || mc.thePlayer == null) {
            return;
        }
        if (mc.thePlayer.inventory.currentItem == slot) {
            return;
        }
        // Client-visible hotbar switch (sends C09 via vanilla update)
        mc.thePlayer.inventory.currentItem = slot;
        mc.playerController.updateController();
    }

    private int findPearlHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (ItemUtil.isEnderPearl(stack)) {
                return i;
            }
        }
        return -1;
    }

    private int rollDelay(int base) {
        if (base <= 0) {
            return 0;
        }
        if (!this.humanize.getValue()) {
            return base;
        }
        // ±1 tick when base >= 1
        int delta = RandomUtil.nextInt(0, 2) - 1; // -1, 0, or 1
        return Math.max(0, base + delta);
    }

    private void resetState() {
        this.stage = Stage.IDLE;
        this.stageTicks = 0;
        this.waitTarget = 0;
        this.originalSlot = -1;
        this.pearlSlot = -1;
    }
}
