package myau.module.modules;

/**
 * Adapted from InjectMyau (Dotoryy) Clutch for OpenMyau-Plus-Backup.
 * Category: Player
 *
 * Differences from upstream:
 * - TickEvent instead of UpdateEvent (no setRotation on event)
 * - Silent via C05 packets + RotationUtil; Legit via RotationManager
 * - select-key is IntProperty (LWJGL keycode, 0 = none) instead of KeyProperty
 * - Property constructors match OpenMyau (no step argument)
 * - MouseButton cancel omitted (not required for place flow)
 */

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.TextProperty;
import myau.util.KeyBindUtil;
import myau.util.PacketUtil;
import myau.util.RotationUtil;
import net.minecraft.network.play.client.C03PacketPlayer;
import org.lwjgl.input.Keyboard;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Clutch extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double HALF_WIDTH = 0.3;
    private static final double[][] CORNERS =
            {{-HALF_WIDTH, -HALF_WIDTH}, {HALF_WIDTH, -HALF_WIDTH},
             {-HALF_WIDTH, HALF_WIDTH}, {HALF_WIDTH, HALF_WIDTH}};
    private static final Map<String, Integer> BLOCK_SCORE = new HashMap<String, Integer>();
    private static final int ROTATION_PRIORITY = 3;
    private static final int AUTO_CLUTCH_ARM_TICKS = 40;

    static {
        BLOCK_SCORE.put("obsidian", 0);
        BLOCK_SCORE.put("end_stone", 1);
        BLOCK_SCORE.put("glass", 3);
        BLOCK_SCORE.put("stained_glass", 3);
        BLOCK_SCORE.put("hardened_clay", 4);
        BLOCK_SCORE.put("stained_hardened_clay", 4);
        // Same priority tier as wool (prefer-weak-blocks uses higher score when enabled)
        BLOCK_SCORE.put("wool", 5);
        BLOCK_SCORE.put("stone", 5);
        BLOCK_SCORE.put("cobblestone", 5);
        BLOCK_SCORE.put("planks", 5);   // oak/all wood planks (1.8.9)
        BLOCK_SCORE.put("log", 5);      // oak + other overworld logs
        BLOCK_SCORE.put("log2", 5);     // acacia / dark oak
    }

    public final FloatProperty reach = new FloatProperty("reach", 4.5F, 0.5F, 4.5F);
    public final IntProperty speed = new IntProperty("speed", 8, 0, 100);
    public final IntProperty snapbackSpeed = new IntProperty("snapback-speed", 12, 0, 100);
    public final IntProperty maxDistance = new IntProperty("max-distance", 10, 0, 20);
    public final IntProperty rotationTolerance = new IntProperty("rotation-tolerance", 25, 20, 100);
    public final IntProperty minimumFallDistance =
            new IntProperty("minimum-fall-distance", 10, 3, 20);
    public final BooleanProperty simulateFuturePosition =
            new BooleanProperty("simulate-future-position", true);
    public final BooleanProperty autoClutch = new BooleanProperty("auto-clutch", false);
    public final BooleanProperty requireVoid = new BooleanProperty("require-void", false);
    public final BooleanProperty silent = new BooleanProperty("silent", true);
    public final BooleanProperty preferWeakBlocks = new BooleanProperty("prefer-weak-blocks", false);
    /** LWJGL key code; 0 = none (auto-clutch / module only). Hold to clutch manually. */
    public final IntProperty selectKey = new IntProperty("select-key", 0, 0, 255);
    public final TextProperty itemBlacklist = new TextProperty("item-blacklist", "");

    private BlockPos placeAtBlock;
    private EnumFacing hitSide;
    private Vec3 hitVec;
    private boolean placing;
    private boolean slotWasSwapped;
    private boolean autoClickerWasOn;
    private int prevSlot = -1;
    private int plannedSlot = -1;
    private float aimYaw;
    private float aimPitch;
    private BlockPos targetHitPos;
    private EnumFacing targetSide;
    private boolean hasAim;
    private boolean resetting;
    private BlockPos lastPlaced;
    private int clutchBlocksPlaced;
    private boolean autoClutchActive;
    private boolean autoClutchChecking;
    private int autoClutchCheckCounter;
    private boolean autoClutchLandedGuard;
    private int autoClutchLandedTick;
    private int prevHurtTime = -1;
    private float serverYaw;
    private float serverPitch;
    private float restoreYaw;
    private float restorePitch;
    private boolean restoreCaptured;

    public Clutch() {
        super("Clutch", false, false, "Block clutch while falling (manual key or auto after knockback)");
    }

    @Override
    public void onEnabled() {
        this.hasAim = false;
        this.resetting = false;
        this.clutchBlocksPlaced = 0;
        this.autoClutchActive = false;
        this.autoClutchChecking = false;
        this.autoClutchCheckCounter = 0;
        this.autoClutchLandedGuard = false;
        this.autoClutchLandedTick = 0;
        this.prevHurtTime = -1;
        this.restoreCaptured = false;
    }

    @Override
    public void onDisabled() {
        this.clearAim(false);
        this.disablePlacing(true);
        this.autoClutchActive = false;
        this.autoClutchChecking = false;
        this.autoClutchLandedGuard = false;
    }

    @EventTarget(Priority.HIGH)
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null
                || mc.theWorld == null) {
            return;
        }
        BedNuker bedNuker = (BedNuker) Myau.moduleManager.modules.get(BedNuker.class);
        if (bedNuker != null && bedNuker.isEnabled()) {
            try {
                if (bedNuker.isReady()) {
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        float baseYaw = RotationUtil.customRots ? RotationUtil.serverYaw : mc.thePlayer.rotationYaw;
        float basePitch = RotationUtil.customRots ? RotationUtil.serverPitch : mc.thePlayer.rotationPitch;
        this.serverYaw = baseYaw;
        this.serverPitch = basePitch;
        this.runPrePlayerInteract();
        if (mc.currentScreen != null) {
            this.disablePlacing(false);
        }
        if (this.resetting) {
            if (this.silent.getValue() || !this.restoreCaptured) {
                this.aimYaw = mc.thePlayer.rotationYaw;
                this.aimPitch = mc.thePlayer.rotationPitch;
            } else {
                this.aimYaw = this.restoreYaw;
                this.aimPitch = this.restorePitch;
            }
            float[] smoothed =
                    this.getRotationsSmoothed(baseYaw, basePitch, this.aimYaw, this.aimPitch, true);
            if (Math.abs(MathHelper.wrapAngleTo180_float(smoothed[0] - this.aimYaw)) < 0.5F
                    && Math.abs(smoothed[1] - this.aimPitch) < 0.5F) {
                this.resetting = false;
                this.restoreCaptured = false;
                this.restoreInputsAndAutoClicker();
            } else {
                this.submitRotation(smoothed);
            }
            return;
        }
        if (!this.hasAim) {
            return;
        }
        float[] smoothed =
                this.getRotationsSmoothed(baseYaw, basePitch, this.aimYaw, this.aimPitch, false);
        this.submitRotation(smoothed);
        this.tryPlace(smoothed);
    }

    private void submitRotation(float[] smoothed) {
        float yaw = smoothed[0];
        float pitch = MathHelper.clamp_float(smoothed[1], -90.0F, 90.0F);
        if (this.silent.getValue()) {
            PacketUtil.sendPacket(new C03PacketPlayer.C05PacketPlayerLook(
                    yaw, pitch, mc.thePlayer.onGround));
            RotationUtil.serverYaw = yaw;
            RotationUtil.serverPitch = pitch;
            RotationUtil.customRots = true;
        } else {
            Myau.rotationManager.setRotation(yaw, pitch, ROTATION_PRIORITY, true);
            RotationUtil.serverYaw = yaw;
            RotationUtil.serverPitch = pitch;
            RotationUtil.customRots = true;
        }
    }

    private void tryPlace(float[] smoothed) {
        if (!this.placing || this.targetHitPos == null || !this.canClutchHere()) {
            return;
        }
        int maxBlocks = this.maxDistance.getValue();
        if (maxBlocks != 0 && this.clutchBlocksPlaced >= maxBlocks) {
            return;
        }
        double tolerance = this.rotationTolerance.getValue();
        if (Math.abs(MathHelper.wrapAngleTo180_float(smoothed[0] - this.serverYaw)) > tolerance
                || Math.abs(smoothed[1] - this.serverPitch) > tolerance) {
            return;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            return;
        }
        MovingObjectPosition mop = RotationUtil.rayTrace(
                smoothed[0], smoothed[1], this.reach.getValue(), 1.0F);
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || mop.getBlockPos() == null || mop.sideHit == null) {
            return;
        }
        if (!this.targetHitPos.equals(mop.getBlockPos()) || this.targetSide != mop.sideHit) {
            return;
        }
        if (mop.sideHit == EnumFacing.DOWN || !canPlaceBlockOnSide(held, mop.getBlockPos(), mop.sideHit)) {
            return;
        }
        this.placeAtBlock = mop.getBlockPos();
        this.hitSide = mop.sideHit;
        this.hitVec = mop.hitVec;
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held,
                mop.getBlockPos(), mop.sideHit, mop.hitVec)) {
            if (mop.sideHit != EnumFacing.UP) {
                this.clutchBlocksPlaced++;
            }
            this.lastPlaced = mop.getBlockPos();
            mc.thePlayer.swingItem();
        }
    }

    private void runPrePlayerInteract() {
        if (mc.thePlayer.onGround) {
            this.clutchBlocksPlaced = 0;
        }
        this.updateAutoClutch(mc.thePlayer.ticksExisted);
        boolean keyHeld = this.selectKey.getValue() > 0
                && Keyboard.isKeyDown(this.selectKey.getValue());
        boolean active = keyHeld || this.autoClutchActive;
        if (mc.currentScreen != null || !active || !this.canClutchHere()) {
            this.clearAim(true);
            this.disablePlacing(false);
            return;
        }
        BlockPos below = new BlockPos(MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ));
        if (!canPlaceThrough(below)) {
            this.disablePlacing(false);
            return;
        }
        int slot = this.pickBlockSlot();
        if (slot == -1) {
            this.disablePlacing(false);
            return;
        }
        this.plannedSlot = slot;
        AimResult target = this.clutchAim();
        if (target != null) {
            this.targetHitPos = target.ray.getBlockPos();
            this.targetSide = target.ray.sideHit;
            this.aimYaw = target.yaw;
            this.aimPitch = target.pitch;
            this.hasAim = true;
            this.resetting = false;
        }
        if (this.hasAim && !this.placing) {
            this.enablePlacing();
        }
        if (this.placing || this.resetting || this.hasAim) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            this.equipPlannedSlot();
        }
    }

    // --- remainder of logic identical to prepared file; truncated upload will fail ---
}
