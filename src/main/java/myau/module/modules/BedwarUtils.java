package myau.module.modules;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class FastBuyHelper {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int[][] KEYS = {
            { Keyboard.KEY_ADD, Keyboard.KEY_EQUALS },
            { Keyboard.KEY_SUBTRACT, Keyboard.KEY_MINUS },
            { Keyboard.KEY_8 },
            { Keyboard.KEY_9 },
            { Keyboard.KEY_0 }
    };
    private static final int[] TARGET_SLOTS = { 19, 20, 19, 20, 21 };
    private static final boolean[] NEEDS_CATEGORY = { false, false, true, true, true };
    private static final int CATEGORY_SLOT = 4;
    private static final int TIMEOUT_TICKS = 20;

    private static Method clickMethod;
    private static Method releaseMethod;
    private static Field guiLeftField;
    private static Field guiTopField;

    private boolean[] prev = new boolean[KEYS.length];
    private int pendingSlot = -1;
    private int wait = 0;
    private int timeout = 0;

    public void tick(boolean enabled, int delayTicks) {
        boolean[] pressed = new boolean[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            for (int key : KEYS[i]) {
                if (Keyboard.isKeyDown(key)) {
                    pressed[i] = true;
                    break;
                }
            }
        }

        if (!enabled || !(mc.currentScreen instanceof GuiChest)) {
            this.pendingSlot = -1;
            this.prev = pressed;
            return;
        }

        if (this.pendingSlot != -1) {
            if (this.wait > 0) {
                this.wait--;
            } else if (this.isSlotReady(this.pendingSlot)) {
                this.clickSlot(this.pendingSlot);
                this.pendingSlot = -1;
            } else if (--this.timeout <= 0) {
                this.pendingSlot = -1;
            }
            this.prev = pressed;
            return;
        }

        for (int i = 0; i < pressed.length; i++) {
            if (pressed[i] && !this.prev[i]) {
                if (!NEEDS_CATEGORY[i]) {
                    this.clickSlot(TARGET_SLOTS[i]);
                } else if (this.clickSlot(CATEGORY_SLOT)) {
                    this.pendingSlot = TARGET_SLOTS[i];
                    this.wait = delayTicks;
                    this.timeout = TIMEOUT_TICKS;
                }
                break;
            }
        }
        this.prev = pressed;
    }

    public void reset() {
        this.pendingSlot = -1;
        this.wait = 0;
        this.timeout = 0;
    }

    private boolean isSlotReady(int index) {
        if (!(mc.currentScreen instanceof GuiContainer)) {
            return false;
        }
        Container c = ((GuiContainer) mc.currentScreen).inventorySlots;
        return index >= 0 && index < c.inventorySlots.size() && c.getSlot(index).getHasStack();
    }

    private boolean clickSlot(int index) {
        if (!(mc.currentScreen instanceof GuiContainer)) {
            return false;
        }
        GuiContainer gui = (GuiContainer) mc.currentScreen;
        Container c = gui.inventorySlots;
        if (index < 0 || index >= c.inventorySlots.size()) {
            return false;
        }
        Slot slot = c.getSlot(index);
        try {
            if (clickMethod == null) {
                clickMethod = findMethod("mouseClicked", "func_73864_a");
                releaseMethod = findMethod("mouseReleased", "func_146286_b");
                guiLeftField = findField("guiLeft", "field_147003_i");
                guiTopField = findField("guiTop", "field_147009_r");
            }
            int x = guiLeftField.getInt(gui) + slot.xDisplayPosition + 8;
            int y = guiTopField.getInt(gui) + slot.yDisplayPosition + 8;
            clickMethod.invoke(gui, x, y, 0);
            releaseMethod.invoke(gui, x, y, 0);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static Method findMethod(String name, String srg) throws NoSuchMethodException {
        for (String n : new String[] { name, srg }) {
            try {
                Method m = GuiContainer.class.getDeclaredMethod(n, int.class, int.class, int.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Field findField(String name, String srg) throws NoSuchFieldException {
        for (String n : new String[] { name, srg }) {
            try {
                Field f = GuiContainer.class.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}