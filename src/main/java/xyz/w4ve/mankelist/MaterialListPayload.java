package xyz.w4ve.mankelist;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Legacy (v1) S2C packet carrying ONE material list. Sent on join and whenever
 * the state file changes. A client without the mod never registers the channel
 * and receives nothing (same scheme Servux uses). Kept so 0.4.x clients still
 * work against newer servers; new clients use {@link MaterialListsPayload}.
 *
 * @param listName name of the active list ("" = no active list)
 * @param entries  materials in the original state-file order
 */
public record MaterialListPayload(String listName, List<Entry> entries) implements CustomPacketPayload {

	/**
	 * One material of the list.
	 *
	 * @param id      block/item id, e.g. "minecraft:black_concrete"
	 * @param display readable name stored in the list file (fallback when the id does not resolve)
	 * @param count   total amount the schematic needs
	 * @param done    collaborative all-or-nothing check
	 * @param claim    who is farming it ("" = nobody); only travels on v2
	 * @param stocked  units already delivered to a stocking area; only travels on v2
	 * @param gathered units the farmer reported having so far (/ml have); only travels on v2
	 */
	public record Entry(String id, String display, int count, boolean done, String claim, int stocked, int gathered) {
		public Entry(String id, String display, int count, boolean done) {
			this(id, display, count, done, "", 0, 0);
		}
	}

	public static final CustomPacketPayload.Type<MaterialListPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MankeList.MOD_ID, "material_list"));

	public static final StreamCodec<FriendlyByteBuf, MaterialListPayload> CODEC =
			CustomPacketPayload.codec(MaterialListPayload::write, MaterialListPayload::new);

	private MaterialListPayload(FriendlyByteBuf buf) {
		this(buf.readUtf(), readEntries(buf));
	}

	private static List<Entry> readEntries(FriendlyByteBuf buf) {
		int size = buf.readVarInt();
		List<Entry> list = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			list.add(new Entry(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readBoolean()));
		}
		return list;
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(this.listName);
		buf.writeVarInt(this.entries.size());
		for (Entry e : this.entries) {
			buf.writeUtf(e.id());
			buf.writeUtf(e.display());
			buf.writeVarInt(e.count());
			buf.writeBoolean(e.done());
		}
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
