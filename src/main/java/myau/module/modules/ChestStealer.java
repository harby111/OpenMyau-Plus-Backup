package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.UpdateEvent;
import myau.events.WindowClickEvent;
import myau.mixin.IAccessorItemSword;
import myau.module.Module;
import myau.util.ChatUtil;
import myau.util.ItemUtil;
import myau.util.KeyBindUtil;
import myau.util.PlayerUtil;
import myau.util.RotationUtil;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.resources.I18n;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.*;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldSettings.GameType;
import org.apache.commons.lang3.RandomUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ChestStealer extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float BREAK_RANGE = 2.8F;
    private static final float FOV = 70.0F;

    private int clickDelay = 0;
    private int oDelay = 0;
    private boolean inChest = false;
    private boolean warnedFull = false;

    public final IntProperty minDelay = new IntProperty("min-delay", 1, 0, 20);
    public final IntProperty maxDelay = new IntProperty("max-delay", 2, 0, 20);
    public final IntProperty openDelay = new IntProperty("open-delay", 1, 0, 20);
    public final BooleanProperty autoClose = new BooleanProperty("auto-close", false);
    public final BooleanProperty nameCheck = new BooleanProperty("name-check", true);
    public final BooleanProperty HypixelMode = new BooleanProperty("hypixel-mode", false);
    public final BooleanProperty skipTrash = new BooleanProperty("skip-trash", true);
    public final BooleanProperty moreArmor = new BooleanProperty("more-armor", false);
    public final BooleanProperty moreSword = new BooleanProperty("more-sword", false);

    public final BooleanProperty breakChests = new BooleanProperty("Break-Chests", false);

    private BlockPos targetChest = null;
    private boolean isHoldingAttack = false;

    private boolean isValidGameMode() {
        GameType gameType = mc.playerController.getCurrentGameType();
        return gameType == GameType.SURVIVAL || gameType == GameType.ADVENTURE;
    }

    private boolean isMoreArmor(ItemStack itemStack) {
        if (itemStack == null) return false;
        if (!this.moreArmor.getValue()) return false;
        if (!(itemStack.getItem() instanceof ItemArmor)) return false;
        ItemArmor.ArmorMaterial armorMaterial = ((ItemArmor) itemStack.getItem()).getArmorMaterial();
        if (armorMaterial == ItemArmor.ArmorMaterial.DIAMOND) return true;
        return armorMaterial == ItemArmor.ArmorMaterial.IRON && itemStack.isItemEnchanted();
    }

    private boolean isMoreSword(ItemStack itemStack) {
        if (itemStack == null) return false;
        if (!this.moreSword.getValue()) return false;
        if (!(itemStack.getItem() instanceof ItemSword)) return false;
        Item.ToolMaterial swordMaterial = ((IAccessorItemSword) itemStack.getItem()).getMaterial();
        if (swordMaterial == Item.ToolMaterial.EMERALD) return true;
        if (EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, itemStack) != 0) return true;
        return swordMaterial == Item.ToolMaterial.IRON && itemStack.isItemEnchanted();
    }

    private boolean isInvManagerRequire(ItemStack itemStack) {
        if (itemStack == null) return false;
        InvManager invManager = (InvManager) Myau.moduleManager.modules.get(InvManager.class);
        if (ItemUtil.ItemType.Block.contains(itemStack)) {
            return !invManager.isEnabled() || ItemUtil.findInventorySlot(ItemUtil.ItemType.Block) < invManager.blocks.getValue();
        }
        if (ItemUtil.ItemType.Projectile.contains(itemStack)) {
            return !invManager.isEnabled() || ItemUtil.findInventorySlot(ItemUtil.ItemType.Projectile) < invManager.projectiles.getValue();
        }
        if (ItemUtil.ItemType.FishRod.contains(itemStack)) {
            return ItemUtil.findInventorySlot(ItemUtil.ItemType.Projectile) == 0;
        }
        if (ItemUtil.ItemType.Arrow.contains(itemStack)) {
            return !invManager.isEnabled() || ItemUtil.findInventorySlot(ItemUtil.ItemType.Arrow) < invManager.arrow.getValue();
        }
        return false;
    }

    private void shiftClick(int windowId, int slotId) {
        mc.playerController.windowClick(windowId, slotId, 0, 1, mc.thePlayer);
    }

    private boolean isHoldingValidAxe() {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) return false;
        Item item = held.getItem();
        return item == Items.iron_axe || item == Items.diamond_axe;
    }

    private boolean hasClearLineOfSight(BlockPos pos) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eyes, center, false, true, false);
        if (mop == null) return true;
        if (mop.typeOfHit == MovingObjectType.BLOCK) {
            return mop.getBlockPos().equals(pos);
        }
        return false;
    }

    private boolean isInFov(BlockPos pos) {
        double x = pos.getX() + 0.5 - mc.thePlayer.posX;
        double y = pos.getY() + 0.5 - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
        double z = pos.getZ() + 0.5 - mc.thePlayer.posZ;
        float[] rots = RotationUtil.getRotationsTo(x, y, z, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(rots[0] - mc.thePlayer.rotationYaw));
        float pitchDiff = Math.abs(MathHelper.wrapAngleTo180_float(rots[1] - mc.thePlayer.rotationPitch));
        return yawDiff <= FOV / 2.0F && pitchDiff <= FOV / 2.0F;
    }

    private BlockPos findNearestBreakableChest() {
        List<BlockPos> candidates = new ArrayList<>();

        int sx = (int) Math.floor(mc.thePlayer.posX);
        int sy = (int) Math.floor(mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        int sz = (int) Math.floor(mc.thePlayer.posZ);

        int r = 4;
        for (int x = sx - r; x <= sx + r; x++) {
            for (int y = sy - r; y <= sy + r; y++) {
                for (int z = sz - r; z <= sz + r; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    Block block = mc.theWorld.getBlockState(pos).getBlock();
                    if (!(block instanceof BlockChest)) continue;
                    if (!PlayerUtil.isBlockWithinReach(pos, mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ, BREAK_RANGE)) {
                        continue;
                    }
                    if (!hasClearLineOfSight(pos)) continue;
                    if (!isInFov(pos)) continue;
                    candidates.add(pos);
                }
            }
        }

        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator.comparingDouble(p ->
                p.distanceSqToCenter(mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ)
        ));
        return candidates.get(0);
    }

    private void stopAttackHold() {
        if (this.isHoldingAttack) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
            this.isHoldingAttack = false;
        }
    }

    private void handleBreakChests(UpdateEvent event) {
        if (!this.breakChests.getValue()) {
            this.targetChest = null;
            stopAttackHold();
            return;
        }

        if (mc.currentScreen != null) {
            this.targetChest = null;
            stopAttackHold();
            return;
        }

        if (!this.isHoldingValidAxe()) {
            this.targetChest = null;
            stopAttackHold();
            return;
        }

        if (this.targetChest == null
                || mc.theWorld.isAirBlock(this.targetChest)
                || !(mc.theWorld.getBlockState(this.targetChest).getBlock() instanceof BlockChest)
                || !PlayerUtil.canReach(this.targetChest, BREAK_RANGE)
                || !hasClearLineOfSight(this.targetChest)
                || !isInFov(this.targetChest)) {
            this.targetChest = findNearestBreakableChest();
            stopAttackHold();
        }

        if (this.targetChest == null) {
            stopAttackHold();
            return;
        }

        double x = this.targetChest.getX() + 0.5 - mc.thePlayer.posX;
        double y = this.targetChest.getY() + 0.5 - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
        double z = this.targetChest.getZ() + 0.5 - mc.thePlayer.posZ;
        float[] rotations = RotationUtil.getRotationsTo(x, y, z, event.getYaw(), event.getPitch());
        event.setRotation(rotations[0], rotations[1], 5);
        event.setPervRotation(rotations[0], 5);

        if (!this.isHoldingAttack) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), true);
            this.isHoldingAttack = true;
        }
    }

    public ChestStealer() {
        super("ChestStealer", false);
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) return;

        if (this.isEnabled() && this.isValidGameMode()) {
            handleBreakChests(event);
        }

        if (this.clickDelay > 0) {
            this.clickDelay--;
        }
        if (this.oDelay > 0) {
            this.oDelay--;
        }
        if (!(mc.currentScreen instanceof GuiChest)) {
            this.inChest = false;
        } else {
            Container container = ((GuiChest) mc.currentScreen).inventorySlots;
            if (!(container instanceof ContainerChest)) {
                this.inChest = false;
            } else {
                if (!this.inChest) {
                    this.inChest = true;
                    this.warnedFull = false;
                    this.oDelay = this.openDelay.getValue() + 1;
                }
                if (this.oDelay <= 0 && this.clickDelay <= 0) {
                    if (this.isEnabled() && this.isValidGameMode()) {
                        IInventory inventory = ((ContainerChest) container).getLowerChestInventory();
                        if (this.HypixelMode.getValue()) {
                            String inventoryName = inventory.getName();
                            String stripped = inventoryName == null
                                    ? ""
                                    : net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(inventoryName).trim();
                            if (!stripped.isEmpty()) {
                                return;
                            }
                        } else if (this.nameCheck.getValue()) {
                            String inventoryName = inventory.getName();
                            if (!inventoryName.equals(I18n.format("container.chest")) && !inventoryName.equals(I18n.format("container.chestDouble"))) {
                                return;
                            }
                        }
                        if (mc.thePlayer.inventory.getFirstEmptyStack() == -1) {
                            if (!this.warnedFull) {
                                ChatUtil.sendFormatted(String.format("%s%s: &cYour inventory is full!&r", Myau.clientName, this.getName()));
                                this.warnedFull = true;
                            }
                            if (this.autoClose.getValue()) {
                                mc.thePlayer.closeScreen();
                            }
                        } else {
                            if (this.skipTrash.getValue()) {
                                int bestSword = -1;
                                double bestDamage = 0.0;
                                int[] bestArmorSlots = new int[]{-1, -1, -1, -1};
                                double[] bestArmorProtection = new double[]{0.0, 0.0, 0.0, 0.0};
                                int bestPickaxeSlot = -1;
                                float bestPickaxeEfficiency = 1.0F;
                                int bestShovelSlot = -1;
                                float bestShovelEfficiency = 1.0F;
                                int bestAxeSlot = -1;
                                float bestAxeEfficiency = 1.0F;
                                int bestBow = -1;
                                double bestBowDamage = 0.0;
                                for (int i = 0; i < inventory.getSizeInventory(); i++) {
                                    if (container.getSlot(i).getHasStack()) {
                                        ItemStack stack = container.getSlot(i).getStack();
                                        Item item = stack.getItem();
                                        if (item instanceof ItemSword) {
                                            double damage = ItemUtil.getAttackBonus(stack);
                                            if (bestSword == -1 || damage > bestDamage) {
                                                bestSword = i;
                                                bestDamage = damage;
                                            }
                                        } else if (item instanceof ItemArmor) {
                                            int armorType = ((ItemArmor) item).armorType;
                                            double protectionLevel = ItemUtil.getArmorProtection(stack);
                                            if (bestArmorSlots[armorType] == -1 || protectionLevel > bestArmorProtection[armorType]) {
                                                bestArmorSlots[armorType] = i;
                                                bestArmorProtection[armorType] = protectionLevel;
                                            }
                                        } else if (item instanceof ItemPickaxe) {
                                            float efficiency = ItemUtil.getToolEfficiency(stack);
                                            if (bestPickaxeSlot == -1 || efficiency > bestPickaxeEfficiency) {
                                                bestPickaxeSlot = i;
                                                bestPickaxeEfficiency = efficiency;
                                            }
                                        } else if (item instanceof ItemSpade) {
                                            float efficiency = ItemUtil.getToolEfficiency(stack);
                                            if (bestShovelSlot == -1 || efficiency > bestShovelEfficiency) {
                                                bestShovelSlot = i;
                                                bestShovelEfficiency = efficiency;
                                            }
                                        } else if (item instanceof ItemAxe) {
                                            float efficiency = ItemUtil.getToolEfficiency(stack);
                                            if (bestAxeSlot == -1 || efficiency > bestAxeEfficiency) {
                                                bestAxeSlot = i;
                                                bestAxeEfficiency = efficiency;
                                            }
                                        } else if (item instanceof ItemBow) {
                                            double damage = ItemUtil.getBowAttackBonus(stack);
                                            if (bestBow == -1 || damage > bestBowDamage) {
                                                bestBow = i;
                                                bestBowDamage = damage;
                                            }
                                        }
                                    }
                                }
                                int swordInInventorySlot = ItemUtil.findSwordInInventorySlot(0, true);
                                double damage = swordInInventorySlot != -1 ? ItemUtil.getAttackBonus(mc.thePlayer.inventory.getStackInSlot(swordInInventorySlot)) : 0.0;
                                if (bestDamage > damage) {
                                    this.shiftClick(container.windowId, bestSword);
                                    return;
                                }
                                for (int i = 0; i < 4; i++) {
                                    int slot = ItemUtil.findArmorInventorySlot(i, true);
                                    double protectionLevel = slot != -1
                                            ? ItemUtil.getArmorProtection(mc.thePlayer.inventory.getStackInSlot(slot))
                                            : 0.0;
                                    if (bestArmorProtection[i] > protectionLevel) {
                                        this.shiftClick(container.windowId, bestArmorSlots[i]);
                                        return;
                                    }
                                }
                                int pickaxeSlot = ItemUtil.findInventorySlot("pickaxe", 0, true);
                                float pickaxeEfficiency = pickaxeSlot != -1 ? ItemUtil.getToolEfficiency(mc.thePlayer.inventory.getStackInSlot(pickaxeSlot)) : 1.0F;
                                if (bestPickaxeEfficiency > pickaxeEfficiency) {
                                    this.shiftClick(container.windowId, bestPickaxeSlot);
                                    return;
                                }
                                int shovelSlot = ItemUtil.findInventorySlot("shovel", 0, true);
                                float shovelEfficiency = shovelSlot != -1 ? ItemUtil.getToolEfficiency(mc.thePlayer.inventory.getStackInSlot(shovelSlot)) : 1.0F;
                                if (bestShovelEfficiency > shovelEfficiency) {
                                    this.shiftClick(container.windowId, bestShovelSlot);
                                    return;
                                }
                                int axeSlot = ItemUtil.findInventorySlot("axe", 0, true);
                                float efficiency = axeSlot != -1 ? ItemUtil.getToolEfficiency(mc.thePlayer.inventory.getStackInSlot(axeSlot)) : 1.0F;
                                if (bestAxeEfficiency > efficiency) {
                                    this.shiftClick(container.windowId, bestAxeSlot);
                                    return;
                                }
                                int bowSlot = ItemUtil.findBowInventorySlot(0, true);
                                double bowDamage = bowSlot != -1 ? ItemUtil.getBowAttackBonus(mc.thePlayer.inventory.getStackInSlot(bowSlot)) : 0.0;
                                if (bestBowDamage > bowDamage) {
                                    this.shiftClick(container.windowId, bestBow);
                                    return;
                                }
                            }
                            for (int i = 0; i < inventory.getSizeInventory(); i++) {
                                if (container.getSlot(i).getHasStack()) {
                                    ItemStack stack = container.getSlot(i).getStack();
                                    if (!this.skipTrash.getValue() || !ItemUtil.isNotSpecialItem(stack) || isMoreArmor(stack) || isMoreSword(stack) || isInvManagerRequire(stack)) {
                                        this.shiftClick(container.windowId, i);
                                        return;
                                    }
                                }
                            }
                            if (this.autoClose.getValue()) {
                                mc.thePlayer.closeScreen();
                            }
                        }
                    }
                }
            }
        }
    }

    @EventTarget
    public void onWindowClick(WindowClickEvent event) {
        this.clickDelay = RandomUtils.nextInt(this.minDelay.getValue() + 1, this.maxDelay.getValue() + 2);
    }

    @Override
    public void onDisabled() {
        stopAttackHold();
        this.targetChest = null;
    }

    @Override
    public void verifyValue(String mode) {
        switch (mode) {
            case "min-delay":
                if (this.minDelay.getValue() > this.maxDelay.getValue()) {
                    this.maxDelay.setValue(this.minDelay.getValue());
                }
                break;
            case "max-delay":
                if (this.minDelay.getValue() > this.maxDelay.getValue()) {
                    this.minDelay.setValue(this.maxDelay.getValue());
                }
        }
    }
}