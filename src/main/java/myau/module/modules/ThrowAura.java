package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.CombatTargeting;
import myau.util.ItemUtil;
import myau.util.PacketUtil;
import myau.util.RotationUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

public class ThrowAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Approximate snowball/egg initial speed in blocks per tick (1.8.9). */
    private static final double PROJECTILE_SPEED = 1.5;
    /** Snowball gravity per tick. */
    private static final double PROJECTILE_GRAVITY = 0.03;
    private static final int PREDICT_ITERATIONS = 5;

    private enum Stage {
        IDLE,
        WAIT_THROW,
        WAIT_SWITCH_BACK
    }

    public final IntProperty cooldown = new IntProperty("cooldown", 500, 0, 2000);
    public final FloatProperty maxRange = new FloatProperty("max-range", 20.0F, 3.0F, 64.0F);
    /** When true (default), ThrowAura only runs while KillAura is enabled and uses its target.
     *  When false, ThrowAura finds its own target and works without KillAura. */
    public final BooleanProperty requireKillAura = new BooleanProperty("require-killaura", true);
    /** Lead the target using velocity and estimated projectile flight time. */
    public final BooleanProperty predict = new BooleanProperty("predict", true);
    /** Scales how far ahead of the target we aim (1.0 = full predicted lead). */
    public final FloatProperty predictStrength = new FloatProperty("predict-strength", 1.0F, 0.0F, 2.0F, () -> this.predict.getValue());
    /** Compensate pitch for projectile gravity drop. */
    public final BooleanProperty predictGravity = new BooleanProperty("predict-gravity", true, () -> this.predict.getValue());
    /** Ticks to wait after switching to the projectile before throwing (0 = throw same tick). */
    public final IntProperty preThrowDelay = new IntProperty("pre-throw-delay", 0, 0, 10);
    /** Ticks to wait after throwing before switching back to the original slot. */
    public final IntProperty switchBackDelay = new IntProperty("switch-back-delay", 2, 0, 10);

    private final TimerUtil timer = new TimerUtil();

    private Stage stage = Stage.IDLE;
    private int stageTicks;
    private int originalSlot = -1;
    private int projectileSlot = -1;

    public ThrowAura() {
        super("ThrowAura", false, true, "Throw ur ball to the enemy");
    }

    @Override
    public void onDisabled() {
        this.resetThrowState();
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            this.resetThrowState();
            return;
        }
        if (event.getType() != EventType.PRE) return;

        // Finish an in-progress throw sequence across ticks
        if (this.stage != Stage.IDLE) {
            this.advanceThrowSequence(event);
            return;
        }

        EntityLivingBase target = this.findTarget();
        if (target == null) return;

        double minThrowDistance = this.getMinThrowDistance();
        double distance = mc.thePlayer.getDistanceToEntity(target);
        if (distance <= minThrowDistance || distance > maxRange.getValue()) return;

        int projectileCount = ItemUtil.findInventorySlot(ItemUtil.ItemType.Projectile);
        if (projectileCount <= 0 || !timer.hasTimeElapsed(cooldown.getValue().longValue())) return;

        int slot = findProjectileHotbarSlot();
        if (slot == -1) return;

        // Aim at target (server must receive look before C08)
        float[] rotations = getAimRotations(target, event.getYaw(), event.getPitch());
        event.setRotation(rotations[0], rotations[1], 1);
        this.sendLookPacket(rotations[0], rotations[1]);

        int held = mc.thePlayer.inventory.currentItem;

        // Already holding projectile: throw immediately, no switch packets / delays
        if (slot == held) {
            PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.inventory.getStackInSlot(slot)));
            timer.reset();
            return;
        }

        // Need to switch slots — start delayed sequence
        this.originalSlot = held;
        this.projectileSlot = slot;
        PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));

        if (this.preThrowDelay.getValue() <= 0) {
            // Throw on the same tick as the switch, then wait to switch back
            PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.inventory.getStackInSlot(slot)));
            this.stage = Stage.WAIT_SWITCH_BACK;
            this.stageTicks = 0;
            if (this.switchBackDelay.getValue() <= 0) {
                this.sendSwitchBack();
                this.resetThrowState();
                timer.reset();
            }
        } else {
            this.stage = Stage.WAIT_THROW;
            this.stageTicks = 0;
        }
    }

    private void advanceThrowSequence(UpdateEvent event) {
        this.stageTicks++;

        // Keep aiming while waiting to throw
        if (this.stage == Stage.WAIT_THROW) {
            EntityLivingBase target = this.findTarget();
            if (target != null) {
                float[] rotations = getAimRotations(target, event.getYaw(), event.getPitch());
                event.setRotation(rotations[0], rotations[1], 1);
                this.sendLookPacket(rotations[0], rotations[1]);
            }

            if (this.stageTicks >= this.preThrowDelay.getValue()) {
                if (this.projectileSlot >= 0) {
                    PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(
                            mc.thePlayer.inventory.getStackInSlot(this.projectileSlot)));
                }
                this.stage = Stage.WAIT_SWITCH_BACK;
                this.stageTicks = 0;

                if (this.switchBackDelay.getValue() <= 0) {
                    this.sendSwitchBack();
                    this.resetThrowState();
                    timer.reset();
                }
            }
            return;
        }

        if (this.stage == Stage.WAIT_SWITCH_BACK) {
            if (this.stageTicks >= this.switchBackDelay.getValue()) {
                this.sendSwitchBack();
                this.resetThrowState();
                timer.reset();
            }
        }
    }

    /**
     * Tell the server the aim direction before the throw packet, otherwise the
     * projectile uses the player's previous look (where the crosshair points).
     */
    private void sendLookPacket(float yaw, float pitch) {
        pitch = MathHelper.clamp_float(pitch, -90.0F, 90.0F);
        PacketUtil.sendPacket(new C03PacketPlayer.C05PacketPlayerLook(yaw, pitch, mc.thePlayer.onGround));
        RotationUtil.serverYaw = yaw;
        RotationUtil.serverPitch = pitch;
        RotationUtil.customRots = true;
    }

    private void sendSwitchBack() {
        if (this.originalSlot >= 0 && this.originalSlot != this.projectileSlot) {
            PacketUtil.sendPacket(new C09PacketHeldItemChange(this.originalSlot));
        }
    }

    private void resetThrowState() {
        this.stage = Stage.IDLE;
        this.stageTicks = 0;
        this.originalSlot = -1;
        this.projectileSlot = -1;
    }

    private EntityLivingBase findTarget() {
        if (this.requireKillAura.getValue()) {
            KillAura killAura = (KillAura) myau.Myau.moduleManager.modules.get(KillAura.class);
            if (killAura == null || !killAura.isEnabled()) return null;
            return killAura.getTarget();
        }
        return CombatTargeting.getTarget(
                true,
                false,
                false,
                true,
                true,
                true,
                this.maxRange.getValue(),
                CombatTargeting.SortMode.DISTANCE
        );
    }

    private double getMinThrowDistance() {
        if (this.requireKillAura.getValue()) {
            KillAura killAura = (KillAura) myau.Myau.moduleManager.modules.get(KillAura.class);
            if (killAura != null && killAura.isEnabled()) {
                return killAura.attackRange.getValue();
            }
        }
        return 0.0;
    }

    /**
     * Aim at the target box, or at a velocity-predicted point when predict is enabled.
     */
    private float[] getAimRotations(EntityLivingBase target, float currentYaw, float currentPitch) {
        if (!this.predict.getValue()) {
            return RotationUtil.getRotationsToBox(
                    target.getEntityBoundingBox(),
                    currentYaw,
                    currentPitch,
                    180.0F,
                    0.0F
            );
        }

        Vec3 aimPoint = predictAimPoint(target);
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        return RotationUtil.getRotationsTo(
                aimPoint.xCoord - eye.xCoord,
                aimPoint.yCoord - eye.yCoord,
                aimPoint.zCoord - eye.zCoord,
                currentYaw,
                currentPitch
        );
    }

    /**
     * Iteratively estimate where the target will be when a snowball/egg arrives,
     * using current motion (speed / strafe / jump / fall) and optional gravity drop.
     */
    private Vec3 predictAimPoint(EntityLivingBase target) {
        AxisAlignedBB box = target.getEntityBoundingBox();
        double centerX = (box.minX + box.maxX) * 0.5;
        double centerY = box.minY + (box.maxY - box.minY) * 0.4;
        double centerZ = (box.minZ + box.maxZ) * 0.5;

        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        double strength = this.predictStrength.getValue();

        double predX = centerX;
        double predY = centerY;
        double predZ = centerZ;
        double time = 0.0;

        for (int i = 0; i < PREDICT_ITERATIONS; i++) {
            double dx = predX - eye.xCoord;
            double dy = predY - eye.yCoord;
            double dz = predZ - eye.zCoord;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            time = dist / PROJECTILE_SPEED;
            // Cap so extreme ranges do not over-lead
            if (time > 40.0) {
                time = 40.0;
            }

            predX = centerX + target.motionX * time * strength;
            predY = centerY + target.motionY * time * strength;
            predZ = centerZ + target.motionZ * time * strength;

            // Extra vertical lead while airborne (falling / jumping)
            if (!target.onGround) {
                // Approximate entity gravity contribution over flight time
                predY += 0.5 * -0.08 * time * time * strength;
            }
        }

        // Aim higher so the projectile lands on the predicted point after gravity drop
        if (this.predictGravity.getValue() && time > 0.0) {
            predY += 0.5 * PROJECTILE_GRAVITY * time * time;
        }

        return new Vec3(predX, predY, predZ);
    }

    private int findProjectileHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (ItemUtil.isProjectile(stack)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String[] getSuffix() {
        int count = ItemUtil.findInventorySlot(ItemUtil.ItemType.Projectile);
        return new String[]{String.valueOf(count)};
    }
}
