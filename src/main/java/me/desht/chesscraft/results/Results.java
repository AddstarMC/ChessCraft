package me.desht.chesscraft.results;

import me.desht.chesscraft.ChessCraft;
import me.desht.chesscraft.chess.ChessGame;
import me.desht.chesscraft.enums.GameResult;
import me.desht.chesscraft.enums.GameState;
import me.desht.chesscraft.exceptions.ChessException;
import me.desht.dhutils.Debugger;
import me.desht.dhutils.LogUtils;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class Results {
	private static Results results = null;	// this is a singleton class

	private final ResultsDB db;
	private final List<ResultEntry> entries = Collections.synchronizedList(new ArrayList<ResultEntry>());
    private final Map<String, ResultViewBase> views = new ConcurrentHashMap<>();

	private boolean databaseLoaded = false;

    private final BlockingQueue<DatabaseSavable> pendingUpdates = new LinkedBlockingQueue<>();

	/**
	 * Create the singleton results handler - only called from getResultsHandler once
	 * @throws SQLException
	 * @throws ClassNotFoundException
	 */
	private Results() throws ClassNotFoundException, SQLException {
		db = new ResultsDB();
		registerView("ladder", new Ladder(this));
		registerView("league", new League(this));
		loadEntriesFromDatabase();
		Thread updater = new Thread(new DatabaseUpdaterTask(this));
		updater.start();
	}

	/**
	 * Register a new view type
	 *
	 * @param viewName	Name of the view
	 * @param view		Object to handle the view (must subclass ResultViewBase)
	 */
	private void registerView(String viewName, ResultViewBase view) {
		views.put(viewName, view);
	}

	/**
	 * Get the singleton results handler object
	 *
	 * @return	The results handler
	 */
	public synchronized static Results getResultsHandler() {
		if (results == null) {
			try {
				results = new Results();
			} catch (Exception e) {
				LogUtils.warning(e.getMessage());
			}
		}
		return results;
	}

	@SuppressWarnings("CloneDoesntCallSuperClone")
	@Override
	public Object clone() throws CloneNotSupportedException {
		throw new CloneNotSupportedException();
	}

	/**
	 * Shut down the results handler, ensuring the DB is cleanly disconnected etc.
	 * Call this when the plugin is disabled.
	 */
	public static synchronized void shutdown() {
		if (results != null) {
			results.queueDatabaseUpdate(new EndMarker());
			if (results.db != null) {
				results.db.shutdown();
			}
			results = null;
		}
	}

	/**
	 * Get a results view of the given type (e.g. ladder, league...)
	 *
	 * @param viewName	Name of the view type
	 * @return			A view object
	 * @throws ChessException	if there is no such view type
	 */
	public ResultViewBase getView(String viewName) throws ChessException {
		if (!views.containsKey(viewName)) {
			throw new ChessException("No such results type: " + viewName);
		}
		return views.get(viewName);
	}

	/**
	 * Return a list of all results
	 *
	 * @return	A list of ResultEntry objects
	 */
	public List<ResultEntry> getEntries() {
		return entries;
	}

	/**
	 * Get the database connection object
	 *
	 * @return	A SQL Connection object
	 */
	Connection getDBConnection() {
		try {
			if (db.getConnection() != null && db.getActiveDriver() != ResultsDB.SupportedDrivers.SQLITE && !db.getConnection().isValid(5)) {
				// stale handler
				LogUtils.info("DB connection no longer valid - attempting reconnection");
				db.makeDBConnection();
				LogUtils.info("Reconnection successful");
			}
		} catch (Exception e) {
			e.printStackTrace();
			LogUtils.severe("No database connection available - results will not be saved.");
		}
		return db.getConnection();
	}

	/**
	 * @return the databaseLoaded
	 */
	boolean isDatabaseLoaded() {
		return databaseLoaded;
	}

	/**
	 * Log the result for a game
	 *
	 * @param game	The game that has just finished
	 * @param rt	The outcome of the game
	 */
	public void logResult(ChessGame game, GameResult rt) {
		if (!databaseLoaded) {
			return;
		}
		if (game.getState() != GameState.FINISHED) {
			return;
		}
		if (rt == GameResult.Abandoned) {
			// Abandoned games don't really have a result - we can't count it as a draw
			// since that would hurt higher-ranked players on the ladder.
			return;
		}

		final ResultEntry re = new ResultEntry(game, rt);
		entries.add(re);
		for (ResultViewBase view : views.values()) {
			view.addResult(re);
		}

		queueDatabaseUpdate(re);
	}

	/**
	 * Asynchronously load in the result data from database.  Called at startup; results data
	 * will not be available until this has finished.
	 */
	private void loadEntriesFromDatabase() {
		Bukkit.getScheduler().runTaskAsynchronously(ChessCraft.getInstance(), new Runnable() {
			@Override
			public void run() {
				try {
					entries.clear();
					Connection conn = getDBConnection();
					if (conn != null) {
						Statement stmt = conn.createStatement();
						ResultSet rs = stmt.executeQuery("SELECT * FROM " + getTableName("results"));
						while (rs.next()) {
							ResultEntry e = new ResultEntry(rs);
							entries.add(e);
						}
						rebuildViews();
						Debugger.getInstance().debug("Results data loaded from database");
						databaseLoaded = true;
					}
				} catch (SQLException e) {
					LogUtils.warning("SQL query failed: " + e.getMessage());
				}
			}
		});
	}

	/**
	 * Generate some random test data and put it in the results table.  This is just
	 * for testing purposes.
	 */
	public void addTestData() {
		final int N_PLAYERS = 10;
		String[] pgnResults = { "1-0", "0-1", "1/2-1/2" };

		try {
			Connection conn = getDBConnection();
			if (conn == null) {
				return;
			}
			conn.setAutoCommit(false);
			Statement clear = conn.createStatement();
			clear.executeUpdate("DELETE FROM " + getTableName("results") + " WHERE playerWhite LIKE 'testplayer%' OR playerBlack LIKE 'testplayer%'");
			Random rnd = new Random();
			for (int i = 0; i < N_PLAYERS; i++) {
				for (int j = 0; j < N_PLAYERS; j++) {
					if (i == j) {
						continue;
					}
					String plw = "testplayer" + i;
					String plb = "testplayer" + j;
					String gn = "testgame-" + i + "-" + j;
					long start = System.currentTimeMillis() - 5000;
					long end = System.currentTimeMillis() - 4000;
					String pgnRes = pgnResults[rnd.nextInt(pgnResults.length)];
					GameResult rt;
					if (pgnRes.equals("1-0") || pgnRes.equals("0-1")) {
						rt = GameResult.Checkmate;
					} else {
						rt = GameResult.DrawAgreed;
					}
					ResultEntry re = new ResultEntry(plw, plb, gn, start, end, pgnRes, rt);
					entries.add(re);
					re.saveToDatabase(conn);
				}
			}
			conn.setAutoCommit(true);
			rebuildViews();
			LogUtils.info("test data added & committed");
		} catch (SQLException e) {
			LogUtils.warning("can't put test data into DB: " + e.getMessage());
		}
	}

	/**
	 * Force a rebuild of all registered result views.
	 */
	public void rebuildViews() {
		for (ResultViewBase view : views.values()) {
			view.rebuild();
		}
	}

	void queueDatabaseUpdate(DatabaseSavable update) {
		pendingUpdates.add(update);
	}

	DatabaseSavable pollDatabaseUpdate() throws InterruptedException {
		return pendingUpdates.take();
	}

	String getTableName(String base) {
		return ChessCraft.getInstance().getConfig().getString("database.table_prefix") + base;
	}

	static class EndMarker implements DatabaseSavable {
		@Override
		public void saveToDatabase(Connection conn) throws SQLException {
			// no-op
		}
	}
}
