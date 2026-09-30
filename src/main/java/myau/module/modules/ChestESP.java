package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorMinecraft;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.util.RenderUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

import java.awt.Color;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class ChestESP extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float OPEN_LID_THRESHOLD = 0.01F;
    private static final int INITIAL_SCAN_TICKS = 200;

    public final ColorProperty chest = new ColorProperty("chest", new Color(255, 170, 0).getRGB());
    public final ColorProperty trappedChest = new ColorProperty("trapped-chest", new Color(255, 43, 0).getRGB());
    public final ColorProperty enderChest = new ColorProperty("ender-chest", new Color(26, 17, 0).getRGB());
    public final ColorProperty brokenColor = new ColorProperty("broken-color", new Color(255, 0, 0).getRGB()); 
    public final BooleanProperty tracers = new BooleanProperty("tracers", false);
    public final BooleanProperty hideOpened = new BooleanProperty("hide-opened", false);
    public final BooleanProperty rememberBroken = new BooleanProperty("remember-broken", false);

    private final Set<BlockPos> knownChestPositions = new HashSet<BlockPos>();
    private int scanTicksRemaining;

    public ChestESP() {
        super("ChestESP", false);
    }

    @Override
    public void onEnabled() {
        this.resetKnown();
        this.scanTicksRemaining = INITIAL_SCAN_TICKS;
        this.scanAndRememberChests();
    }

    @Override
    public void onDisabled() {
        this.resetKnown();
    }

    private void resetKnown() {
        this.knownChestPositions.clear();
        this.scanTicksRemaining = 0;
    }

    private boolean isChestLidOpen(TileEntity tile) {
        if (tile instanceof TileEntityChest) {
            return ((TileEntityChest) tile).lidAngle > OPEN_LID_THRESHOLD;
        }
        if (tile instanceof TileEntityEnderChest) {
            return ((TileEntityEnderChest) tile).lidAngle > OPEN_LID_THRESHOLD;
        }
        return false;
    }

    private boolean isNormalOrTrappedChest(TileEntity tile) {
        return tile instanceof TileEntityChest;
    }

    private void scanAndRememberChests() {
        if (mc.theWorld == null) {
            return;
        }
        for (TileEntity tile : mc.theWorld.loadedTileEntityList) {
            if (this.isNormalOrTrappedChest(tile)) {
                this.knownChestPositions.add(tile.getPos());
            }
        }
    }

    private Color colorForChestBlock(Block block) {
        if (block instanceof BlockChest && block.canProvidePower()) {
            return new Color(this.trappedChest.getValue());
        }
        if (block instanceof BlockChest) {
            return new Color(this.chest.getValue());
        }
        return new Color(this.enderChest.getValue());
    }

    private double[] chestBoundsOrSkip(BlockPos pos, Block block) {
        double minX = 0.0625;
        double minZ = 0.0625;
        double maxX = 0.9375;
        double maxZ = 0.9375;
        if (!(block instanceof BlockChest)) {
            return new double[]{minX, minZ, maxX, maxZ};
        }
        EnumFacing facing = mc.theWorld.getBlockState(pos).getValue(BlockChest.FACING);
        switch (facing) {
            case NORTH:
                if (mc.theWorld.getBlockState(pos.east()).getBlock() == block) {
                    return null;
                } else if (mc.theWorld.getBlockState(pos.west()).getBlock() == block) {
                    minX -= 1;
                }
                break;
            case SOUTH:
                if (mc.theWorld.getBlockState(pos.west()).getBlock() == block) {
                    return null;
                } else if (mc.theWorld.getBlockState(pos.east()).getBlock() == block) {
                    maxX += 1;
                }
                break;
            case WEST:
                if (mc.theWorld.getBlockState(pos.north()).getBlock() == block) {
                    return null;
                } else if (mc.theWorld.getBlockState(pos.south()).getBlock() == block) {
                    maxZ += 1;
                }
                break;
            case EAST:
                if (mc.theWorld.getBlockState(pos.south()).getBlock() == block) {
                    return null;
                } else if (mc.theWorld.getBlockState(pos.north()).getBlock() == block) {
                    minZ -= 1;
                }
                break;
            default:
                return null;
        }
        return new double[]{minX, minZ, maxX, maxZ};
    }

    private double[] brokenMarkerBounds(BlockPos pos) {
        if (this.knownChestPositions.contains(pos.west()) || this.knownChestPositions.contains(pos.north())) {
            return null;
        }
        double minX = 0.0625;
        double minZ = 0.0625;
        double maxX = 0.9375;
        double maxZ = 0.9375;
        if (this.knownChestPositions.contains(pos.east())) {
            maxX += 1.0;
        }
        if (this.knownChestPositions.contains(pos.south())) {
            maxZ += 1.0;
        }
        return new double[]{minX, minZ, maxX, maxZ};
    }

    private void drawBox(BlockPos pos, double minX, double minZ, double maxX, double maxZ, Color color) {
        AxisAlignedBB aabb = new AxisAlignedBB(
                (double) pos.getX() + minX,
                (double) pos.getY() + 0.0,
                (double) pos.getZ() + minZ,
                (double) pos.getX() + maxX,
                (double) pos.getY() + 0.875,
                (double) pos.getZ() + maxZ
        ).offset(
                -((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX(),
                -((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY(),
                -((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ()
        );
        RenderUtil.drawBoundingBox(aabb, color.getRed(), color.getGreen(), color.getBlue(), 255, 1.5F);
    }

    private void drawTracerTo(BlockPos pos, Color color) {
        if (!this.tracers.getValue()) {
            return;
        }
        Vec3 vec;
        if (mc.gameSettings.thirdPersonView == 0) {
            vec = new Vec3(0.0, 0.0, 1.0)
                    .rotatePitch((float) (-Math.toRadians(RenderUtil.lerpFloat(
                            mc.getRenderViewEntity().rotationPitch,
                            mc.getRenderViewEntity().prevRotationPitch,
                            ((IAccessorMinecraft) mc).getTimer().renderPartialTicks))))
                    .rotateYaw((float) (-Math.toRadians(RenderUtil.lerpFloat(
                            mc.getRenderViewEntity().rotationYaw,
                            mc.getRenderViewEntity().prevRotationYaw,
                            ((IAccessorMinecraft) mc).getTimer().renderPartialTicks))));
        } else {
            vec = new Vec3(0.0, 0.0, 0.0)
                    .rotatePitch((float) (-Math.toRadians(RenderUtil.lerpFloat(
                            mc.thePlayer.cameraPitch, mc.thePlayer.prevCameraPitch,
                            ((IAccessorMinecraft) mc).getTimer().renderPartialTicks))))
                    .rotateYaw((float) (-Math.toRadians(RenderUtil.lerpFloat(
                            mc.thePlayer.cameraYaw, mc.thePlayer.prevCameraYaw,
                            ((IAccessorMinecraft) mc).getTimer().renderPartialTicks))));
        }
        vec = new Vec3(vec.xCoord, vec.yCoord + (double) mc.getRenderViewEntity().getEyeHeight(), vec.zCoord);
        float opacity = (float) ((Tracers) Myau.moduleManager.modules.get(Tracers.class)).opacity.getValue() / 100.0F;
        RenderUtil.drawLine3D(
                vec,
                (double) pos.getX() + 0.5,
                (double) pos.getY() + 0.5,
                (double) pos.getZ() + 0.5,
                (float) color.getRed() / 255.0F,
                (float) color.getGreen() / 255.0F,
                (float) color.getBlue() / 255.0F,
                opacity,
                1.5F
        );
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.resetKnown();
        this.scanTicksRemaining = INITIAL_SCAN_TICKS;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (!this.rememberBroken.getValue() || mc.theWorld == null) {
            return;
        }
        if (this.scanTicksRemaining > 0) {
            this.scanAndRememberChests();
            this.scanTicksRemaining--;
        }
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        if (!this.isEnabled() || mc.theWorld == null) {
            return;
        }

        RenderUtil.enableRenderState();

        Set<BlockPos> liveChestPos = new HashSet<BlockPos>();

        for (TileEntity tile : mc.theWorld.loadedTileEntityList.stream()
                .filter(t -> t instanceof TileEntityChest || t instanceof TileEntityEnderChest)
                .collect(Collectors.toList())) {

            if (this.hideOpened.getValue() && this.isChestLidOpen(tile)) {
                continue;
            }

            if (this.isNormalOrTrappedChest(tile)) {
                liveChestPos.add(tile.getPos());
            }

            Block block = mc.theWorld.getBlockState(tile.getPos()).getBlock();
            double[] bounds = this.chestBoundsOrSkip(tile.getPos(), block);
            if (bounds == null) {
                continue;
            }

            Color color = this.colorForChestBlock(block);
            this.drawBox(tile.getPos(), bounds[0], bounds[1], bounds[2], bounds[3], color);
            this.drawTracerTo(tile.getPos(), color);
        }

        if (this.rememberBroken.getValue() && !this.knownChestPositions.isEmpty()) {
            Color red = new Color(this.brokenColor.getValue());
            for (BlockPos pos : this.knownChestPositions) {
                if (liveChestPos.contains(pos)) {
                    continue;
                }
                TileEntity te = mc.theWorld.getTileEntity(pos);
                if (te instanceof TileEntityChest) {
                    continue;
                }

                double[] bounds = this.brokenMarkerBounds(pos);
                if (bounds == null) {
                    continue;
                }
                this.drawBox(pos, bounds[0], bounds[1], bounds[2], bounds[3], red);
                this.drawTracerTo(pos, red);
            }
        }

        RenderUtil.disableRenderState();
    }
}
