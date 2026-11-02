package com.yossi.chesscraft.client.ui;

import com.yossi.chesscraft.ChessCraft;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Rounded, chess-site style drawing helpers and widgets for the chess screen. */
public final class ChessUi {
	public static final Identifier ROUND = ChessCraft.id("textures/gui/ui/round.png");
	/** Open Sans (Apache 2.0), loaded as a TTF font: clean UI text instead of the pixel font. */
	public static final FontDescription UI_FONT = new FontDescription.Resource(ChessCraft.id("ui"));
	public static final FontDescription UI_BOLD = new FontDescription.Resource(ChessCraft.id("ui_bold"));

	// Buttons use the board's dark-square brown; only Start (and Join) stay green.
	public static final int BROWN = 0xFF9A6A43;
	public static final int BROWN_HOVER = 0xFFAD7C52;
	public static final int BROWN_EDGE = 0xFF664226;
	public static final int BROWN_DARK = 0xFF6E4A2F;
	public static final int CREAM = 0xFFF0D9B5;
	public static final int CREAM_TEXT = 0xFFF3E4CC;

	public static final int PANEL = 0xF5262421;
	public static final int SURFACE = 0xFF312E2B;
	public static final int CARD = 0xFF3C3936;
	public static final int CARD_HOVER = 0xFF474440;
	public static final int GREEN = 0xFF81B64C;
	public static final int GREEN_HOVER = 0xFF95C95F;
	public static final int GREEN_EDGE = 0xFF537A31;
	public static final int GREEN_PALE = 0xFF566B44;
	public static final int GREEN_PALE_EDGE = 0xFF3D4C30;
	public static final int GRAY = 0xFF4E4B47;
	public static final int GRAY_HOVER = 0xFF5C5955;
	public static final int GRAY_EDGE = 0xFF353330;
	public static final int RED = 0xFFE0533F;
	public static final int RED_EDGE = 0xFF9E3426;
	public static final int AMBER = 0xFFE8A33D;
	public static final int AMBER_EDGE = 0xFFA36F22;
	public static final int TEXT = 0xFFFFFFFF;
	public static final int MUTED = 0xFFA9A7A3;
	public static final int DIM = 0xFF6F6C68;

	private ChessUi() {
	}

	// ------------------------------------------------------------------ drawing

	/** Filled rounded rectangle: anti-aliased corners from the disc texture, straight parts as fills. */
	public static void roundRect(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) return;
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		if (r == 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, ROUND, x, y, 0, 0, r, r, 64, 64, 128, 128, color);
		g.blit(RenderPipelines.GUI_TEXTURED, ROUND, x + w - r, y, 64, 0, r, r, 64, 64, 128, 128, color);
		g.blit(RenderPipelines.GUI_TEXTURED, ROUND, x, y + h - r, 0, 64, r, r, 64, 64, 128, 128, color);
		g.blit(RenderPipelines.GUI_TEXTURED, ROUND, x + w - r, y + h - r, 64, 64, r, r, 64, 64, 128, 128, color);
		g.fill(x + r, y, x + w - r, y + h, color);
		g.fill(x, y + r, x + r, y + h - r, color);
		g.fill(x + w - r, y + r, x + w, y + h - r, color);
	}

	public static void circle(GuiGraphics g, int cx, int cy, int r, int color) {
		g.blit(RenderPipelines.GUI_TEXTURED, ROUND, cx - r, cy - r, 0, 0, 2 * r, 2 * r, 128, 128, 128, 128, color);
	}

	/** Raised rounded surface with a darker bottom lip (chess.com button look) and a soft drop shadow. */
	public static void raised(GuiGraphics g, int x, int y, int w, int h, int r, int face, int edge) {
		roundRect(g, x, y + 2, w, h, r, 0x40000000);
		roundRect(g, x, y, w, h, r, edge);
		roundRect(g, x, y, w, h - 3, r, face);
		roundRect(g, x + 2, y + 1, w - 4, Math.max(2, (h - 3) / 2), Math.max(1, r - 2), 0x14FFFFFF);
	}

	public static void icon(GuiGraphics g, String name, int x, int y, int size, int color) {
		Identifier id = ChessCraft.id("textures/gui/ui/icons/" + name + ".png");
		g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, size, size, 64, 64, 64, 64, color);
	}

	public static Component styled(String s, boolean bold) {
		return Component.literal(s).withStyle(st -> st.withFont(bold ? UI_BOLD : UI_FONT));
	}

	/** Trims a string with an ellipsis so it fits a width in the UI font. */
	public static String fit(Font font, String s, int maxW, boolean bold) {
		if (font.width(styled(s, bold)) <= maxW) return s;
		String t = s;
		while (t.length() > 1 && font.width(styled(t + "…", bold)) > maxW) t = t.substring(0, t.length() - 1);
		return t + "…";
	}

	public static void text(GuiGraphics g, Font font, String s, int x, int y, int color, boolean bold, float scale) {
		Component c = styled(s, bold);
		if (scale == 1f) {
			g.drawString(font, c, x, y, color, false);
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.drawString(font, c, 0, 0, color, false);
		g.pose().popMatrix();
	}

	public static int textWidth(Font font, String s, boolean bold, float scale) {
		Component c = styled(s, bold);
		return Math.round(font.width(c) * scale);
	}

	public static void centered(GuiGraphics g, Font font, String s, int cx, int y, int color, boolean bold, float scale) {
		text(g, font, s, cx - textWidth(font, s, bold, scale) / 2, y, color, bold, scale);
	}

	/** Wrapped muted text; returns the y after the last line. */
	public static int wrapped(GuiGraphics g, Font font, String s, int x, int y, int w, int color) {
		for (var line : font.split(styled(s, false), w)) {
			g.drawString(font, line, x, y, color, false);
			y += 10;
		}
		return y;
	}

	public static int withAlpha(int color, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0xFFFFFF);
	}

	// ------------------------------------------------------------------ widgets

	/** Base widget: a rectangle that renders itself and may react to the mouse. */
	public abstract static class Widget {
		public int x, y, w, h;
		public boolean visible = true;
		public BooleanSupplier enabled = () -> true;
		public @Nullable String tooltip;

		protected Widget(int x, int y, int w, int h) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
		}

		public boolean hovered(double mx, double my) {
			return visible && mx >= x && mx < x + w && my >= y && my < y + h;
		}

		public abstract void render(GuiGraphics g, Font font, int mx, int my);

		public boolean click(double mx, double my, int button) {
			return false;
		}

		public void drag(double mx, double my) {
		}

		public void release() {
		}

		/** Tooltip for the point under the mouse (widgets with several parts override this). */
		public @Nullable String tooltipAt(double mx, double my) {
			return hovered(mx, my) ? tooltip : null;
		}

		public Widget tooltip(String t) {
			tooltip = t;
			return this;
		}

		public Widget enabledWhen(BooleanSupplier s) {
			enabled = s;
			return this;
		}
	}

	public enum Style { PRIMARY, SECONDARY, DANGER, AMBER, GHOST }

	/** Rounded push button with an optional icon. */
	public static class Button extends Widget {
		private final Runnable action;
		private final Style style;
		public String label;
		public @Nullable String icon;
		public BooleanSupplier selected = () -> false;
		public float textScale = 1f;
		public boolean bold = true;

		public Button(int x, int y, int w, int h, String label, @Nullable String icon, Style style, Runnable action) {
			super(x, y, w, h);
			this.label = label;
			this.icon = icon;
			this.style = style;
			this.action = action;
		}

		@Override
		public void render(GuiGraphics g, Font font, int mx, int my) {
			if (!visible) return;
			boolean on = enabled.getAsBoolean();
			boolean hover = on && hovered(mx, my);
			int r = Math.min(h / 2, 8);
			int face, edge;
			int textColor = TEXT;
			switch (style) {
				case PRIMARY -> {
					face = on ? (hover ? GREEN_HOVER : GREEN) : GREEN_PALE;
					edge = on ? GREEN_EDGE : GREEN_PALE_EDGE;
					if (!on) textColor = 0xFFA6B39A;
				}
				case DANGER -> {
					face = hover ? BROWN_HOVER : BROWN;
					edge = BROWN_EDGE;
				}
				case AMBER -> {
					face = AMBER;
					edge = AMBER_EDGE;
				}
				case GHOST -> {
					face = hover ? 0x40FFFFFF : 0x00000000;
					edge = 0x00000000;
					textColor = hover ? TEXT : MUTED;
				}
				default -> {
					face = on ? (hover ? BROWN_HOVER : BROWN) : 0xFF5A4636;
					edge = BROWN_EDGE;
					if (!on) textColor = 0xFF9C8B79;
				}
			}
			if (selected.getAsBoolean()) roundRect(g, x - 2, y - 2, w + 4, h + 4, r + 2, CREAM);
			if (style == Style.GHOST) roundRect(g, x, y, w, h, r, face);
			else raised(g, x, y, w, h, r, face, edge);
			int lift = style == Style.GHOST ? 0 : 1;
			int contentH = h - 3 * lift;
			int iconSize = icon == null ? 0 : Math.min(contentH - 4, 14);
			int tw = label.isEmpty() ? 0 : textWidth(font, label, bold, textScale);
			int gap = icon != null && tw > 0 ? 4 : 0;
			int total = iconSize + gap + tw;
			int sx = x + (w - total) / 2;
			if (icon != null) icon(g, icon, sx, y + (contentH - iconSize) / 2, iconSize, textColor);
			if (tw > 0) {
				int ty = y + (contentH - Math.round(8 * textScale)) / 2;
				text(g, font, label, sx + iconSize + gap, ty, textColor, bold, textScale);
			}
		}

		@Override
		public boolean click(double mx, double my, int button) {
			if (button == 0 && hovered(mx, my) && enabled.getAsBoolean()) {
				action.run();
				return true;
			}
			return hovered(mx, my);
		}
	}

	/** Circular icon button. */
	public static class IconButton extends Widget {
		private final Runnable action;
		private final String icon;
		private final Style style;

		public IconButton(int x, int y, int size, String icon, Style style, Runnable action) {
			super(x, y, size, size);
			this.icon = icon;
			this.style = style;
			this.action = action;
		}

		@Override
		public void render(GuiGraphics g, Font font, int mx, int my) {
			if (!visible) return;
			boolean on = enabled.getAsBoolean();
			boolean hover = on && hovered(mx, my);
			int r = w / 2;
			int face = switch (style) {
				case PRIMARY -> hover ? GREEN_HOVER : GREEN;
				case DANGER -> hover ? BROWN_HOVER : BROWN;
				case GHOST -> hover ? 0x40FFFFFF : 0x00000000;
				default -> on ? (hover ? BROWN_HOVER : BROWN) : 0xFF5A4636;
			};
			if (style != Style.GHOST) circle(g, x + r, y + r + 1, r, 0x50000000);
			circle(g, x + r, y + r, r, face);
			int s = Math.max(6, w * 11 / 20);
			icon(g, icon, x + (w - s) / 2, y + (h - s) / 2, s, on ? TEXT : 0xFF9C8B79);
		}

		@Override
		public boolean click(double mx, double my, int button) {
			if (button == 0 && hovered(mx, my) && enabled.getAsBoolean()) {
				action.run();
				return true;
			}
			return hovered(mx, my);
		}
	}

	/** Navigation card: icon tile, title, subtitle and a chevron. */
	public static class Card extends Widget {
		private final Runnable action;
		private final String icon;
		private final String title;
		private final java.util.function.Supplier<String> subtitle;
		private final BooleanSupplier selected;
		private final int iconColor;

		public Card(int x, int y, int w, int h, String icon, int iconColor, String title,
				java.util.function.Supplier<String> subtitle, BooleanSupplier selected, Runnable action) {
			super(x, y, w, h);
			this.icon = icon;
			this.iconColor = iconColor;
			this.title = title;
			this.subtitle = subtitle;
			this.selected = selected;
			this.action = action;
		}

		@Override
		public void render(GuiGraphics g, Font font, int mx, int my) {
			boolean hover = hovered(mx, my);
			boolean sel = selected.getAsBoolean();
			int r = 7;
			if (sel) roundRect(g, x - 2, y - 2, w + 4, h + 4, r + 2, CREAM);
			roundRect(g, x, y + 2, w, h, r, BROWN_EDGE);
			roundRect(g, x, y, w, h, r, hover ? BROWN_HOVER : BROWN);
			int tile = h - 8;
			roundRect(g, x + 4, y + 4, tile, tile, 6, BROWN_DARK);
			icon(g, icon, x + 4 + tile / 5, y + 4 + tile / 5, tile * 3 / 5, TEXT);
			int tx = x + tile + 10;
			String sub = subtitle.get();
			boolean two = h >= 22 && !sub.isEmpty();
			text(g, font, title, tx, y + (two ? h / 2 - 9 : (h - 8) / 2), TEXT, true, 1f);
			if (two) text(g, font, sub, tx, y + h / 2 + 1, CREAM_TEXT, false, 1f);
			icon(g, sel ? "check" : "chevron", x + w - 14, y + (h - 9) / 2, 9, TEXT);
		}

		@Override
		public boolean click(double mx, double my, int button) {
			if (button == 0 && hovered(mx, my)) {
				action.run();
				return true;
			}
			return false;
		}
	}

	/** Label with an iOS-style on/off switch. */
	public static class Toggle extends Widget {
		private final String label;
		private final BooleanSupplier value;
		private final java.util.function.Consumer<Boolean> set;
		private float anim = -1;

		public Toggle(int x, int y, int w, int h, String label, BooleanSupplier value, java.util.function.Consumer<Boolean> set) {
			super(x, y, w, h);
			this.label = label;
			this.value = value;
			this.set = set;
		}

		@Override
		public void render(GuiGraphics g, Font font, int mx, int my) {
			boolean on = value.getAsBoolean();
			if (anim < 0) anim = on ? 1 : 0;
			anim += ((on ? 1 : 0) - anim) * 0.35f;
			boolean hover = hovered(mx, my);
			roundRect(g, x, y, w, h, 6, hover ? BROWN_HOVER : BROWN);
			text(g, font, label, x + 7, y + (h - 8) / 2, TEXT, false, 1f);
			int tw = 24, th = 13;
			int tx = x + w - tw - 6, ty = y + (h - th) / 2;
			int track = lerp(BROWN_DARK, CREAM, anim);
			roundRect(g, tx, ty, tw, th, th / 2, track);
			int knobX = Math.round(tx + th / 2f + anim * (tw - th));
			circle(g, knobX, ty + th / 2 + 1, th / 2 - 1, 0x40000000);
			circle(g, knobX, ty + th / 2, th / 2 - 1, lerp(CREAM, BROWN_EDGE, anim));
		}

		@Override
		public boolean click(double mx, double my, int button) {
			if (button == 0 && hovered(mx, my)) {
				set.accept(!value.getAsBoolean());
				return true;
			}
			return false;
		}
	}

	/** Labelled slider over an integer range with a step. */
	public static class Slider extends Widget {
		private final String label;
		private final int min, max, step;
		private final IntSupplier value;
		private final IntConsumer set;
		private final IntFunction<String> describe;
		private boolean dragging;

		public Slider(int x, int y, int w, int h, String label, int min, int max, int step,
				IntSupplier value, IntConsumer set, IntFunction<String> describe) {
			super(x, y, w, h);
			this.label = label;
			this.min = min;
			this.max = max;
			this.step = step;
			this.value = value;
			this.set = set;
			this.describe = describe;
		}

		@Override
		public void render(GuiGraphics g, Font font, int mx, int my) {
			roundRect(g, x, y, w, h, 6, BROWN);
			int v = value.getAsInt();
			text(g, font, label, x + 7, y + 4, CREAM_TEXT, false, 1f);
			String val = String.valueOf(v);
			String desc = describe.apply(v);
			int vw = textWidth(font, val, true, 1f);
			text(g, font, val, x + w - 7 - vw, y + 4, TEXT, true, 1f);
			if (!desc.isEmpty()) text(g, font, desc, x + w - 11 - vw - textWidth(font, desc, false, 0.8f), y + 5, CREAM_TEXT, false, 0.8f);
			int tx = x + 9, tw = w - 18, ty = y + h - 9;
			roundRect(g, tx, ty, tw, 4, 2, BROWN_DARK);
			float t = (v - min) / (float) (max - min);
			roundRect(g, tx, ty, Math.max(4, Math.round(tw * t)), 4, 2, CREAM);
			int kx = tx + Math.round(tw * t);
			boolean hover = dragging || (mx >= kx - 7 && mx <= kx + 7 && my >= ty - 6 && my <= ty + 10);
			circle(g, kx, ty + 3, 6, 0x50000000);
			circle(g, kx, ty + 2, hover ? 6 : 5, TEXT);
		}

		@Override
		public boolean click(double mx, double my, int button) {
			if (button == 0 && hovered(mx, my)) {
				dragging = true;
				drag(mx, my);
				return true;
			}
			return false;
		}

		@Override
		public void drag(double mx, double my) {
			if (!dragging) return;
			float t = (float) ((mx - (x + 9)) / (w - 18));
			t = Math.max(0, Math.min(1, t));
			int v = Math.round((min + t * (max - min)) / step) * step;
			set.accept(Math.max(min, Math.min(max, v)));
		}

		@Override
		public void release() {
			dragging = false;
		}
	}

	/** Pill-shaped segmented control. */
	public static class Segmented extends Widget {
		private final List<String> options;
		private final IntSupplier selected;
		private final IntConsumer select;
		private @Nullable List<String> tips;

		public Segmented tooltips(List<String> t) {
			tips = t;
			return this;
		}

		@Override
		public @Nullable String tooltipAt(double mx, double my) {
			if (!hovered(mx, my)) return null;
			if (tips == null) return tooltip;
			int seg = (w - 4) / options.size();
			int i = (int) Math.max(0, Math.min(options.size() - 1, (mx - x - 2) / seg));
			return tips.get(i);
		}

		public Segmented(int x, int y, int w, int h, List<String> options, IntSupplier selected, IntConsumer select) {
			super(x, y, w, h);
			this.options = options;
			this.selected = selected;
			this.select = select;
		}

		@Override
		public void render(GuiGraphics g, Font font, int mx, int my) {
			roundRect(g, x, y, w, h, h / 2, BROWN_DARK);
			int n = options.size();
			int seg = (w - 4) / n;
			int sel = selected.getAsInt();
			for (int i = 0; i < n; i++) {
				int sx = x + 2 + i * seg;
				boolean hover = mx >= sx && mx < sx + seg && my >= y && my < y + h;
				if (i == sel) roundRect(g, sx, y + 2, seg, h - 4, (h - 4) / 2, BROWN_HOVER);
				else if (hover) roundRect(g, sx, y + 2, seg, h - 4, (h - 4) / 2, 0x30FFFFFF);
				centered(g, font, options.get(i), sx + seg / 2, y + (h - 8) / 2, i == sel ? TEXT : CREAM_TEXT, i == sel, 1f);
			}
		}

		@Override
		public boolean click(double mx, double my, int button) {
			if (button != 0 || !hovered(mx, my)) return false;
			int seg = (w - 4) / options.size();
			int i = (int) Math.max(0, Math.min(options.size() - 1, (mx - x - 2) / seg));
			select.accept(i);
			return true;
		}
	}

	public static int lerp(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int ra = (a >> 16) & 255, ga = (a >> 8) & 255, ba = a & 255, aa = (a >>> 24);
		int rb = (b >> 16) & 255, gb = (b >> 8) & 255, bb = b & 255, ab = (b >>> 24);
		return (Math.round(aa + (ab - aa) * t) << 24) | (Math.round(ra + (rb - ra) * t) << 16)
				| (Math.round(ga + (gb - ga) * t) << 8) | Math.round(ba + (bb - ba) * t);
	}
}
