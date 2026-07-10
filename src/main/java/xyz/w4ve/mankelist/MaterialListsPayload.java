package xyz.w4ve.mankelist;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C packet v2: ALL active lists (0.5 multi-list), and every entry also
 * carries claim/stocked. A 0.4.x client only registers the v1 channel and the
 * server sends it the first list there; a 0.5 client registers both and the
 * server prefers v2.
 */
public record MaterialListsPayload(List<MaterialListPayload> lists) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<MaterialListsPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MankeList.MOD_ID, "material_list_v2"));

	public static final StreamCodec<FriendlyByteBuf, MaterialListsPayload> CODEC =
			CustomPacketPayload.codec(MaterialListsPayload::write, MaterialListsPayload::new);

	private MaterialListsPayload(FriendlyByteBuf buf) {
		this(readLists(buf));
	}

	private static List<MaterialListPayload> readLists(FriendlyByteBuf buf) {
		int nLists = buf.readVarInt();
		List<MaterialListPayload> lists = new ArrayList<>(nLists);
		for (int i = 0; i < nLists; i++) {
			String name = buf.readUtf();
			int nEntries = buf.readVarInt();
			List<MaterialListPayload.Entry> entries = new ArrayList<>(nEntries);
			for (int j = 0; j < nEntries; j++) {
				entries.add(new MaterialListPayload.Entry(
						buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readBoolean(),
						buf.readUtf(), buf.readVarInt(), buf.readVarInt()));
			}
			lists.add(new MaterialListPayload(name, entries));
		}
		return lists;
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.lists.size());
		for (MaterialListPayload list : this.lists) {
			buf.writeUtf(list.listName());
			buf.writeVarInt(list.entries().size());
			for (MaterialListPayload.Entry e : list.entries()) {
				buf.writeUtf(e.id());
				buf.writeUtf(e.display());
				buf.writeVarInt(e.count());
				buf.writeBoolean(e.done());
				buf.writeUtf(e.claim());
				buf.writeVarInt(e.stocked());
				buf.writeVarInt(e.gathered());
			}
		}
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
