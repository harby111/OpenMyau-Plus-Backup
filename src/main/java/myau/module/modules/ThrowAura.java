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
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;

public class ThrowAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final IntProperty cooldown = new IntProperty("cooldown", 500, 0, 2000);
    public final FloatProperty maxRange = new FloatProperty("max-range", 20.0F, 3.0F, 64.0F);
    /** When true (default), ThrowAura only runs while KillAura is enabled and uses its target.
     *  When false, ThrowAura finds its own target and works without KillAura. */
    public final BooleanProperty requireKillAura = new BooleanProperty("require-killaura", true);
    private final TimerUtil timer = new TimerUtil();

    public ThrowAura() {
        super("ThrowAura", false, true, "Throw ur ball to the enemy");
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;
        if (event.getType() != EventType.PRE) return;

        EntityLivingBase target;
        double minThrowDistance;

        if (this.requireKillAura.getValue()) {
            KillAura killAura = (KillAura) myau.Myau.moduleManager.modules.get(KillAura.class);
            if (killAura == null || !killAura.isEnabled()) return;

            target = killAura.getTarget();
            if (target == null) return;

            // Only throw when the target is outside KillAura melee range
            minThrowDistance = killAura.attackRange.getValue();
        } else {
            // Independent mode: pick closest valid player within max-range
            target = CombatTargeting.getTarget(
                    true,
                    false,
                    false,
                    true,
                    true,
                    true,
                    this.maxRange.getValue(),
                    CombatTargeting.SortMode.DISTANCE
            );
            if (target == null) return;

            // No minimum distance required when working without KillAura
            minThrowDistance = 0.0;
        }

        double distance = mc.thePlayer.getDistanceToEntity(target);
        if (distance > minThrowDistance && distance <= maxRange.getValue()) {
            int projectileCount = ItemUtil.findInventorySlot(ItemUtil.ItemType.Projectile);
            if (projectileCount > 0 && timer.hasTimeElapsed(cooldown.getValue().longValue())) {
                int projectileSlot = findProjectileHotbarSlot();
                if (projectileSlot != -1) {
                    float[] rotations = RotationUtil.getRotationsToBox(
                            target.getEntityBoundingBox(),
                            event.getYaw(),
                            event.getPitch(),
                            180.0F,
                            0.0F
                    );
                    event.setRotation(rotations[0], rotations[1], 1);
                    int originalSlot = mc.thePlayer.inventory.currentItem;
                    if (projectileSlot != originalSlot) {
                        PacketUtil.sendPacket(new C09PacketHeldItemChange(projectileSlot));
                    }
                    PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.inventory.getStackInSlot(projectileSlot)));
                    if (projectileSlot != originalSlot) {
                        PacketUtil.sendPacket(new C09PacketHeldItemChange(originalSlot));
                    }

                    timer.reset();
                }
            }
        }
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
