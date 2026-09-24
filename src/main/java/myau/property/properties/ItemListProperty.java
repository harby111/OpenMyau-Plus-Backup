package myau.property.properties;

import net.minecraft.item.ItemStack;

import java.util.function.BooleanSupplier;

public class ItemListProperty extends TextProperty {

    public ItemListProperty(String name, String value) {
        super(name, value);
    }

    public ItemListProperty(String name, String value, BooleanSupplier booleanSupplier) {
        super(name, value, booleanSupplier);
    }

    /** Partial match on unlocalized or display name (substring). */
    public boolean matches(ItemStack stack) {
        if (stack == null) return false;
        String val = this.getValue();
        if (val == null || val.isEmpty()) return false;

        String[] items = val.split(",");
        String itemName = stack.getUnlocalizedName().toLowerCase();
        String displayName = stack.getDisplayName().toLowerCase();

        for (String item : items) {
            item = item.trim().toLowerCase();
            if (item.isEmpty()) continue;
            if (itemName.contains(item) || displayName.contains(item)) {
                return true;
            }
        }
        return false;
    }

    /** Exact match on full unlocalized name or full display name (case-insensitive). */
    public boolean matchesExact(ItemStack stack) {
        if (stack == null) return false;
        String val = this.getValue();
        if (val == null || val.isEmpty()) return false;

        String[] items = val.split(",");
        String itemName = stack.getUnlocalizedName().trim().toLowerCase();
        String displayName = stack.getDisplayName().trim().toLowerCase();

        for (String item : items) {
            item = item.trim().toLowerCase();
            if (item.isEmpty()) continue;
            if (itemName.equals(item) || displayName.equals(item)) {
                return true;
            }
        }
        return false;
    }
}
