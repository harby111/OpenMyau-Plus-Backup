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
 * While enabled: module keybind triggers a human-like ender pearl throw
 * (hotbar search → switch → right-click look direction → optional switch back).
 * While disabled: keybind does nothing (does not enable the module).
 * Enable/disable via ClickGUI or command only.
 */
public class AutoPearl extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private enum Stage {
        IDLE,
        SWITCH_TO,
        WAIT_THROW,
        THROW,
        WAIT_SWITCH_BACK,
        SWITCH_BACK
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
        super("AutoPearl", false, false,
                "While on: bind throws pearl from hotbar. While off: bind does nothing");
    }

    /**
     * ModuleManager calls toggle() on the bound key.
     * Enabled → start a throw (do not disable).
     * Disabled → ignore key (do not enable).
     */
    @Override
    public boolean toggle() {
        if (!this.isEnabled()) {
            return false;
        }
        this.requestThrow();
        return false;
    }

    @Override
    public void onEnabled() {
        this.resetState();
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer != null) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        }
        this.resetState();
    }

    /** Queue a throw if idle; ignore if already mid-sequence. */
    private void requestThrow() {
        if (this.stage != Stage.IDLE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.currentScreen != null) {
            ChatUtil.sendFormatted("&8[&eAutoPearl&8] &cClose any GUI first");
            return;
        }

        int slot = this.findPearlHotbarSlot();
        if (slot == -1) {
            ChatUtil.sendFormatted("&8[&eAutoPearl&8] &cNo ender pearl in hotbar");
            return;
        }

        this.pearlSlot = slot;
        this.originalSlot = mc.thePlayer.inventory.currentItem;

        if (slot == this.originalSlot) {
            this.stage = Stage.WAIT_THROW;
            this.stageTicks = 0;
            this.waitTarget = this.rollDelay(this.switchDelay.getValue());
        } else {
            this.stage = Stage.SWITCH_TO;
            this.stageTicks = 0;
            this.waitTarget = 0;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (this.stage == Stage.IDLE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.resetState();
            return;
        }
        if (mc.currentScreen != null) {
            ChatUtil.sendFormatted("&8[&eAutoPearl&8] &cCancelled (GUI open)");
            this.resetState();
            return;
        }

        switch (this.stage) {
            case SWITCH_TO:
                this.selectSlot(this.pearlSlot);
                this.stage = Stage.WAIT_THROW;
                this.stageTicks = 0;
                this.waitTarget = this.rollDelay(this.switchDelay.getValue());
                break;

            case WAIT_THROW:
                this.stageTicks++;
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
                    ChatUtil.sendFormatted("&8[&eAutoPearl&8] &cPearl missing, abort");
                    this.resetState();
                    break;
                }
                KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindUseItem.getKeyCode());

                if (this.switchBack.getValue()
                        && this.originalSlot >= 0
                        && this.originalSlot != this.pearlSlot) {
                    this.stage = Stage.WAIT_SWITCH_BACK;
                    this.stageTicks = 0;
                    this.waitTarget = this.rollDelay(this.switchBackDelay.getValue());
                } else {
                    this.resetState();
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
                this.resetState();
                break;

            default:
                this.resetState();
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
        int delta = RandomUtil.nextInt(0, 2) - 1;
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
