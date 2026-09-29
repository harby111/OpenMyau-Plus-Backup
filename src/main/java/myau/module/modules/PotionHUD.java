package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import myau.util.RenderUtil;
import myau.util.font.FontManager;
import myau.util.font.impl.FontRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.StatCollector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PotionHUD extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int TIMER_COLOR = 0xFFAAAAAA;
    private static final int EDIT_OUTLINE_COLOR = 0xFFFFFFFF;
    private static final int BACKGROUND_COLOR = 0x6E000000;
    private static final String PLACEHOLDER_TEXT = "No Potions Active";
    private static final float DEFAULT_RELATIVE_X = 0.02f;
    private static final float DEFAULT_RELATIVE_Y = 0.35f;

    public final ModeProperty timeFormat = new ModeProperty("time-format", 0, new String[]{"1m20s", "01:20"});
    public final ModeProperty sortMode = new ModeProperty("sort-by", 0, new String[]{"Duration", "Length"});
    public final ModeProperty sortDirection = new ModeProperty("sort-direction", 0, new String[]{"Descending", "Ascending"});
    public final ModeProperty horizontalAlignment = new ModeProperty("horizontal-align", 0, new String[]{"Left", "Center", "Right"});
    public final ModeProperty verticalAlignment = new ModeProperty("vertical-align", 0, new String[]{"Top", "Center", "Bottom"});
    public final ModeProperty fontMode = new ModeProperty("font", 0, new String[]{
            "Minecraft", "ProductSans", "Regular", "Tenacity", "Vision", "NbpInforma", "TahomaBold"
    });
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("offset-x", 0, -500, 500);
    public final IntProperty offsetY = new IntProperty("offset-y", 0, -500, 500);
    public final BooleanProperty excludePermanent = new BooleanProperty("exclude-permanent", false);
    public final BooleanProperty drawBackground = new BooleanProperty("draw-background", false);
    public final BooleanProperty textShadow = new BooleanProperty("text-shadow", true);
    public final BooleanProperty showIcons = new BooleanProperty("show-icons", true);
    public final BooleanProperty hideInMenus = new BooleanProperty("hide-in-menus", true);
    public final BooleanProperty chatPreview = new BooleanProperty("chat-preview", true);
    public final BooleanProperty editPosition = new BooleanProperty("edit-position", false);
    public final TextProperty potionBlacklist = new TextProperty("potion-blacklist", "");

    private float posX = Float.NaN;
    private float posY = Float.NaN;
    private float relativePosX = Float.NaN;
    private float relativePosY = Float.NaN;

    public PotionHUD() {
        super("PotionHUD", false, false, "Lists active potion effects on screen");
    }

    @Override
    public void verifyValue(String name) {
        if ("edit-position".equalsIgnoreCase(name) && this.editPosition.getValue()) {
            this.editPosition.setValue(false);
            if (mc.thePlayer != null) {
                mc.displayGuiScreen(new EditScreen());
            }
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (this.editPosition.getValue()) {
            this.editPosition.setValue(false);
            mc.displayGuiScreen(new EditScreen());
            return;
        }

        boolean inChat = mc.currentScreen instanceof GuiChat;
        if (mc.currentScreen != null && !(inChat && this.chatPreview.getValue())) {
            if (this.hideInMenus.getValue()) {
                return;
            }
        }
        if (mc.gameSettings.showDebugInfo) {
            return;
        }

        render(false);
    }

    private void render(boolean editing) {
        ScaledResolution resolution = new ScaledResolution(mc);
        syncPositionToResolution(resolution);

        RenderState state = buildRenderState(editing);
        if (state.entries.isEmpty()) {
            return;
        }

        renderState(state, editing);
    }

    private RenderState buildRenderState(boolean includePlaceholder) {
        FontBridge font = getFontBridge();
        float scaleValue = this.scale.getValue();
        LayoutMetrics metrics = LayoutMetrics.from(font, scaleValue, this.showIcons.getValue());
        ArrayList<PotionEntry> entries = new ArrayList<PotionEntry>();
        Collection<PotionEffect> activeEffects = mc.thePlayer.getActivePotionEffects();
        Set<String> blacklist = parseBlacklist();

        if (activeEffects != null) {
            for (PotionEffect effect : activeEffects) {
                PotionEntry entry = buildEntry(effect, font, blacklist);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }

        sortEntries(entries);

        if (entries.isEmpty() && includePlaceholder) {
            entries.add(PotionEntry.placeholder(font));
        }

        int maxWidth = 0;
        for (PotionEntry entry : entries) {
            maxWidth = Math.max(maxWidth, entry.totalWidth);
        }

        return new RenderState(font, metrics, entries, maxWidth, scaleValue);
    }

    private PotionEntry buildEntry(PotionEffect effect, FontBridge font, Set<String> blacklist) {
        if (effect == null) {
            return null;
        }

        int duration = effect.getDuration();
        if (this.excludePermanent.getValue() && duration > 32000) {
            return null;
        }

        if (effect.getPotionID() < 0 || effect.getPotionID() >= Potion.potionTypes.length) {
            return null;
        }
        Potion potion = Potion.potionTypes[effect.getPotionID()];
        if (potion == null) {
            return null;
        }

        String potionName = potion.getName();
        if (potionName != null && blacklist.contains(potionName.toLowerCase(Locale.ROOT))) {
            return null;
        }

        String label = getPotionLabel(effect, potion);
        String durationText = formatDuration(duration);
        int labelWidth = font.getStringWidth(label);
        int durationWidth = durationText.isEmpty() ? 0 : font.getStringWidth(durationText);
        int gapWidth = durationWidth > 0 ? font.getStringWidth(" ") : 0;
        int color = potion.getLiquidColor() | 0xFF000000;
        return new PotionEntry(label, durationText, labelWidth, durationWidth, gapWidth, duration, color, effect);
    }

    private void sortEntries(List<PotionEntry> entries) {
        final int directionMultiplier = this.sortDirection.getValue() == 1 ? 1 : -1;

        if (this.sortMode.getValue() == 0) {
            entries.sort(new Comparator<PotionEntry>() {
                @Override
                public int compare(PotionEntry first, PotionEntry second) {
                    return Integer.compare(first.durationTicks, second.durationTicks) * directionMultiplier;
                }
            });
            return;
        }

        entries.sort(new Comparator<PotionEntry>() {
            @Override
            public int compare(PotionEntry first, PotionEntry second) {
                return Integer.compare(first.totalWidth, second.totalWidth) * directionMultiplier;
            }
        });
    }

    private Bounds renderState(RenderState state, boolean editing) {
        int horizontalAlignMode = this.horizontalAlignment.getValue();
        int verticalAlignMode = this.verticalAlignment.getValue();
        float stackHeight = state.entries.size() * state.metrics.rowHeight;
        float firstRowTop = verticalAlignMode == 0
                ? posY
                : verticalAlignMode == 1 ? posY - stackHeight / 2.0f : posY - stackHeight;
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        for (int i = 0; i < state.entries.size(); i++) {
            PotionEntry entry = state.entries.get(i);
            float rowTop = firstRowTop + i * state.metrics.rowHeight;
            float contentWidth = entry.totalWidth + (this.showIcons.getValue() && entry.effect != null ? state.metrics.iconSize + 2 : 0);
            float rowLeft = horizontalAlignMode == 0
                    ? posX
                    : horizontalAlignMode == 2 ? posX - contentWidth : posX - contentWidth / 2.0f;
            float textLeft = rowLeft + (this.showIcons.getValue() && entry.effect != null ? state.metrics.iconSize + 2 : 0);
            float backgroundLeft = rowLeft - state.metrics.horizontalTextPadding;
            float backgroundRight = rowLeft + contentWidth + state.metrics.horizontalTextPadding;
            float backgroundBottom = rowTop + state.metrics.rowHeight;
            float textY = rowTop + state.metrics.textTopPadding;
            float timerX = textLeft + entry.labelWidth + entry.gapWidth;

            if (this.drawBackground.getValue()) {
                Gui.drawRect(
                        Math.round(backgroundLeft),
                        Math.round(rowTop),
                        Math.round(backgroundRight),
                        Math.round(backgroundBottom),
                        BACKGROUND_COLOR
                );
            }

            if (this.showIcons.getValue() && entry.effect != null) {
                int iconX = Math.round(rowLeft);
                int iconY = Math.round(rowTop + (state.metrics.rowHeight - state.metrics.iconSize) / 2.0f);
                try {
                    RenderUtil.renderPotionEffect(entry.effect, iconX, iconY);
                } catch (Throwable ignored) {
                }
            }

            state.font.drawString(entry.label, textLeft, textY, entry.color, this.textShadow.getValue());
            if (!entry.durationText.isEmpty()) {
                state.font.drawString(entry.durationText, timerX, textY, TIMER_COLOR, this.textShadow.getValue());
            }

            minX = Math.min(minX, backgroundLeft);
            minY = Math.min(minY, rowTop);
            maxX = Math.max(maxX, backgroundRight);
            maxY = Math.max(maxY, backgroundBottom);
        }

        GlStateManager.disableBlend();
        GlStateManager.popMatrix();

        Bounds bounds = new Bounds(minX, minY, maxX, maxY);
        if (editing) {
            drawBounds(bounds);
        }
        return bounds;
    }

    private void drawBounds(Bounds bounds) {
        float left = bounds.left - 1.0f;
        float top = bounds.top - 1.0f;
        float right = bounds.right + 1.0f;
        float bottom = bounds.bottom + 1.0f;
        Gui.drawRect(Math.round(left), Math.round(top), Math.round(right), Math.round(top + 1), EDIT_OUTLINE_COLOR);
        Gui.drawRect(Math.round(left), Math.round(bottom - 1), Math.round(right), Math.round(bottom), EDIT_OUTLINE_COLOR);
        Gui.drawRect(Math.round(left), Math.round(top), Math.round(left + 1), Math.round(bottom), EDIT_OUTLINE_COLOR);
        Gui.drawRect(Math.round(right - 1), Math.round(top), Math.round(right), Math.round(bottom), EDIT_OUTLINE_COLOR);
    }

    private void syncPositionToResolution() {
        syncPositionToResolution(new ScaledResolution(mc));
    }

    private void syncPositionToResolution(ScaledResolution resolution) {
        int scaledWidth = Math.max(1, resolution.getScaledWidth());
        int scaledHeight = Math.max(1, resolution.getScaledHeight());

        if (Float.isNaN(relativePosX) || Float.isNaN(relativePosY)) {
            relativePosX = DEFAULT_RELATIVE_X;
            relativePosY = DEFAULT_RELATIVE_Y;
        }

        posX = relativePosX * scaledWidth + this.offsetX.getValue();
        posY = relativePosY * scaledHeight + this.offsetY.getValue();
    }

    private void setAbsolutePosition(float absoluteX, float absoluteY, ScaledResolution resolution) {
        int scaledWidth = Math.max(1, resolution.getScaledWidth());
        int scaledHeight = Math.max(1, resolution.getScaledHeight());
        relativePosX = (absoluteX - this.offsetX.getValue()) / scaledWidth;
        relativePosY = (absoluteY - this.offsetY.getValue()) / scaledHeight;
        posX = absoluteX;
        posY = absoluteY;
    }

    private void resetPosition() {
        relativePosX = DEFAULT_RELATIVE_X;
        relativePosY = DEFAULT_RELATIVE_Y;
        syncPositionToResolution();
    }

    private FontBridge getFontBridge() {
        FontRenderer custom = null;
        switch (this.fontMode.getValue()) {
            case 1:
                custom = FontManager.productSans20;
                break;
            case 2:
                custom = FontManager.regular22;
                break;
            case 3:
                custom = FontManager.tenacity20;
                break;
            case 4:
                custom = FontManager.vision20;
                break;
            case 5:
                custom = FontManager.nbpInforma20;
                break;
            case 6:
                custom = FontManager.tahomaBold20;
                break;
            default:
                break;
        }
        if (custom != null) {
            return new FontBridge(custom, null);
        }
        return new FontBridge(null, mc.fontRendererObj);
    }

    private String getPotionLabel(PotionEffect effect, Potion potion) {
        String label = StatCollector.translateToLocal(effect.getEffectName());
        if (effect.getAmplifier() >= 1) {
            label += " " + toRomanNumeral(effect.getAmplifier() + 1);
        }
        if (label == null || label.isEmpty()) {
            label = StatCollector.translateToLocal(potion.getName());
        }
        return label;
    }

    private String formatDuration(int durationTicks) {
        int totalSeconds = Math.max(0, durationTicks / 20);
        if (this.timeFormat.getValue() == 1) {
            int minutes = totalSeconds / 60;
            int seconds = totalSeconds % 60;
            return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
        }

        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (minutes > 0 && seconds == 0) {
            return minutes + "m";
        }
        return (minutes > 0 ? minutes + "m" : "") + seconds + "s";
    }

    private Set<String> parseBlacklist() {
        Set<String> set = new HashSet<String>();
        String raw = this.potionBlacklist.getValue();
        if (raw == null || raw.trim().isEmpty()) {
            return set;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return set;
    }

    private static String toRomanNumeral(int value) {
        if (value <= 0) {
            return Integer.toString(value);
        }
        int[] values = new int[]{1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = new String[]{"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder builder = new StringBuilder();
        int remaining = value;
        for (int i = 0; i < values.length; i++) {
            while (remaining >= values[i]) {
                builder.append(numerals[i]);
                remaining -= values[i];
            }
        }
        return builder.toString();
    }

    private static final class FontBridge {
        private final FontRenderer custom;
        private final net.minecraft.client.gui.FontRenderer vanilla;

        private FontBridge(FontRenderer custom, net.minecraft.client.gui.FontRenderer vanilla) {
            this.custom = custom;
            this.vanilla = vanilla;
        }

        private int getStringWidth(String text) {
            if (custom != null) {
                return (int) Math.ceil(custom.getStringWidth(text));
            }
            return vanilla.getStringWidth(text);
        }

        private int getHeight() {
            if (custom != null) {
                return (int) Math.ceil(custom.getHeight());
            }
            return vanilla.FONT_HEIGHT;
        }

        private void drawString(String text, float x, float y, int color, boolean shadow) {
            if (custom != null) {
                custom.drawString(text, x, y, color, shadow);
            } else {
                vanilla.drawString(text, x, y, color, shadow);
            }
        }
    }

    private static final class PotionEntry {
        private final String label;
        private final String durationText;
        private final int labelWidth;
        private final int durationWidth;
        private final int gapWidth;
        private final int totalWidth;
        private final int durationTicks;
        private final int color;
        private final PotionEffect effect;

        private PotionEntry(String label, String durationText, int labelWidth, int durationWidth,
                            int gapWidth, int durationTicks, int color, PotionEffect effect) {
            this.label = label;
            this.durationText = durationText;
            this.labelWidth = labelWidth;
            this.durationWidth = durationWidth;
            this.gapWidth = gapWidth;
            this.totalWidth = labelWidth + gapWidth + durationWidth;
            this.durationTicks = durationTicks;
            this.color = color;
            this.effect = effect;
        }

        private static PotionEntry placeholder(FontBridge font) {
            String text = PLACEHOLDER_TEXT;
            return new PotionEntry(text, "", font.getStringWidth(text), 0, 0, 0, 0xFFFFFFFF, null);
        }
    }

    private static final class LayoutMetrics {
        private final int textTopPadding;
        private final int horizontalTextPadding;
        private final int rowHeight;
        private final int iconSize;

        private LayoutMetrics(int textTopPadding, int horizontalTextPadding, int rowHeight, int iconSize) {
            this.textTopPadding = textTopPadding;
            this.horizontalTextPadding = horizontalTextPadding;
            this.rowHeight = rowHeight;
            this.iconSize = iconSize;
        }

        private static LayoutMetrics from(FontBridge font, float fontScale, boolean icons) {
            int textHeight = Math.max(1, font.getHeight());
            int textTopPadding = Math.max(1, Math.round(2.0f * fontScale));
            int horizontalTextPadding = Math.max(1, Math.round(2.0f * fontScale));
            int iconSize = icons ? 18 : 0;
            int rowHeight = Math.max(textHeight + textTopPadding * 2, icons ? iconSize + 2 : 0);
            return new LayoutMetrics(textTopPadding, horizontalTextPadding, rowHeight, iconSize);
        }
    }

    private static final class RenderState {
        private final FontBridge font;
        private final LayoutMetrics metrics;
        private final List<PotionEntry> entries;
        private final int maxWidth;
        private final float scale;

        private RenderState(FontBridge font, LayoutMetrics metrics, List<PotionEntry> entries, int maxWidth, float scale) {
            this.font = font;
            this.metrics = metrics;
            this.entries = entries;
            this.maxWidth = maxWidth;
            this.scale = scale;
        }
    }

    private static final class Bounds {
        private final float left;
        private final float top;
        private final float right;
        private final float bottom;

        private Bounds(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private class EditScreen extends GuiScreen {
        private GuiButton resetButton;
        private boolean dragging;
        private float minX;
        private float minY;
        private float maxX;
        private float maxY;
        private float actualX;
        private float actualY;
        private float lastActualX;
        private float lastActualY;
        private int lastMouseX;
        private int lastMouseY;

        @Override
        public void initGui() {
            super.initGui();
            this.buttonList.clear();
            this.resetButton = new GuiButton(1, this.width - 90, this.height - 25, 85, 20, "Reset");
            this.buttonList.add(this.resetButton);
            syncPositionToResolution(new ScaledResolution(this.mc));
            this.actualX = posX;
            this.actualY = posY;
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            ScaledResolution resolution = new ScaledResolution(this.mc);
            if (!this.dragging) {
                syncPositionToResolution(resolution);
                this.actualX = posX;
                this.actualY = posY;
            }

            drawRect(0, 0, this.width, this.height, 0xB2000000);
            setAbsolutePosition(this.actualX, this.actualY, resolution);

            RenderState state = buildRenderState(true);
            Bounds bounds = renderState(state, true);

            this.minX = bounds.left;
            this.minY = bounds.top;
            this.maxX = bounds.right;
            this.maxY = bounds.bottom;
            this.actualX = posX;
            this.actualY = posY;

            String message = "Drag the HUD. Press Esc when done.";
            int textX = resolution.getScaledWidth() / 2 - this.fontRendererObj.getStringWidth(message) / 2;
            int textY = resolution.getScaledHeight() / 2 - 20;
            this.fontRendererObj.drawStringWithShadow(message, textX, textY, 0xFFFFFFFF);

            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
            super.mouseClickMove(mouseX, mouseY, button, timeSinceLastClick);
            if (button != 0) {
                return;
            }
            if (this.dragging) {
                this.actualX = this.lastActualX + (mouseX - this.lastMouseX);
                this.actualY = this.lastActualY + (mouseY - this.lastMouseY);
            } else if (mouseX >= this.minX && mouseX <= this.maxX && mouseY >= this.minY && mouseY <= this.maxY) {
                this.dragging = true;
                this.lastMouseX = mouseX;
                this.lastMouseY = mouseY;
                this.lastActualX = this.actualX;
                this.lastActualY = this.actualY;
            }
        }

        @Override
        protected void mouseReleased(int mouseX, int mouseY, int state) {
            super.mouseReleased(mouseX, mouseY, state);
            if (state == 0) {
                this.dragging = false;
            }
        }

        @Override
        protected void actionPerformed(GuiButton button) {
            if (button == this.resetButton) {
                resetPosition();
                this.actualX = posX;
                this.actualY = posY;
            }
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }
    }
}