package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.BlockUtil;
import myau.util.ChatUtil;
import myau.util.ItemUtil;
import myau.util.KeyBindUtil;
import myau.util.PacketUtil;
import myau.util.RandomUtil;
import myau.util.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/**
 * While enabled: module keybind triggers a human-like ender pearl throw
 * (hotbar search → switch → right-click → optional switch back).
 * Auto-Throw: aim at nearest safe solid landing instead of look direction.
 * While disabled: keybind does nothing.
 */
public class AutoPearl extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Horizontal search radius for Auto-Throw landing spots. */
    private static final int SEARCH_RANGE = 16;
    /** Prefer landings at least this far (blocks) so pearl travel is useful. */
    private static final double MIN_LANDING_DIST = 2.5;
    /** Max vertical distance below player to scan. */
    private static final int SEARCH_DOWN = 24;
    /** Max vertical distance above player to scan. */
    private static final int SEARCH_UP = 8;
    /** Approximate pearl speed for mild gravity pitch compensation. */
    private static final double PEARL_SPEED = 1.5;
    private static final double PEARL_GRAVITY = 0.03;

    private enum Stage {
        IDLE,
        SWITCH_TO,
        WAIT_THROW,
        THROW,
        WAIT_SWITCH_BACK,
        SWITCH_BACK
    }

    public final IntProperty switchDelay = new IntProperty("switch-delay", 2, 0, 10);
    public final IntProperty switchBackDelay = new IntProperty("switch-back-delay", 2, 0, 10);
    public final BooleanProperty switchBack = new BooleanProperty("switch-back", true);
    public final BooleanProperty humanize = new BooleanProperty("humanize", true);
    /**
     * When true: find nearest safe solid block and aim the pearl there
     * (void escape). When false: throw in current look direction.
     */
    public final BooleanProperty autoThrow = new BooleanProperty("Auto-Throw", false);

    private Stage stage = Stage.IDLE;
    private int stageTicks;
    private int waitTarget;
    private int originalSlot = -1;
    private int pearlSlot = -1;
    /** Landing aim point when Auto-Throw is active; null = look direction. */
    private Vec3 aimTarget = null;

    public AutoPearl() {
        super("AutoPearl", false, false,
                "While on: bind throws pearl. Auto-Throw aims at safe ground");
    }

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

        this.aimTarget = null;
        if (this.autoThrow.getValue()) {
            BlockPos landing = this.findSafeLanding();
            if (landing == null) {
                ChatUtil.sendFormatted("&8[&eAutoPearl&8] &cNo safe landing found");
                return;
            }
            // Aim slightly above the block top so the pearl hits the surface
            this.aimTarget = new Vec3(
                    landing.getX() + 0.5,
                    landing.getY() + 1.05,
                    landing.getZ() + 0.5
            );
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

        // Keep aiming at safe target while waiting / throwing
        if (this.aimTarget != null
                && (this.stage == Stage.WAIT_THROW || this.stage == Stage.THROW)) {
            this.applyAimLook();
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
                if (this.aimTarget != null) {
                    this.applyAimLook();
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

    /**
     * Scan nearby columns for a solid top with two air blocks above (standable).
     * Score prefers closer horizontal distance, then closer vertical to feet.
     */
    private BlockPos findSafeLanding() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }

        int baseX = MathHelper.floor_double(mc.thePlayer.posX);
        int baseY = MathHelper.floor_double(mc.thePlayer.posY);
        int baseZ = MathHelper.floor_double(mc.thePlayer.posZ);

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -SEARCH_RANGE; dx <= SEARCH_RANGE; dx++) {
            for (int dz = -SEARCH_RANGE; dz <= SEARCH_RANGE; dz++) {
                if (dx * dx + dz * dz > SEARCH_RANGE * SEARCH_RANGE) {
                    continue;
                }

                int x = baseX + dx;
                int z = baseZ + dz;

                int yMin = Math.max(0, baseY - SEARCH_DOWN);
                int yMax = Math.min(255, baseY + SEARCH_UP);

                // Walk from top down so we get the highest solid in column first
                for (int y = yMax; y >= yMin; y--) {
                    BlockPos ground = new BlockPos(x, y, z);
                    if (!this.isSafeLanding(ground)) {
                        continue;
                    }

                    double landX = x + 0.5;
                    double landY = y + 1.0;
                    double landZ = z + 0.5;
                    double distSq = mc.thePlayer.getDistanceSq(landX, landY, landZ);
                    if (distSq < MIN_LANDING_DIST * MIN_LANDING_DIST) {
                        continue;
                    }

                    double horiz = Math.sqrt(dx * dx + dz * dz);
                    double vert = Math.abs(landY - mc.thePlayer.posY);
                    // Prefer near, then level with feet; slight penalty for going up while falling
                    double score = horiz * 1.0 + vert * 0.65;
                    if (landY > mc.thePlayer.posY + 2.0) {
                        score += 3.0;
                    }

                    if (score < bestScore) {
                        bestScore = score;
                        best = ground;
                    }
                    // Only take the topmost valid in this column
                    break;
                }
            }
        }
        return best;
    }

    /**
     * ground = solid block player would stand on top of.
     * Requires solid ground + air at ground+1 and ground+2.
     */
    private boolean isSafeLanding(BlockPos ground) {
        Block block = mc.theWorld.getBlockState(ground).getBlock();
        if (block.getMaterial() == Material.air
                || block.getMaterial().isLiquid()
                || !BlockUtil.isSolid(block)) {
            return false;
        }
        // Must support entities (full top)
        if (!block.isFullCube() && !block.isFullBlock()) {
            // still allow some full-ish solids already filtered by isSolid
            if (!block.getMaterial().blocksMovement()) {
                return false;
            }
        }

        BlockPos feet = ground.up();
        BlockPos head = ground.up(2);
        Block feetBlock = mc.theWorld.getBlockState(feet).getBlock();
        Block headBlock = mc.theWorld.getBlockState(head).getBlock();

        if (feetBlock.getMaterial().isSolid() || feetBlock.getMaterial().isLiquid()) {
            return false;
        }
        if (headBlock.getMaterial().isSolid() || headBlock.getMaterial().isLiquid()) {
            return false;
        }
        return true;
    }

    private void applyAimLook() {
        if (this.aimTarget == null || mc.thePlayer == null) {
            return;
        }
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        double dx = this.aimTarget.xCoord - eye.xCoord;
        double dy = this.aimTarget.yCoord - eye.yCoord;
        double dz = this.aimTarget.zCoord - eye.zCoord;

        // Mild gravity compensation so long throws land closer to the pad
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double time = horiz / PEARL_SPEED;
        if (time > 40.0) {
            time = 40.0;
        }
        dy += 0.5 * PEARL_GRAVITY * time * time;

        float[] rots = RotationUtil.getRotationsTo(
                dx, dy, dz,
                mc.thePlayer.rotationYaw,
                mc.thePlayer.rotationPitch
        );
        float yaw = rots[0];
        float pitch = MathHelper.clamp_float(rots[1], -90.0F, 90.0F);

        // Client look (visual) + server look (projectile uses last look)
        mc.thePlayer.rotationYaw = yaw;
        mc.thePlayer.rotationPitch = pitch;
        PacketUtil.sendPacket(new C03PacketPlayer.C05PacketPlayerLook(
                yaw, pitch, mc.thePlayer.onGround));
        RotationUtil.serverYaw = yaw;
        RotationUtil.serverPitch = pitch;
        RotationUtil.customRots = true;
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
        this.aimTarget = null;
    }
}
