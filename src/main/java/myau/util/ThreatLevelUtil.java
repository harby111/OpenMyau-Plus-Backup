package myau.util;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;

public final class ThreatLevelUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private ThreatLevelUtil() {}

    public static double getPlayerGearScore(EntityPlayer player) {
        if (player == null) return 0.0;
        double armorScore = 0.0;
        for (int i = 0; i < 4; i++) {
            armorScore += ItemUtil.getArmorProtection(player.inventory.armorInventory[i]);
        }
        ItemStack held = player.getHeldItem();
        double weaponScore = ItemUtil.getAttackBonus(held);
        if (held != null && held.getItem() instanceof ItemBow) {
            weaponScore = Math.max(weaponScore, ItemUtil.getBowAttackBonus(held));
        }
        return armorScore * 1.5 + weaponScore;
    }

    /** Returns colored suffix like " &a[Easy]&r" or empty. */
    public static String getThreatTag(EntityPlayer enemy) {
        if (mc.thePlayer == null || enemy == null) return "";
        double myScore = getPlayerGearScore(mc.thePlayer);
        double theirScore = getPlayerGearScore(enemy);
        double ratio = theirScore / Math.max(myScore, 0.5);
        if (ratio < 0.75) return " &a[Easy]&r";
        if (ratio <= 1.25) return " &e[Medium]&r";
        return " &c[Hard]&r";
    }
}
