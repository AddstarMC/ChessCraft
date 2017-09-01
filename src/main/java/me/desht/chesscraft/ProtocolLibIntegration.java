package me.desht.chesscraft;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ConnectionSide;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import me.desht.chesscraft.chess.BoardViewManager;
import org.bukkit.Location;

import java.util.HashSet;
import java.util.Set;

public class ProtocolLibIntegration {
	private static double entityVolume = 1.0;

	public static void registerPacketHandler(ChessCraft plugin) {
        PacketAdapter.AdapterParameteters params = new PacketAdapter.AdapterParameteters();
        params.connectionSide(ConnectionSide.SERVER_SIDE);
        params.plugin(plugin);
        params.listenerPriority(ListenerPriority.NORMAL);
        Set<PacketType> types = new HashSet<>();
        types.add(PacketType.Play.Server.NAMED_SOUND_EFFECT);
        params.types(types);
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(params) {
            @Override
			public void onPacketSending(PacketEvent event) {
                if (event.getPacketType().getCurrentId() == PacketType.Play.Server.NAMED_SOUND_EFFECT.getCurrentId()) {// 0x3E
                    if (entityVolume != 1.0) {
						// modify volume of all mob noises if they're on a chess board
						String soundName = event.getPacket().getStrings().read(0);
						int x = event.getPacket().getIntegers().read(0) >> 3;
						int y = event.getPacket().getIntegers().read(1) >> 3;
						int z = event.getPacket().getIntegers().read(2) >> 3;
						Location loc = new Location(event.getPlayer().getWorld(), x, y, z);
						if (BoardViewManager.getManager().partOfChessBoard(loc) != null) {
							if (soundName.startsWith("mob.") || soundName.equals("fire.fire")) {
//							Debugger.getInstance().debug(2, "cancel sound " + soundName + " -> " + event.getPlayer().getName() + " @ " + loc);
								if (entityVolume == 0.0) {
									event.setCancelled(true);
								} else {
									event.getPacket().getFloat().write(0, (float) entityVolume);
								}
							}
						}
					}
				}
			}
		});
	}

	public static void setEntityVolume(double volume) {
		entityVolume = Math.max(0.0, Math.min(volume, 4.0));
	}
}
