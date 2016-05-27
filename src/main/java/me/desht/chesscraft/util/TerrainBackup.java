package me.desht.chesscraft.util;

import com.sk89q.worldedit.util.io.file.FilenameException;
import me.desht.chesscraft.ChessCraft;
import me.desht.chesscraft.DirectoryStructure;
import me.desht.chesscraft.chess.BoardView;
import me.desht.dhutils.LogUtils;
import me.desht.dhutils.TerrainManager;
import me.desht.dhutils.cuboid.Cuboid;
import org.bukkit.Location;

import java.io.File;

public class TerrainBackup {

	public static boolean save(BoardView view) {
		boolean saved = false;
		try {
			TerrainManager tm = new TerrainManager(ChessCraft.getInstance().getWorldEdit(), view.getA1Square().getWorld());
			Cuboid c = view.getOuterBounds();
			Location l1 = c.getLowerNE();
			Location l2 = c.getUpperSW();

			File schematicFile = new File(DirectoryStructure.getSchematicsDirectory(),view.getName());
			try{tm.saveTerrain(schematicFile, l1, l2);}catch (FilenameException e){
				e.getMessage();
			}
			saved = true;
		} catch (Exception e) {
			LogUtils.warning(e.getMessage());
		}
		return saved;
	}

	public static boolean reload(BoardView view) {
		boolean restored = false;
		try {
			TerrainManager tm = new TerrainManager(ChessCraft.getInstance().getWorldEdit(), view.getA1Square().getWorld());
			tm.loadSchematic(new File(DirectoryStructure.getSchematicsDirectory(), view.getName()));
			restored = true;
		} catch (Exception e) {
			LogUtils.warning(e.getMessage());
		}
		return restored;
	}
}
