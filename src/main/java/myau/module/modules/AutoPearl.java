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
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/**
 * ClickGUI / command: normal enable/disable.
 * In-game module keybind: while enabled → throw pearl; while disabled → do nothing.
 * Auto-Throw: silent packet aim at floor tops or walls with a pad in front.
 */
public class AutoPearl extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int SEARCH_RANGE = 16;
    private static final double MIN_LANDING_DIST = 2.5;
    private static final int SEARCH_DOWN = 24;
    private static final int SEARCH_UP = 8;
    private static final double PEARL_SPEED = 1.5;
    private static final double PEARL_GRAVITY = 0.03;

    private static final EnumFacing[] HORIZONTAL = {
            EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST
    };

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
    public final BooleanProperty autoThrow = new BooleanProperty("Auto-Throw", false);

    private Stage stage = Stage.IDLE;
    private int stageTicks;
    private int waitTarget;
    private int originalSlot = -1;
    private int pearlSlot = -1;
    /** Silent aim point (packets only); null = throw with current look. */
    private Vec3 aimTarget = null;

    public AutoPearl() {
        super("AutoPearl", false, false,
                "While on: bind throws pearl. Auto-Throw aims at safe ground/walls");
    }

    @Override
    public boolean toggle() {
        if (mc.currentScreen != null) {
            return super.toggle();
        }
        if (this.isEnabled()) {
            this.requestThrow();
        }
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
            Vec3 target = this.findBestAimTarget();
            if (target == null) {
                ChatUtil.sendFormatted("&8[&eAutoPearl&8] &cNo safe landing found");
                return;
            }
            this.aimTarget = target;
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

        if (this.aimTarget != null
                && (this.stage == Stage.WAIT_THROW || this.stage == Stage.THROW)) {
            this.applySilentAim();
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
                    // Silent aim + placement packet so projectile uses server look, not client view
                    this.applySilentAim();
                    PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
                } else {
                    KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindUseItem.getKeyCode());
                }

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
     * Best aim point among:
     * 1) Top of standable floors
     * 2) Exposed wall faces that have a solid pad + air in front (pearl hits wall → land on pad)
     */
    private Vec3 findBestAimTarget() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }

        int baseX = MathHelper.floor_double(mc.thePlayer.posX);
        int baseY = MathHelper.floor_double(mc.thePlayer.posY);
        int baseZ = MathHelper.floor_double(mc.thePlayer.posZ);

        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;

        int yMin = Math.max(0, baseY - SEARCH_DOWN);
        int yMax = Math.min(255, baseY + SEARCH_UP);

        for (int dx = -SEARCH_RANGE; dx <= SEARCH_RANGE; dx++) {
            for (int dz = -SEARCH_RANGE; dz <= SEARCH_RANGE; dz++) {
                if (dx * dx + dz * dz > SEARCH_RANGE * SEARCH_RANGE) {
                    continue;
                }

                int x = baseX + dx;
                int z = baseZ + dz;

                for (int y = yMax; y >= yMin; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!this.isSolidBlock(pos)) {
                        continue;
                    }

                    // --- Floor top: stand on this block ---
                    if (this.isStandableOn(pos)) {
                        Vec3 aim = new Vec3(x + 0.5, y + 1.05, z + 0.5);
                        double score = this.scoreAim(aim, dx, dz);
                        if (score < bestScore) {
                            bestScore = score;
                            best = aim;
                        }
                    }

                    // --- Wall face with pad in front ---
                    for (EnumFacing face : HORIZONTAL) {
                        Vec3 wallAim = this.wallAimIfSafe(pos, face);
                        if (wallAim == null) {
                            continue;
                        }
                        double score = this.scoreAim(wallAim, dx, dz);
                        // slight preference for floors over walls when equal
                        score += 0.35;
                        if (score < bestScore) {
                            bestScore = score;
                            best = wallAim;
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Wall at {@code wall} exposed on {@code face}.
     * In front: air for body, solid pad under feet → aim at wall face center.
     *
     * <pre>
     *   [Wall]
     *   [Wall]  x  x   (air in front)
     *   [Wall]  x  x
     *           [Pad]  (solid landing)
     * </pre>
     */
    private Vec3 wallAimIfSafe(BlockPos wall, EnumFacing face) {
        BlockPos front = wall.offset(face);
        BlockPos frontUp = front.up();
        BlockPos pad = front.down();

        // Space in front of this wall block must be free (body)
        if (!this.isReplaceableAir(front) || !this.isReplaceableAir(frontUp)) {
            // If aiming mid-wall, only need front of this cell free; feet may be lower
            if (!this.isReplaceableAir(front)) {
                return null;
            }
        }

        // Find a solid pad in front of the wall within a short vertical range
        BlockPos foundPad = null;
        for (int dy = 0; dy <= 3; dy++) {
            BlockPos candidate = front.down(dy);
            if (this.isSolidBlock(candidate) && this.isStandableOn(candidate)) {
                foundPad = candidate;
                break;
            }
        }
        if (foundPad == null && this.isSolidBlock(pad) && this.isStandableOn(pad)) {
            foundPad = pad;
        }
        if (foundPad == null) {
            return null;
        }

        // Prefer aiming at the wall face roughly at chest/head height above the pad
        int aimY = MathHelper.clamp_int(foundPad.getY() + 1, wall.getY() - 1, wall.getY() + 1);
        BlockPos aimBlock = new BlockPos(wall.getX(), aimY, wall.getZ());
        if (!this.isSolidBlock(aimBlock)) {
            aimBlock = wall;
        }

        // Center of the exposed face (pearl hits wall → player lands near pad)
        double cx = aimBlock.getX() + 0.5 + face.getFrontOffsetX() * 0.51;
        double cy = aimBlock.getY() + 0.55;
        double cz = aimBlock.getZ() + 0.5 + face.getFrontOffsetZ() * 0.51;
        return new Vec3(cx, cy, cz);
    }

    private double scoreAim(Vec3 aim, int dx, int dz) {
        double distSq = mc.thePlayer.getDistanceSq(aim.xCoord, aim.yCoord, aim.zCoord);
        if (distSq < MIN_LANDING_DIST * MIN_LANDING_DIST) {
            return Double.MAX_VALUE;
        }
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double vert = Math.abs(aim.yCoord - mc.thePlayer.posY);
        double score = horiz * 1.0 + vert * 0.65;
        if (aim.yCoord > mc.thePlayer.posY + 2.0) {
            score += 3.0;
        }
        return score;
    }

    private boolean isSolidBlock(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        if (block.getMaterial() == Material.air || block.getMaterial().isLiquid()) {
            return false;
        }
        if (!BlockUtil.isSolid(block)) {
            return false;
        }
        if (!block.isFullCube() && !block.isFullBlock()) {
            return block.getMaterial().blocksMovement();
        }
        return true;
    }

    /** Solid top with two air cells above (player can stand). */
    private boolean isStandableOn(BlockPos ground) {
        if (!this.isSolidBlock(ground)) {
            return false;
        }
        return this.isReplaceableAir(ground.up()) && this.isReplaceableAir(ground.up(2));
    }

    private boolean isReplaceableAir(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        if (block.getMaterial().isLiquid()) {
            return false;
        }
        if (block.getMaterial() == Material.air) {
            return true;
        }
        return !block.getMaterial().blocksMovement() && !block.getMaterial().isSolid();
    }

    /** Server look only — does not move client camera. */
    private void applySilentAim() {
        if (this.aimTarget == null || mc.thePlayer == null) {
            return;
        }
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        double dx = this.aimTarget.xCoord - eye.xCoord;
        double dy = this.aimTarget.yCoord - eye.yCoord;
        double dz = this.aimTarget.zCoord - eye.zCoord;

        double horiz = Math.sqrt(dx * dx + dz * dz);
        double time = horiz / PEARL_SPEED;
        if (time > 40.0) {
            time = 40.0;
        }
        dy += 0.5 * PEARL_GRAVITY * time * time;

        float baseYaw = RotationUtil.customRots ? RotationUtil.serverYaw : mc.thePlayer.rotationYaw;
        float basePitch = RotationUtil.customRots ? RotationUtil.serverPitch : mc.thePlayer.rotationPitch;

        float[] rots = RotationUtil.getRotationsTo(dx, dy, dz, baseYaw, basePitch);
        float yaw = rots[0];
        float pitch = MathHelper.clamp_float(rots[1], -90.0F, 90.0F);

        PacketUtil.sendPacket(new C03PacketPlayer.C05PacketPlayerLook(
                yaw, pitch, mc.thePlayer.onGround));
        RotationUtil.serverYaw = yaw;
        RotationUtil.serverPitch = pitch;
        RotationUtil.customRots = true;
        // client rotationYaw / rotationPitch intentionally unchanged
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
