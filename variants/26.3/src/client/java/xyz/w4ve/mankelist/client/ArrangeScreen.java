package xyz.w4ve.mankelist.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import xyz.w4ve.mankelist.MaterialListPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Custom HUD order: click a material to grab it, click another row to drop it
 * into that slot (switching pages or filtering in between is fine). [Top]
 * sends it to the first slot. The order is saved and CUSTOM kicks in.
 */
public class ArrangeScreen extends Screen {
	private static final int PER_PAGE = 8;
	private static final int ROW_H = 22;
	private static final int W = 260;

	private final Screen parent;
	private final MankeListConfig cfg = MankeListConfig.get();
	private final List<String> order = new ArrayList<>();
	private final Map<String, String> labels = new LinkedHashMap<>();
	private String grabbed;
	private String filter = "";
	private EditBox search;
	private int page = 0;

	public ArrangeScreen(Screen parent) {
		super(Component.translatable("screen.mankelist.arrange"));
		this.parent = parent;
		MaterialListPayload list = ClientListState.getList();
		if (list != null) {
			for (MaterialListPayload.Entry e : list.entries()) {
				labels.put(e.id(), e.display());
			}
			for (String id : cfg.customOrder) {
				if (labels.containsKey(id)) {
					order.add(id);
				}
			}
			for (String id : labels.keySet()) {
				if (!order.contains(id)) {
					order.add(id);
				}
			}
		}
	}

	/** Ids visible under the current filter, in the current order. */
	private List<String> view() {
		if (filter.isEmpty()) {
			return order;
		}
		String f = filter.toLowerCase(Locale.ROOT);
		List<String> out = new ArrayList<>();
		for (String id : order) {
			if (id.toLowerCase(Locale.ROOT).contains(f)
					|| labels.getOrDefault(id, "").toLowerCase(Locale.ROOT).contains(f)) {
				out.add(id);
			}
		}
		return out;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - W / 2;
		if (search == null) {
			search = new EditBox(this.font, x, 30, W, 18, Component.translatable("gui.mankelist.search"));
			search.setHint(Component.translatable("gui.mankelist.search"));
			search.setResponder(t -> {
				filter = t;
				page = 0;
				rebuildWidgets();
			});
		}
		search.setX(x);
		search.setY(30);
		addRenderableWidget(search);

		List<String> view = view();
		int y0 = 54;
		int pages = Math.max(1, (view.size() + PER_PAGE - 1) / PER_PAGE);
		this.page = Math.max(0, Math.min(this.page, pages - 1));

		for (int r = 0; r < PER_PAGE; r++) {
			final int idx = page * PER_PAGE + r;
			if (idx >= view.size()) {
				break;
			}
			final String id = view.get(idx);
			int y = y0 + r * ROW_H;
			String name = labels.getOrDefault(id, id);
			String label = (order.indexOf(id) + 1) + ". " + name;
			if (id.equals(grabbed)) {
				label = "» " + name + " «";
			}
			addRenderableWidget(Button.builder(Component.literal(label), b -> rowClicked(id))
					.bounds(x, y, W - 34, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Top"), b -> {
				order.remove(id);
				order.add(0, id);
				grabbed = null;
				rebuildWidgets();
			}).bounds(x + W - 30, y, 30, 20).build()).active = order.indexOf(id) > 0;
		}

		int navY = y0 + PER_PAGE * ROW_H + 4;
		if (pages > 1) {
			addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
				this.page--;
				this.rebuildWidgets();
			}).bounds(x, navY, 30, 20).build()).active = page > 0;
			addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
				this.page++;
				this.rebuildWidgets();
			}).bounds(x + 34, navY, 30, 20).build()).active = page < pages - 1;
		}
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
	}

	/** Row click: grab, cancel, or drop into the target's slot. */
	private void rowClicked(String id) {
		if (grabbed == null) {
			grabbed = id;
		} else if (grabbed.equals(id)) {
			grabbed = null;
		} else {
			int from = order.indexOf(grabbed);
			int to = order.indexOf(id);
			if (from >= 0 && to >= 0) {
				order.remove(from);
				order.add(to, grabbed);
			}
			grabbed = null;
		}
		rebuildWidgets();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
		if (order.isEmpty()) {
			g.centeredText(this.font, Component.translatable("msg.mankelist.no_list"),
					this.width / 2, 60, 0xFFAAAAAA);
			return;
		}
		List<String> view = view();
		int pages = Math.max(1, (view.size() + PER_PAGE - 1) / PER_PAGE);
		if (pages > 1) {
			int navY = 54 + PER_PAGE * ROW_H + 4;
			g.text(this.font, (page + 1) + "/" + pages,
					this.width / 2 - W / 2 + 70, navY + 6, 0xFFAAAAAA, false);
		}
		Component hint = grabbed != null
				? Component.translatable("msg.mankelist.drop_hint", labels.getOrDefault(grabbed, grabbed))
				: Component.translatable("msg.mankelist.grab_hint");
		g.centeredText(this.font, hint, this.width / 2, this.height - 42, 0xFFAAAAAA);
	}

	@Override
	public void onClose() {
		if (!order.isEmpty()) {
			cfg.customOrder = new ArrayList<>(order);
			cfg.sortMode = MankeListConfig.SortMode.CUSTOM;
		}
		cfg.save();
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(parent);
		}
	}
}
