package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import myau.util.TeamUtil;
import net.minecraft.block.BlockAir;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

public class KeepRange extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final FloatProperty range = new FloatProperty("range", 3.0F, 0.0F, 6.0F);
    public final BooleanProperty disableNearEdge = new BooleanProperty("disable-near-edge", true);
    public final IntProperty edgeRange = new IntProperty("edge-range", 5, 0, 6, this.disableNearEdge::getValue);
    public final ModeProperty mode = new ModeProperty("mode", 1, new String[]{"Backwards", "Stop"});
    public final IntProperty comboToStart = new IntProperty("combo-to-start", 2, 0, 6);
    public final BooleanProperty kaOnly = new BooleanProperty("ka-only", true);

    private boolean nearEdge;
    private int comboTicks;

    public KeepRange() {
        super("KeepRange", false, false, "Stop walking into target when too close (S-tap style)");
    }

    @Override
    public void onDisabled() {
        this.nearEdge = false;
        this.comboTicks = 0;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.comboTicks = 0;
            return;
        }

        if (mc.thePlayer.onGround) {
            this.nearEdge = false;
            int er = this.edgeRange.getValue();
            outer:
            for (int j = -er; j <= er; j++) {
                for (int k = -er; k <= er; k++) {
                    boolean foundSolid = false;
                    for (int l = -5; l <= 0; l++) {
                        BlockPos pos = new BlockPos(
                                MathHelper.floor_double(mc.thePlayer.posX) + j,
                                MathHelper.floor_double(mc.thePlayer.posY) + l,
                                MathHelper.floor_double(mc.thePlayer.posZ) + k
                        );
                        if (!(mc.theWorld.getBlockState(pos).getBlock() instanceof BlockAir)) {
                            foundSolid = true;
                            break;
                        }
                    }
                    if (!foundSolid) {
                        this.nearEdge = true;
                        break outer;
                    }
                }
            }
        }

        EntityLivingBase target = resolveTarget();
        double keepRange = this.range.getValue();
        if (mc.thePlayer.getHealth() <= 7.0F) {
            keepRange -= 0.2;
        }

        if (target == null || (this.nearEdge && this.disableNearEdge.getValue())) {
            this.comboTicks = 0;
            return;
        }

        if (target.hurtTime > 0) {
            this.comboTicks++;
        }
        if (mc.thePlayer.hurtTime > 0) {
            this.comboTicks = 0;
        }

        int comboNeed = this.comboToStart.getValue();
        if (comboNeed > 0 && this.comboTicks <= comboNeed * 8) {
            return;
        }

        double dist = mc.thePlayer.getDistanceToEntity(target);
        if (dist >= keepRange - 0.05) {
            return;
        }

        float forward = mc.thePlayer.movementInput.moveForward;
        float strafe = mc.thePlayer.movementInput.moveStrafe;
        if (forward == 0.0F && strafe == 0.0F) {
            return;
        }

        float[] rots = myau.util.RotationUtil.getRotationsTo(
                target.posX - mc.thePlayer.posX,
                0.0,
                target.posZ - mc.thePlayer.posZ,
                mc.thePlayer.rotationYaw,
                mc.thePlayer.rotationPitch
        );
        double awayYaw = MathHelper.wrapAngleTo180_double(rots[0] - 180.0F);

        float bestF = 0.0F;
        float bestS = 0.0F;
        float bestDiff = Float.MAX_VALUE;

        for (float f5 = -1.0F; f5 <= 1.0F; f5++) {
            for (float f6 = -1.0F; f6 <= 1.0F; f6++) {
                if (f6 == 0.0F && f5 == 0.0F) {
                    continue;
                }
                double dirDeg = MathHelper.wrapAngleTo180_double(
                        Math.toDegrees(MoveUtil.direction(mc.thePlayer.rotationYaw, f5, f6))
                );
                double diff = angleDiff(awayYaw, dirDeg);
                if (diff < bestDiff) {
                    bestDiff = (float) diff;
                    bestF = f5;
                    bestS = f6;
                }
            }
        }

        if (this.mode.getValue() == 1) {
            if (bestF == forward * -1.0F) {
                mc.thePlayer.movementInput.moveForward = 0.0F;
            }
            if (bestS == strafe * -1.0F) {
                mc.thePlayer.movementInput.moveStrafe = 0.0F;
            }
        } else {
            mc.thePlayer.movementInput.moveForward = bestF;
            mc.thePlayer.movementInput.moveStrafe = bestS;
        }
    }

    private EntityLivingBase resolveTarget() {
        KillAura ka = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (ka != null && ka.isEnabled()) {
            try {
                EntityLivingBase t = ka.getTarget();
                if (TeamUtil.isEntityLoaded(t)) {
                    return t;
                }
            } catch (Throwable ignored) {
            }
        }
        if (this.kaOnly.getValue()) {
            return null;
        }
        EntityLivingBase best = null;
        double bestD = 10.0;
        for (Object o : mc.theWorld.playerEntities) {
            if (!(o instanceof EntityLivingBase)) {
                continue;
            }
            EntityLivingBase e = (EntityLivingBase) o;
            if (e == mc.thePlayer || !TeamUtil.isEntityLoaded(e)) {
                continue;
            }
            if (e instanceof net.minecraft.entity.player.EntityPlayer
                    && TeamUtil.isFriend((net.minecraft.entity.player.EntityPlayer) e)) {
                continue;
            }
            double d = mc.thePlayer.getDistanceToEntity(e);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private static double angleDiff(double a, double b) {
        double d = Math.abs(MathHelper.wrapAngleTo180_double(a - b));
        return d;
    }
}