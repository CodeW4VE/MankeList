package xyz.w4ve.mankelist.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Config screen: everything is switches/cycles built from vanilla widgets (no
 * malilib/YACL/cloth), two columns. Opens from Mod Menu or the bindable key.
 * Right-clicking a cycle steps backwards (setValue does not fire the vanilla
 * callback, so the value is applied by hand).
 */
public class ConfigScreen extends Screen {
	private final Screen parent;
	private final MankeListConfig cfg = MankeListConfig.get();
	private final Map<CycleButton<?>, Runnable> backCycles = new LinkedHashMap<>();
	private int maxLinesLabelX;
	private int maxLinesLabelY;

	public ConfigScreen(Screen parent) {
		super(Component.translatable("screen.mankelist.title"));
		this.parent = parent;
	}

	private <T> void addCycle(CycleButton<T> btn, List<T> values, Consumer<T> apply) {
		addRenderableWidget(btn);
		backCycles.put(btn, () -> {
			int i = values.indexOf(btn.getValue());
			T prev = values.get(Math.floorMod(i - 1, values.size()));
			btn.setValue(prev);
			apply.accept(prev);
		});
	}

	private void addToggle(CycleButton<Boolean> btn, Consumer<Boolean> apply) {
		addCycle(btn, List.of(Boolean.TRUE, Boolean.FALSE), apply);
	}

	@Override
	protected void init() {
		backCycles.clear();
		int colW = 150;
		int gap = 6;
		int leftX = this.width / 2 - colW - gap / 2;
		int rightX = this.width / 2 + gap / 2;
		int y0 = 40;
		int step = 24;
		int i = 0;

		// Left column: what is shown
		addToggle(CycleButton.onOffBuilder(cfg.hudEnabled)
				.create(leftX, y0 + step * i++, colW, 20, Component.translatable("option.mankelist.hud"),
						(btn, val) -> cfg.hudEnabled = val), val -> cfg.hudEnabled = val);

		addToggle(CycleButton.onOffBuilder(cfg.showCompleted)
				.create(leftX, y0 + step * i++, colW, 20, Component.translatable("option.mankelist.show_completed"),
						(btn, val) -> cfg.showCompleted = val), val -> cfg.showCompleted = val);

		addToggle(CycleButton.onOffBuilder(cfg.inventoryCount)
				.create(leftX, y0 + step * i++, colW, 20, Component.translatable("option.mankelist.inventory_count"),
						(btn, val) -> cfg.inventoryCount = val), val -> cfg.inventoryCount = val);

		addToggle(CycleButton.onOffBuilder(cfg.sortByMissing)
				.create(leftX, y0 + step * i++, colW, 20, Component.translatable("option.mankelist.sort_missing"),
						(btn, val) -> cfg.sortByMissing = val), val -> cfg.sortByMissing = val);

		addToggle(CycleButton.onOffBuilder(cfg.showIcons)
				.create(leftX, y0 + step * i, colW, 20, Component.translatable("option.mankelist.icons"),
						(btn, val) -> cfg.showIcons = val), val -> cfg.showIcons = val);

		// Right column: how it looks
		i = 0;
		addCycle(CycleButton.<MankeListConfig.Corner>builder(
						val -> Component.translatable("option.mankelist.corner." + val.name().toLowerCase(Locale.ROOT)))
				.withValues(MankeListConfig.Corner.values())
				.withInitialValue(cfg.corner)
				.create(rightX, y0 + step * i++, colW, 20, Component.translatable("option.mankelist.corner"),
						(btn, val) -> cfg.corner = val),
				List.of(MankeListConfig.Corner.values()), val -> cfg.corner = val);

		List<Double> scales = List.of(0.5, 0.75, 1.0, 1.25, 1.5);
		addCycle(CycleButton.<Double>builder(val -> Component.literal((int) (val * 100) + "%"))
				.withValues(scales)
				.withInitialValue(cfg.scale)
				.create(rightX, y0 + step * i++, colW, 20, Component.translatable("option.mankelist.scale"),
						(btn, val) -> cfg.scale = val),
				scales, val -> cfg.scale = val);

		// Max lines: free numeric field (1-999) instead of fixed steps
		int rowY = y0 + step * i++;
		maxLinesLabelX = rightX;
		maxLinesLabelY = rowY + 6;
		EditBox maxLinesBox = new EditBox(this.font, rightX + colW - 48, rowY, 48, 20,
				Component.translatable("option.mankelist.max_lines"));
		maxLinesBox.setFilter(s -> s.matches("\\d{0,3}"));
		maxLinesBox.setValue(String.valueOf(cfg.maxLines));
		maxLinesBox.setResponder(s -> {
			if (!s.isEmpty()) {
				cfg.maxLines = Math.clamp(Integer.parseInt(s), 1, 999);
			}
		});
		addRenderableWidget(maxLinesBox);

		addToggle(CycleButton.onOffBuilder(cfg.background)
				.create(rightX, y0 + step * i++, colW, 20, Component.translatable("option.mankelist.background"),
						(btn, val) -> cfg.background = val), val -> cfg.background = val);

		addCycle(CycleButton.<MankeListConfig.SortMode>builder(
						val -> Component.translatable("option.mankelist.sort_mode." + val.name().toLowerCase(Locale.ROOT)))
				.withValues(MankeListConfig.SortMode.values())
				.withInitialValue(cfg.sortMode)
				.create(rightX, y0 + step * i++, colW, 20, Component.translatable("option.mankelist.sort_mode"),
						(btn, val) -> cfg.sortMode = val),
				List.of(MankeListConfig.SortMode.values()), val -> cfg.sortMode = val);

		addRenderableWidget(Button.builder(Component.translatable("option.mankelist.arrange"),
						b -> this.minecraft.setScreen(new ArrangeScreen(this)))
				.bounds(rightX, y0 + step * i, colW, 20).build());

		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 1) {
			for (Map.Entry<CycleButton<?>, Runnable> e : backCycles.entrySet()) {
				CycleButton<?> btn = e.getKey();
				if (btn.active && btn.visible && btn.isMouseOver(mouseX, mouseY)) {
					btn.playDownSound(this.minecraft.getSoundManager());
					e.getValue().run();
					return true;
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		g.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
		g.drawString(this.font, Component.translatable("option.mankelist.max_lines"),
				maxLinesLabelX, maxLinesLabelY, 0xFFFFFF, true);
	}

	@Override
	public void onClose() {
		cfg.save();
		if (this.minecraft != null) {
			this.minecraft.setScreen(parent);
		}
	}
}
