package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.KeyBindUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;

public class MLG extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty pickUp = new BooleanProperty("pick-up", true);
    public final BooleanProperty swapSlot = new BooleanProperty("swap-slot", true);
    public final BooleanProperty cameraMove = new BooleanProperty("Camera-Move", true);
    public final FloatProperty minFall = new FloatProperty("min-fall", 3.0F, 2.0F, 10.0F);
    public final FloatProperty aimPitch = new FloatProperty("aim-pitch", 90.0F, 70.0F, 90.0F,
            this.cameraMove::getValue);
    public final FloatProperty pitchSpeed = new FloatProperty("pitch-speed", 12.0F, 2.0F, 30.0F,
            this.cameraMove::getValue);
    public final FloatProperty requiredPitch = new FloatProperty("required-pitch", 80.0F, 60.0F, 90.0F,
            () -> !this.cameraMove.getValue());
    public final IntProperty switchDelay = new IntProperty("switch-delay", 1, 0, 5);
    public final IntProperty placeDelay = new IntProperty("place-delay", 0, 0, 5);
    public final IntProperty pickUpDelay = new IntProperty("pick-up-delay", 3, 1, 15,
            this.pickUp::getValue);
    public final IntProperty switchBackDelay = new IntProperty("switch-back-delay", 2, 0, 10);

    private enum Stage {
        IDLE,
        SWITCH_TO,
        WAIT_SWITCH,
        AIM,
        WAIT_PLACE,
        PLACE,
        WAIT_PICKUP,
        PICKUP,
        WAIT_SWITCH_BACK,
        SWITCH_BACK
    }

    private Stage stage = Stage.IDLE;
    private int stageTicks;
    private int waitTarget;
    private int originalSlot = -1;
    private int waterSlot = -1;
    private long lastPlaceMs;

    public MLG() {
        super("MLG", false, false,
                "Legit water MLG: real use key + optional camera aim down");
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
            if (this.originalSlot >= 0 && this.originalSlot <= 8) {
                this.selectSlot(this.originalSlot);
            }
        }
        this.resetState();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.resetState();
            return;
        }
        if (mc.currentScreen != null || mc.isGamePaused()
                || mc.thePlayer.capabilities.isFlying
                || mc.thePlayer.capabilities.isCreativeMode) {
            if (this.stage != Stage.IDLE) {
                this.resetState();
            }
            return;
        }

        if (this.stage == Stage.IDLE) {
            if (this.isFallingDangerously()) {
                int slot = this.findHotbarSlot(Items.water_bucket);
                if (slot == -1) {
                    return;
                }
                this.waterSlot = slot;
                this.originalSlot = mc.thePlayer.inventory.currentItem;
                if (this.swapSlot.getValue() && slot != this.originalSlot) {
                    this.stage = Stage.SWITCH_TO;
                    this.stageTicks = 0;
                } else if (this.isHolding(Items.water_bucket)) {
                    this.stage = Stage.AIM;
                    this.stageTicks = 0;
                } else if (this.swapSlot.getValue()) {
                    this.stage = Stage.SWITCH_TO;
                    this.stageTicks = 0;
                }
            }
            return;
        }

        switch (this.stage) {
            case SWITCH_TO:
                this.selectSlot(this.waterSlot);
                this.stage = Stage.WAIT_SWITCH;
                this.stageTicks = 0;
                this.waitTarget = this.switchDelay.getValue();
                break;

            case WAIT_SWITCH:
                this.stageTicks++;
                if (mc.thePlayer.inventory.currentItem != this.waterSlot) {
                    this.selectSlot(this.waterSlot);
                }
                if (this.stageTicks >= this.waitTarget) {
                    this.stage = Stage.AIM;
                    this.stageTicks = 0;
                }
                break;

            case AIM:
                this.stageTicks++;
                if (!this.isHolding(Items.water_bucket)) {
                    if (this.swapSlot.getValue()) {
                        this.selectSlot(this.waterSlot);
                    } else {
                        this.resetState();
                        break;
                    }
                }
                if (this.cameraMove.getValue()) {
                    this.stepPitchToward(this.aimPitch.getValue());
                    if (mc.thePlayer.rotationPitch >= this.aimPitch.getValue() - 2.0F
                            || this.stageTicks > 40) {
                        this.stage = Stage.WAIT_PLACE;
                        this.stageTicks = 0;
                        this.waitTarget = this.placeDelay.getValue();
                    }
                } else {
                    if (mc.thePlayer.rotationPitch >= this.requiredPitch.getValue()) {
                        this.stage = Stage.WAIT_PLACE;
                        this.stageTicks = 0;
                        this.waitTarget = this.placeDelay.getValue();
                    } else if (!this.isFallingDangerously() && mc.thePlayer.onGround) {
                        this.resetState();
                    }
                }
                break;

            case WAIT_PLACE:
                this.stageTicks++;
                if (this.cameraMove.getValue()) {
                    this.stepPitchToward(this.aimPitch.getValue());
                }
                if (this.stageTicks >= this.waitTarget) {
                    this.stage = Stage.PLACE;
                    this.stageTicks = 0;
                }
                break;

            case PLACE:
                if (!this.isHolding(Items.water_bucket)) {
                    this.resetState();
                    break;
                }
                if (this.cameraMove.getValue()) {
                    this.stepPitchToward(this.aimPitch.getValue());
                }
                if (!this.canPlaceOnGround()) {
                    if (!this.isFallingDangerously() || this.stageTicks > 10) {
                        this.resetState();
                    } else {
                        this.stageTicks++;
                    }
                    break;
                }
                KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindUseItem.getKeyCode());
                this.lastPlaceMs = System.currentTimeMillis();
                if (this.pickUp.getValue()) {
                    this.stage = Stage.WAIT_PICKUP;
                    this.stageTicks = 0;
                    this.waitTarget = this.pickUpDelay.getValue();
                } else if (this.swapSlot.getValue()
                        && this.originalSlot >= 0
                        && this.originalSlot != this.waterSlot) {
                    this.stage = Stage.WAIT_SWITCH_BACK;
                    this.stageTicks = 0;
                    this.waitTarget = this.switchBackDelay.getValue();
                } else {
                    this.resetState();
                }
                break;

            case WAIT_PICKUP:
                this.stageTicks++;
                if (this.cameraMove.getValue()) {
                    this.stepPitchToward(this.aimPitch.getValue());
                }
                if (this.stageTicks >= this.waitTarget) {
                    this.stage = Stage.PICKUP;
                    this.stageTicks = 0;
                }
                break;

            case PICKUP:
                if (this.isHolding(Items.bucket) || this.isHolding(Items.water_bucket)) {
                    KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindUseItem.getKeyCode());
                }
                if (this.swapSlot.getValue()
                        && this.originalSlot >= 0
                        && this.originalSlot != mc.thePlayer.inventory.currentItem) {
                    this.stage = Stage.WAIT_SWITCH_BACK;
                    this.stageTicks = 0;
                    this.waitTarget = this.switchBackDelay.getValue();
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
                if (this.originalSlot >= 0 && this.originalSlot <= 8) {
                    this.selectSlot(this.originalSlot);
                }
                this.resetState();
                break;

            default:
                this.resetState();
                break;
        }
    }

    private boolean isFallingDangerously() {
        if (mc.thePlayer.onGround || mc.thePlayer.capabilities.allowFlying) {
            return false;
        }
        return mc.thePlayer.fallDistance >= this.minFall.getValue()
                && mc.thePlayer.motionY < -0.1;
    }

    private boolean canPlaceOnGround() {
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null) {
            mop = mc.thePlayer.rayTrace(mc.playerController.getBlockReachDistance(), 1.0F);
        }
        if (mop == null || mop.typeOfHit != MovingObjectType.BLOCK) {
            return false;
        }
        return mop.sideHit == EnumFacing.UP;
    }

    private void stepPitchToward(float targetPitch) {
        float cur = mc.thePlayer.rotationPitch;
        float speed = this.pitchSpeed.getValue();
        float next;
        if (cur < targetPitch) {
            next = Math.min(targetPitch, cur + speed);
        } else {
            next = Math.max(targetPitch, cur - speed);
        }
        next = MathHelper.clamp_float(next, -90.0F, 90.0F);
        Myau.rotationManager.setRotation(mc.thePlayer.rotationYaw, next, 0, false);
    }

    private boolean isHolding(Item item) {
        ItemStack stack = mc.thePlayer.getHeldItem();
        return stack != null && stack.getItem() == item;
    }

    private int findHotbarSlot(Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
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

    private void resetState() {
        this.stage = Stage.IDLE;
        this.stageTicks = 0;
        this.waitTarget = 0;
        this.originalSlot = -1;
        this.waterSlot = -1;
    }
}