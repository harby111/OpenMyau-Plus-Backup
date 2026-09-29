package myau.module.modules;

import myau.event.EventTarget;
import myau.events.Render3DEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.util.RenderUtil;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.PercentProperty;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.init.Items;
import net.minecraft.item.*;
import net.minecraft.util.*;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;

public class Trajectories extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final PercentProperty opacity = new PercentProperty("opacity", 100);
    public final BooleanProperty bow = new BooleanProperty("bow", true);
    public final BooleanProperty projectiles = new BooleanProperty("projectiles", false);
    public final BooleanProperty pearls = new BooleanProperty("pearls", true);

    public final BooleanProperty fireCharge = new BooleanProperty("fire-charge", true);
    public final FloatProperty fireChargeRange = new FloatProperty("fire-charge-range", 100.0F, 16.0F, 200.0F,
            () -> this.fireCharge.getValue());
    public final BooleanProperty impactArea = new BooleanProperty("impact-area", false);
    public final IntProperty impactRadius = new IntProperty("impact-radius", 2, 1, 2,
            () -> this.impactArea.getValue());

    public Trajectories() {
        super("Trajectories", false, true);
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.thePlayer.getHeldItem() == null || mc.gameSettings.thirdPersonView != 0) {
            return;
        }

        Item item = mc.thePlayer.getHeldItem().getItem();

        if (this.fireCharge.getValue() && item == Items.fire_charge) {
            renderFireChargePath(event);
            return;
        }

        RenderManager renderManager = mc.getRenderManager();
        boolean isBow = false;
        boolean isPearl = false;
        float velocityMultiplier = 1.5F;
        float drag = 0.99F;
        float gravity;
        float hitboxExpand;

        if (item instanceof ItemBow && this.bow.getValue()) {
            if (!mc.thePlayer.isUsingItem()) {
                return;
            }
            isBow = true;
            gravity = 0.05F;
            hitboxExpand = 0.3F;
            float charge = (float) mc.thePlayer.getItemInUseDuration() / 20.0F;
            charge = (charge * charge + charge * 2.0F) / 3.0F;
            if (charge < 0.1F) {
                return;
            }
            if (charge > 1.0F) {
                charge = 1.0F;
            }
            velocityMultiplier = charge * 3.0F;
        } else if (item instanceof ItemFishingRod && this.projectiles.getValue()) {
            gravity = 0.04F;
            hitboxExpand = 0.25F;
            drag = 0.92F;
        } else if ((item instanceof ItemSnowball || item instanceof ItemEgg) && this.projectiles.getValue()) {
            gravity = 0.03F;
            hitboxExpand = 0.25F;
        } else if (item instanceof ItemEnderPearl && this.pearls.getValue()) {
            isPearl = true;
            gravity = 0.03F;
            hitboxExpand = 0.25F;
        } else {
            return;
        }

        float yaw = mc.thePlayer.rotationYaw;
        float pitch = mc.thePlayer.rotationPitch;
        double x = ((IAccessorRenderManager) renderManager).getRenderPosX()
                - (double) MathHelper.cos(yaw / 180.0F * (float) Math.PI) * 0.16;
        double y = ((IAccessorRenderManager) renderManager).getRenderPosY()
                + (double) mc.thePlayer.getEyeHeight() - 0.1F;
        double z = ((IAccessorRenderManager) renderManager).getRenderPosZ()
                - (double) MathHelper.sin(yaw / 180.0F * (float) Math.PI) * 0.16;
        double mx = (double) (MathHelper.sin(yaw / 180.0F * (float) Math.PI)
                * MathHelper.cos(pitch / 180.0F * (float) Math.PI)) * (isBow ? 1.0 : 0.4) * -1.0;
        double my = (double) MathHelper.sin(pitch / 180.0F * (float) Math.PI) * (isBow ? 1.0 : 0.4) * -1.0;
        double mz = (double) (MathHelper.cos(yaw / 180.0F * (float) Math.PI)
                * MathHelper.cos(pitch / 180.0F * (float) Math.PI)) * (isBow ? 1.0 : 0.4);
        float mag = MathHelper.sqrt_double(mx * mx + my * my + mz * mz);
        mx /= mag;
        my /= mag;
        mz /= mag;
        mx *= velocityMultiplier;
        my *= velocityMultiplier;
        mz *= velocityMultiplier;

        MovingObjectPosition mop = null;
        boolean hasHitBlock = false;
        boolean hasHitEntity = false;
        WorldRenderer worldRenderer = Tessellator.getInstance().getWorldRenderer();
        ArrayList<Vec3> trajectoryPoints = new ArrayList<Vec3>();
        BlockPos impactPos = null;

        while (!hasHitBlock && y > 0.0) {
            Vec3 start = new Vec3(x, y, z);
            Vec3 end = new Vec3(x + mx, y + my, z + mz);
            mop = mc.theWorld.rayTraceBlocks(start, end, false, true, false);
            start = new Vec3(x, y, z);
            end = new Vec3(x + mx, y + my, z + mz);
            if (mop != null) {
                hasHitBlock = true;
                end = new Vec3(mop.hitVec.xCoord, mop.hitVec.yCoord, mop.hitVec.zCoord);
                if (mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                    impactPos = mop.getBlockPos();
                }
            }

            AxisAlignedBB aabb = new AxisAlignedBB(
                    x - (double) hitboxExpand,
                    y - (double) hitboxExpand,
                    z - (double) hitboxExpand,
                    x + (double) hitboxExpand,
                    y + (double) hitboxExpand,
                    z + (double) hitboxExpand
            )
                    .addCoord(mx, my, mz)
                    .expand(1.0, 1.0, 1.0);
            int minChunkX = MathHelper.floor_double((aabb.minX - 2.0) / 16.0);
            int maxChunkX = MathHelper.floor_double((aabb.maxX + 2.0) / 16.0);
            int minChunkZ = MathHelper.floor_double((aabb.minZ - 2.0) / 16.0);
            int maxChunkZ = MathHelper.floor_double((aabb.maxZ + 2.0) / 16.0);
            ArrayList<Entity> possibleEntities = new ArrayList<Entity>();
            for (int x1 = minChunkX; x1 <= maxChunkX; ++x1) {
                for (int z1 = minChunkZ; z1 <= maxChunkZ; ++z1) {
                    mc.theWorld.getChunkFromChunkCoords(x1, z1)
                            .getEntitiesWithinAABBForEntity(mc.thePlayer, aabb, possibleEntities, null);
                }
            }
            for (Entity entity : possibleEntities) {
                if (entity.canBeCollidedWith() && entity != mc.thePlayer) {
                    AxisAlignedBB entityBox = entity.getEntityBoundingBox()
                            .expand(hitboxExpand, hitboxExpand, hitboxExpand);
                    MovingObjectPosition intercept = entityBox.calculateIntercept(start, end);
                    if (intercept != null) {
                        hasHitEntity = true;
                        hasHitBlock = true;
                        mop = intercept;
                    }
                }
            }

            x += mx;
            y += my;
            z += mz;
            if (mc.theWorld.getBlockState(new BlockPos(x, y, z)).getBlock().getMaterial() == Material.water) {
                mx *= 0.6;
                my *= 0.6;
                mz *= 0.6;
            } else {
                mx *= drag;
                my *= drag;
                mz *= drag;
            }
            my -= gravity;
            trajectoryPoints.add(
                    new Vec3(
                            x - ((IAccessorRenderManager) renderManager).getRenderPosX(),
                            y - ((IAccessorRenderManager) renderManager).getRenderPosY(),
                            z - ((IAccessorRenderManager) renderManager).getRenderPosZ()
                    )
            );
        }

        boolean pearlSafeLand = isPearl && hasHitBlock && !hasHitEntity && impactPos != null
                && isSolidLandingBlock(impactPos);

        int alpha = (int) (this.opacity.getValue().floatValue() / 100.0F * 255.0F);
        int pathColor;
        if (hasHitEntity) {
            pathColor = new Color(85, 255, 85, alpha).getRGB();
        } else if (pearlSafeLand) {
            pathColor = new Color(40, 255, 80, alpha).getRGB();
        } else {
            pathColor = new Color(255, 255, 255, alpha).getRGB();
        }

        if (trajectoryPoints.size() > 1) {
            drawPathLine(trajectoryPoints, pathColor, mop, x, y, z, renderManager);
        }

        if (this.impactArea.getValue() && impactPos != null && hasHitBlock && !hasHitEntity) {
            drawImpactArea(impactPos, pathColor, alpha);
        }
    }

    private void renderFireChargePath(Render3DEvent event) {
        RenderManager renderManager = mc.getRenderManager();
        float range = this.fireChargeRange.getValue();

        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 look = mc.thePlayer.getLook(1.0F);
        Vec3 end = eyes.addVector(look.xCoord * range, look.yCoord * range, look.zCoord * range);

        MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eyes, end, false, true, false);
        Vec3 hit = mop != null ? mop.hitVec : end;
        BlockPos impactPos = (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK)
                ? mop.getBlockPos() : null;

        double rpx = ((IAccessorRenderManager) renderManager).getRenderPosX();
        double rpy = ((IAccessorRenderManager) renderManager).getRenderPosY();
        double rpz = ((IAccessorRenderManager) renderManager).getRenderPosZ();

        ArrayList<Vec3> points = new ArrayList<Vec3>();
        int samples = Math.max(8, (int) (eyes.distanceTo(hit) * 2.0));
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / (double) samples;
            double px = eyes.xCoord + (hit.xCoord - eyes.xCoord) * t;
            double py = eyes.yCoord + (hit.yCoord - eyes.yCoord) * t;
            double pz = eyes.zCoord + (hit.zCoord - eyes.zCoord) * t;
            points.add(new Vec3(px - rpx, py - rpy, pz - rpz));
        }

        int alpha = (int) (this.opacity.getValue().floatValue() / 100.0F * 255.0F);
        int pathColor = new Color(255, 200, 40, alpha).getRGB();

        if (points.size() > 1) {
            drawPathLine(points, pathColor, mop,
                    hit.xCoord, hit.yCoord, hit.zCoord, renderManager);
        }

        if (this.impactArea.getValue() && impactPos != null) {
            drawImpactArea(impactPos, pathColor, alpha);
        }
    }

    private void drawPathLine(ArrayList<Vec3> trajectoryPoints, int pathColor,
                              MovingObjectPosition mop, double worldX, double worldY, double worldZ,
                              RenderManager renderManager) {
        WorldRenderer worldRenderer = Tessellator.getInstance().getWorldRenderer();
        RenderUtil.enableRenderState();
        RenderUtil.setColor(pathColor);
        GL11.glLineWidth(1.5F);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        worldRenderer.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION);
        for (Vec3 vec3 : trajectoryPoints) {
            worldRenderer.pos(vec3.xCoord, vec3.yCoord, vec3.zCoord).endVertex();
        }
        Tessellator.getInstance().draw();

        GlStateManager.pushMatrix();
        GlStateManager.translate(
                worldX - ((IAccessorRenderManager) renderManager).getRenderPosX(),
                worldY - ((IAccessorRenderManager) renderManager).getRenderPosY(),
                worldZ - ((IAccessorRenderManager) renderManager).getRenderPosZ()
        );
        if (mop != null && mop.sideHit != null) {
            switch (mop.sideHit.getAxis().ordinal()) {
                case 0:
                    GlStateManager.rotate(90.0F, 0.0F, 1.0F, 0.0F);
                    break;
                case 1:
                    GlStateManager.rotate(90.0F, 1.0F, 0.0F, 0.0F);
                    break;
                default:
                    break;
            }
            RenderUtil.drawLine(-0.25F, -0.25F, 0.25F, 0.25F, 1.5F, pathColor);
            RenderUtil.drawLine(-0.25F, 0.25F, 0.25F, -0.25F, 1.5F, pathColor);
        }
        GlStateManager.popMatrix();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(2.0F);
        GlStateManager.resetColor();
        RenderUtil.disableRenderState();
    }

    private void drawImpactArea(BlockPos center, int rgb, int alpha) {
        int radius = this.impactRadius.getValue();
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int outline = new Color(r, g, b, Math.min(255, alpha)).getRGB();

        RenderManager rm = mc.getRenderManager();
        double rpx = ((IAccessorRenderManager) rm).getRenderPosX();
        double rpy = ((IAccessorRenderManager) rm).getRenderPosY();
        double rpz = ((IAccessorRenderManager) rm).getRenderPosZ();

        RenderUtil.enableRenderState();
        GL11.glLineWidth(1.5F);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (!isSolidLandingBlock(pos)) {
                        continue;
                    }
                    double x1 = pos.getX() - rpx;
                    double y1 = pos.getY() - rpy;
                    double z1 = pos.getZ() - rpz;
                    double x2 = x1 + 1.0;
                    double y2 = y1 + 1.0;
                    double z2 = z1 + 1.0;
                    drawBoxOutline(x1, y1, z1, x2, y2, z2, outline);
                }
            }
        }

        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(2.0F);
        GlStateManager.resetColor();
        RenderUtil.disableRenderState();
    }

    private void drawBoxOutline(double x1, double y1, double z1, double x2, double y2, double z2, int color) {
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        RenderUtil.setColor(color);
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);
        // bottom
        wr.pos(x1, y1, z1).endVertex(); wr.pos(x2, y1, z1).endVertex();
        wr.pos(x2, y1, z1).endVertex(); wr.pos(x2, y1, z2).endVertex();
        wr.pos(x2, y1, z2).endVertex(); wr.pos(x1, y1, z2).endVertex();
        wr.pos(x1, y1, z2).endVertex(); wr.pos(x1, y1, z1).endVertex();
        // top
        wr.pos(x1, y2, z1).endVertex(); wr.pos(x2, y2, z1).endVertex();
        wr.pos(x2, y2, z1).endVertex(); wr.pos(x2, y2, z2).endVertex();
        wr.pos(x2, y2, z2).endVertex(); wr.pos(x1, y2, z2).endVertex();
        wr.pos(x1, y2, z2).endVertex(); wr.pos(x1, y2, z1).endVertex();
        // pillars
        wr.pos(x1, y1, z1).endVertex(); wr.pos(x1, y2, z1).endVertex();
        wr.pos(x2, y1, z1).endVertex(); wr.pos(x2, y2, z1).endVertex();
        wr.pos(x2, y1, z2).endVertex(); wr.pos(x2, y2, z2).endVertex();
        wr.pos(x1, y1, z2).endVertex(); wr.pos(x1, y2, z2).endVertex();
        Tessellator.getInstance().draw();
    }

    private boolean isSolidLandingBlock(BlockPos pos) {
        if (mc.theWorld == null || pos == null) {
            return false;
        }
        if (mc.theWorld.isAirBlock(pos)) {
            return false;
        }
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        if (block == null) {
            return false;
        }
        Material mat = block.getMaterial();
        if (mat == null || !mat.isSolid() || mat.isLiquid()) {
            return false;
        }
        try {
            return block.isFullCube();
        } catch (Throwable t) {
            return true;
        }
    }
}