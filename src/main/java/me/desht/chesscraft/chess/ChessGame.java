package me.desht.chesscraft.chess;

import chesspresso.Chess;
import chesspresso.game.Game;
import chesspresso.move.IllegalMoveException;
import chesspresso.move.Move;
import chesspresso.pgn.PGN;
import chesspresso.pgn.PGNWriter;
import chesspresso.position.Position;
import com.google.common.collect.Lists;
import me.desht.chesscraft.*;
import me.desht.chesscraft.chess.ai.AIFactory;
import me.desht.chesscraft.chess.ai.ChessAI;
import me.desht.chesscraft.chess.player.AIChessPlayer;
import me.desht.chesscraft.chess.player.ChessPlayer;
import me.desht.chesscraft.chess.player.HumanChessPlayer;
import me.desht.chesscraft.enums.GameResult;
import me.desht.chesscraft.enums.GameState;
import me.desht.chesscraft.event.ChessGameStateChangedEvent;
import me.desht.chesscraft.exceptions.ChessException;
import me.desht.chesscraft.results.Results;
import me.desht.chesscraft.util.ChessUtils;
import me.desht.chesscraft.util.EconomyUtil;
import me.desht.dhutils.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.Map.Entry;

public class ChessGame implements ConfigurationSerializable, ChessPersistable {
	private final String name;
	private final Game cpGame;
	private final long created;

	private final ChessPlayer[] players = new ChessPlayer[2];
    private final List<Short> history = new ArrayList<>();

	private UUID invited;
	private GameState state;
	private long started, finished, lastMoved, lastOpenInvite;
	private int result;
	private double stake;
	private boolean openInvite;
    private final List<GameListener> listeners = Lists.newArrayList();
    private final TwoPlayerClock clock;

    /**
	 * Create a new Chess game.
	 *
	 * @param name    Name of the game
	 * @param creator    Name of the player who is setting up the game
	 * @param tcSpec    The time control to use for the game
	 * @param colour    Colour the game creator will play (Chess.WHITE or Chess.BLACK)
	 * @throws ChessException	If the game can't be created for any reason
	 */
	public ChessGame(String name, Player creator, String tcSpec, int colour) {
		this.name = name;
		players[Chess.WHITE] = players[Chess.BLACK] = null;
		if (creator != null) {
			players[colour] = createPlayer(creator.getUniqueId().toString(), creator.getDisplayName(), colour);
		}
		state = GameState.SETTING_UP;
		invited = null;
		openInvite = false;
		created = System.currentTimeMillis();
		started = finished = lastOpenInvite = 0L;
		result = Chess.RES_NOT_FINISHED;
        stake = 0.0;
        clock = new TwoPlayerClock(tcSpec);

		cpGame = setupChesspressoGame();

//		view.setGame(this);
	}

	/**
	 * Constructor: Restoring a saved Chess game.
	 *
	 * @param conf	Saved game data
	 * @throws ChessException	If the game can't be created for any reason
	 * @throws IllegalMoveException	If the game data contains an illegal Chess move
	 */
	public ChessGame(ConfigurationSection conf) throws IllegalMoveException {
		name = conf.getString("name");
		String dispW = conf.getString("playerWhiteDisp", "?white?");
		String dispB = conf.getString("playerBlackDisp", "?black?");
		players[Chess.WHITE] = createPlayer(conf.getString("playerWhite"), dispW, Chess.WHITE);
		players[Chess.BLACK] = createPlayer(conf.getString("playerBlack"), dispB, Chess.BLACK);
		state = GameState.valueOf(conf.getString("state"));
		String inv = conf.getString("invited", "");
		invited = inv.isEmpty() ? null : UUID.fromString(inv);
		openInvite = conf.getBoolean("openInvite", false);
		List<Integer> hTmp = conf.getIntegerList("moves");
		for (int m : hTmp) {
			history.add((short) m);
		}
        this.clock = (TwoPlayerClock) conf.get("clock");
		created = conf.getLong("created", System.currentTimeMillis());
		started = conf.getLong("started");
		finished = conf.getLong("finished", state == GameState.FINISHED ? System.currentTimeMillis() : 0);
		lastOpenInvite = 0L;
		lastMoved = conf.getLong("lastMoved", System.currentTimeMillis());
		result = conf.getInt("result");
		if (hasPlayer(Chess.WHITE)) getPlayer(Chess.WHITE).setPromotionPiece(conf.getInt("promotionWhite"));
		if (hasPlayer(Chess.BLACK)) getPlayer(Chess.BLACK).setPromotionPiece(conf.getInt("promotionBlack"));
		stake = conf.getDouble("stake", 0.0);

		cpGame = setupChesspressoGame();

		replayMoves();

        if (getState() == GameState.RUNNING) {
            clock.setActivePlayer(getPosition().getToPlay());
            getPlayerToMove().promptForNextMove();
        }
	}

	private ChessPlayer createPlayer(String playerId, String displayName, int colour) {
		if (playerId == null) {
			String aiName = AIFactory.getInstance().getFreeAIName();
			return new AIChessPlayer(aiName, this, colour);
		} else if (ChessAI.isAIPlayer(playerId)) {
			return new AIChessPlayer(playerId, this, colour);
		} else if (playerId.isEmpty()) {
			// no player for this slot yet
			return null;
		} else {
			return new HumanChessPlayer(playerId, displayName, this, colour);
		}
	}

	public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();

		map.put("name", name);
		map.put("playerWhite", getPlayerId(Chess.WHITE));
		map.put("playerBlack", getPlayerId(Chess.BLACK));
		map.put("playerWhiteDisp", getPlayerDisplayName(Chess.WHITE));
		map.put("playerBlackDisp", getPlayerDisplayName(Chess.BLACK));
		map.put("state", state.toString());
		map.put("invited", invited);
		map.put("openInvite", openInvite);
		map.put("moves", history);
		map.put("created", created);
		map.put("started", started);
		map.put("finished", finished);
		map.put("lastMoved", lastMoved);
		map.put("result", result);
		map.put("promotionWhite", getPromotionPiece(Chess.WHITE));
		map.put("promotionBlack", getPromotionPiece(Chess.BLACK));
        map.put("clock", clock);
		map.put("stake", stake);

		return map;
	}

	public static ChessGame deserialize(Map <String,Object> map) throws IllegalMoveException {
		Configuration conf = new MemoryConfiguration();
		for (Entry<String, Object> e : map.entrySet()) {
			conf.set(e.getKey(), e.getValue());
		}
		return new ChessGame(conf);
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public File getSaveDirectory() {
		return DirectoryStructure.getGamesPersistDirectory();
	}

	/**
	 * Replay the move history to restore the saved board position.  We do this
	 * instead of just saving the position so that the Chesspresso ChessGame model
	 * includes a history of the moves, suitable for creating a PGN file.
	 *
	 * @throws IllegalMoveException
	 */
	private void replayMoves() throws IllegalMoveException {
		// load moves into the Chesspresso model
		for (short move : history) {
			getPosition().doMove(move);
		}

		// load moves into the player's (possibly AI) game model
		if (players[Chess.WHITE] != null) players[Chess.WHITE].replayMoves();
		if (players[Chess.BLACK] != null) players[Chess.BLACK].replayMoves();
	}

	private Game setupChesspressoGame() {
		Game cpg = new Game();

		String site = Bukkit.getServerName() + Messages.getString("Game.sitePGN");

		// seven tag roster
		cpg.setTag(PGN.TAG_EVENT, getName());
		cpg.setTag(PGN.TAG_SITE, site);
		cpg.setTag(PGN.TAG_DATE, ChessUtils.dateToPGNDate(created));
		cpg.setTag(PGN.TAG_ROUND, "?");
		cpg.setTag(PGN.TAG_WHITE, getPlayerId(Chess.WHITE));
		cpg.setTag(PGN.TAG_BLACK, getPlayerId(Chess.BLACK));
		cpg.setTag(PGN.TAG_RESULT, getPGNResult());

		// extra tags
		cpg.setTag(PGN.TAG_FEN, Position.createInitialPosition().getFEN());
		return cpg;
	}

	public void save() {
		ChessCraft.getInstance().getPersistenceHandler().savePersistable("game", this);
	}

	public Game getChesspressoGame() {
		return cpGame;
	}

	public Position getPosition() {
		return cpGame.getPosition();
	}

	/**
	 * Return the player object for the given colour (Chess.WHITE or Chess.BLACK).
	 *
	 * @param colour the colour, Chess.WHITE or Chess.BLACK
	 * @return the chess player object
	 */
	public ChessPlayer getPlayer(int colour) {
		return players[colour];
	}

	public boolean hasPlayer(int colour) {
		return players[colour] != null;
	}

	public ChessPlayer getPlayer(String playerId) {
		if (playerId.equals(getPlayerId(Chess.WHITE))) {
			return getPlayer(Chess.WHITE);
		} else if (playerId.equals(getPlayerId(Chess.BLACK))) {
			return getPlayer(Chess.BLACK);
		} else {
			return null;
		}
	}

	public String getPlayerId(int colour) {
		return players[colour] != null ? players[colour].getId() : "";
	}

	public String getPlayerDisplayName(int colour) {
		return players[colour] != null ? players[colour].getDisplayName() : "";
	}

	public UUID getInvitedId() {
		return invited;
	}

	public GameState getState() {
		return state;
	}

	public void setState(GameState state) {
        if (state == GameState.RUNNING) {
            ChessValidate.isTrue(this.state == GameState.SETTING_UP, "invalid state transition " + this.state + "->" + state);
            started = lastMoved = System.currentTimeMillis();
            clock.setActivePlayer(Chess.WHITE);
        } else if (state == GameState.FINISHED) {
            ChessValidate.isTrue(this.state == GameState.RUNNING, "invalid state transition " + this.state + "->" + state);
			finished = System.currentTimeMillis();
            clock.stop();
		}
        this.state = state;
		Bukkit.getPluginManager().callEvent(new ChessGameStateChangedEvent(this));
        for (GameListener l : listeners) {
            l.gameStateChanged(state);
        }
	}

	public long getStarted() {
		return started;
	}

	public long getCreated() {
		return created;
	}

	public long getFinished() {
		return finished;
	}

	public List<Short> getHistory() {
		return history;
	}

	public double getStake() {
		return stake;
	}

	public int getPromotionPiece(int colour) {
		return hasPlayer(colour) ? getPlayer(colour).getPromotionPiece() : Chess.QUEEN;
	}

	public boolean isOpenInvite() {
		return openInvite;
	}

	/**
	 * A player is trying to adjust the stake for this game.
	 *
	 * @param player	player setting the stake
	 * @param newStake		The stake being set
	 * @throws ChessException	if the stake is out of range or not affordable or the game isn't in setup phase
	 */
	public void setStake(Player player, double newStake) {
		if (EconomyUtil.enabled()) {

            ensureGameState(GameState.SETTING_UP);
            ensurePlayerInGame(player.getUniqueId().toString());

            for (GameListener l : listeners) {
                ChessValidate.isTrue(l.tryStakeChange(newStake), Messages.getString("Game.stakeLocked"));
            }

            ChessValidate.isTrue(newStake >= 0.0, Messages.getString("Game.noNegativeStakes"));
            ChessValidate.isTrue(EconomyUtil.has(player, newStake), Messages.getString("ChessCommandExecutor.cantAffordStake"));
            double max = ChessCraft.getInstance().getConfig().getDouble("stake.max");
            ChessValidate.isTrue(max < 0.0 || newStake <= max, Messages.getString("Game.stakeTooHigh", max));
            ChessValidate.isFalse(isFull(), Messages.getString("Game.stakeCantBeChanged"));

            this.stake = newStake;

            for (GameListener l : listeners) {
                l.stakeChanged(newStake);
            }
        }
	}

	/**
	 * Adjust the game's stake by the given amount.
	 *
	 * @param player	player adjusting the stake
	 * @param adjustment	amount to adjust by (may be negative)
	 * @throws ChessException	if the new stake is out of range or not affordable or the game isn't in setup phase
	 */
	public void adjustStake(Player player, double adjustment) {
		if (!EconomyUtil.enabled()) {
			return;
		}

		double newStake = getStake() + adjustment;
		double max = ChessCraft.getInstance().getConfig().getDouble("stake.max");

		if (max >= 0.0 && newStake > max && adjustment < 0.0) {
			// allow stake to be adjusted down without throwing an exception
			// could happen if global max stake was changed to something lower than
			// a game's current stake setting
			newStake = Math.min(max, EconomyUtil.getBalance(player));
		}
		if (!EconomyUtil.has(player, newStake) && adjustment < 0.0) {
			// similarly for the player's own balance
			newStake = Math.min(max, EconomyUtil.getBalance(player));
		}

		setStake(player, newStake);
	}

    public TimeControl getTimeControl() {
        return new TimeControl(clock.getTimeControl().getSpec());
    }

	/**
	 * Housekeeping task, called periodically by the scheduler.  Update the clocks for the game, and
	 * check for any pending AI moves.
	 */
	public void tick() {
		if (state == GameState.RUNNING) {
            checkForAIActivity();
        }
        checkForAutoDelete();
	}

	public void setTimeControl(String tcSpec) {
		ensureGameState(GameState.SETTING_UP);
        for (GameListener l : listeners) {
            ChessValidate.isTrue(l.tryTimeControlChange(tcSpec), Messages.getString("Game.timeControlLocked"));
        }
        clock.setTimeControl(tcSpec);
        for (GameListener l : listeners) {
            l.timeControlChanged(tcSpec);
        }
	}

	public void swapColours() {
		tick();

		ChessPlayer tmp = players[Chess.WHITE];
		players[Chess.WHITE] = players[Chess.BLACK];
		players[Chess.BLACK] = tmp;

		players[Chess.WHITE].setColour(Chess.WHITE);
		players[Chess.BLACK].setColour(Chess.BLACK);

		players[Chess.WHITE].alert(Messages.getString("Game.nowPlayingWhite"));
		players[Chess.BLACK].alert(Messages.getString("Game.nowPlayingBlack"));
	}

	/**
	 * Check if the game is full, i.e. has a player for both colours.
	 *
	 * @return true if the game is full, false otherwise
	 */
	public boolean isFull() {
		return hasPlayer(Chess.WHITE) && hasPlayer(Chess.BLACK);
	}

	/**
	 * Add the named player (which could be an AI) to the game.
	 *
	 * @param playerId ID of the player to add
	 * @param displayName display name of the player to add
	 * @return the colour the player was added as
	 * @throws ChessException if the player could not be added for any reason
	 */
	public int addPlayer(String playerId, String displayName) {
		ensureGameState(GameState.SETTING_UP);

		ChessValidate.isFalse(isFull(), Messages.getString("Game.gameIsFull"));

		ChessPlayer cp = fillEmptyPlayerSlot(playerId, displayName);

		clearInvitation();
        for (GameListener l : listeners) {
            l.playerAdded(cp);
        }

		if (isFull()) {
			if (ChessCraft.getInstance().getConfig().getBoolean("autostart", true)) {
				start(playerId);
			} else {
				alert(Messages.getString("Game.startPrompt"));
			}
		}

		return cp.getColour();
	}

	/**
	 * Add the given player (human or AI) to the first empty slot (white or black, in order)
	 * found in the game.
	 *
	 * @param playerId id of the player to add
	 * @param displayName display name of the player to add
	 * @return the colour the player was added as
	 * @throws ChessException if the player may not join for any reason
	 */
	private ChessPlayer fillEmptyPlayerSlot(String playerId, String displayName) {
		int colour = hasPlayer(Chess.WHITE) ? Chess.BLACK : Chess.WHITE;

		ChessPlayer chessPlayer = createPlayer(playerId, displayName, colour);
		chessPlayer.validateInvited("Game.notInvited");
		chessPlayer.validateAffordability("Game.cantAffordToJoin");
		players[colour] = chessPlayer;

		int otherColour = Chess.otherPlayer(colour);
		if (hasPlayer(otherColour)) {
			getPlayer(otherColour).alert(Messages.getString("Game.playerJoined", chessPlayer.getDisplayName()));
		}

		return chessPlayer;
	}

	/**
	 * One player has just invited another player to this game.
	 *
	 * @param inviter player doing the inviting
	 * @param inviteeName name of the invited player (could be an AI)
	 * @throws ChessException
	 */
	public void invitePlayer(Player inviter, String inviteeName) {
		inviteSanityCheck(inviter.getUniqueId().toString());

		if (inviteeName == null) {
			inviteOpen(inviter);
			return;
		}

		// Getting player by name is actually OK here - we don't want players to have to type
		// player UUID's just to invite them!
		@SuppressWarnings("deprecation") Player invitee = Bukkit.getServer().getPlayer(inviteeName);
		if (invitee != null) {
			alert(invitee, Messages.getString("Game.youAreInvited", inviter.getDisplayName()));
			if (EconomyUtil.enabled() && getStake() > 0.0) {
				alert(invitee, Messages.getString("Game.gameHasStake", EconomyUtil.formatStakeStr(getStake())));
			}
			alert(invitee, Messages.getString("Game.joinPrompt"));
			if (invited != null) {
				alert(invited, Messages.getString("Game.inviteWithdrawn"));
			}
			invited = invitee.getUniqueId();
			openInvite = false;
			alert(inviter, Messages.getString("Game.inviteSent", invitee.getDisplayName()));
		} else {
			// no human by this name, try to add an AI of the given name
			addPlayer(ChessAI.AI_PREFIX + inviteeName, ChessAI.AI_PREFIX + inviteeName);
		}
	}

	/**
	 * Broadcast an open invitation to the server and allow anyone to join the game.
	 *
	 * @param inviter player creating the open invitation
	 */
	public void inviteOpen(Player inviter) {
		inviteSanityCheck(inviter.getUniqueId().toString());

		long now = System.currentTimeMillis();
		Duration cooldown = new Duration(ChessCraft.getInstance().getConfig().getString("open_invite_cooldown", "3 mins"));
		long remaining = (cooldown.getTotalDuration() - (now - lastOpenInvite)) / 1000;
		if (remaining > 0) {
			throw new ChessException(Messages.getString("Game.inviteCooldown", remaining));
		}

		MiscUtil.broadcastMessage((Messages.getString("Game.openInviteCreated", inviter.getDisplayName())));
		if (EconomyUtil.enabled() && getStake() > 0.0) {
			MiscUtil.broadcastMessage(Messages.getString("Game.gameHasStake", EconomyUtil.formatStakeStr(getStake())));
		}
		MiscUtil.broadcastMessage(Messages.getString("Game.joinPromptGlobal", getName()));
		openInvite = true;
		lastOpenInvite = now;
	}

	private void inviteSanityCheck(String inviterId) {
		ensurePlayerInGame(inviterId);
		ensureGameState(GameState.SETTING_UP);

		if (isFull()) {
			// if one player is an AI, allow the AI to leave
			if (!players[Chess.WHITE].isHuman()) {
				players[Chess.WHITE].cleanup();
				players[Chess.WHITE] = null;
			} else if (!players[Chess.BLACK].isHuman()) {
				players[Chess.BLACK].cleanup();
				players[Chess.BLACK] = null;
			} else {
				throw new ChessException(Messages.getString("Game.gameIsFull"));
			}
		}
	}

	/**
	 * Withdraw any invitation to this game.
	 */
	public void clearInvitation() {
		invited = null;
		openInvite = false;
	}

	/**
	 * Start the game.
	 *
	 * @param playerId	Player who is starting the game
	 * @throws ChessException	if anything goes wrong
	 */
	public void start(String playerId) {
		ensurePlayerInGame(playerId);
		ensureGameState(GameState.SETTING_UP);

		if (!isFull()) {
			// game started with only one player - add an AI player
			fillEmptyPlayerSlot(null, null);
		}

		cpGame.setTag(PGN.TAG_WHITE, getPlayerDisplayName(Chess.WHITE));
		cpGame.setTag(PGN.TAG_BLACK, getPlayerDisplayName(Chess.BLACK));

		if (stake > 0.0 && !getPlayerId(Chess.WHITE).equals(getPlayerId(Chess.BLACK))) {
			// just in case stake.max got adjusted after game creation...
			double max = ChessCraft.getInstance().getConfig().getDouble("stake.max");
			if (max >= 0 && stake > max) {
				stake = max;
			}
			getPlayer(Chess.WHITE).validateAffordability("Game.cantAffordToStart");
			getPlayer(Chess.BLACK).validateAffordability("Game.cantAffordToStart");
            getPlayer(Chess.WHITE).withdrawFunds(stake);
            getPlayer(Chess.BLACK).withdrawFunds(stake);
		}

		clearInvitation();
		setState(GameState.RUNNING);
		getPlayer(Chess.WHITE).promptForFirstMove();

		save();
	}

	/**
	 * The given player is resigning.
	 *
	 * @param colour colour of the resigning player
	 */
	public void resign(int colour) {
		ChessValidate.isTrue(getState() == GameState.RUNNING, Messages.getString("Game.notStarted"));
		ChessPlayer cp = getPlayer(colour);
		ChessValidate.notNull(cp, Messages.getString("Game.notInGame"));
		gameOver(Chess.otherPlayer(cp.getColour()), GameResult.Resigned);
	}

	/**
	 * Player has won by default (other player has exhausted their time control,
	 * or has been offline too long).
	 *
	 * @param colour colour of the winning player
	 */
	public void winByDefault(int colour) {
		ChessValidate.isTrue(getState() == GameState.RUNNING, Messages.getString("Game.notStarted"));
		ChessPlayer cp = getPlayer(colour);
		ChessValidate.notNull(cp, Messages.getString("Game.notInGame"));
		gameOver(cp.getColour(), GameResult.Forfeited);
	}

	/**
	 * The game is a draw.
	 *
	 * @param res	the reason for the draw
	 */
	public void drawn(GameResult res) {
		ensureGameState(GameState.RUNNING);
		gameOver(Chess.NOBODY, res);
	}

	/**
	 * Do a move for player from fromSquare to toSquare.
	 *
	 * @param playerId ID of the player who is moving
	 * @param fromSquare sqi of the square being moved from
	 * @param toSquare sqi of the square being moved to
	 * @throws IllegalMoveException
	 * @throws ChessException
	 */
	public void doMove(String playerId, int fromSquare, int toSquare) throws IllegalMoveException, ChessException {
		ensureGameState(GameState.RUNNING);
		ensurePlayerToMove(playerId);

		if (fromSquare == Chess.NO_SQUARE) {
			return;
		}

		boolean isCapturing = getPosition().getPiece(toSquare) != Chess.NO_PIECE;
		short move = Move.getRegularMove(fromSquare, toSquare, isCapturing);
		short realMove = validateMove(move);

		int prevToMove = getPosition().getToPlay();

		// At this point we know the move is valid, so go ahead and make the necessary changes...
		getPosition().doMove(realMove);	// the board view will be repainted at this point
		lastMoved = System.currentTimeMillis();
		history.add(realMove);

        getPlayer(prevToMove).cancelOffers();

        if (!checkForFinishingPosition()) {
            // the game continues...
            getPlayer(getPosition().getToPlay()).promptForNextMove();
        }

        save();
    }

	/**
	 * Check the current game position to see if the game is over.
	 *
	 * @return	true if game is over, false if not
	 */
	private boolean checkForFinishingPosition() {
		if (getPosition().isMate()) {
			gameOver(Chess.otherPlayer(getPosition().getToPlay()), GameResult.Checkmate);
			return true;
		} else if (getPosition().isStaleMate()) {
			gameOver(Chess.NOBODY, GameResult.Stalemate);
			return true;
		} else if (getPosition().getHalfMoveClock() >= 50) {
			gameOver(Chess.NOBODY, GameResult.FiftyMoveRule);
			return true;
		}
		return false;
	}

	/**
	 * Check if the move is really allowed.  Also account for special cases:
	 * castling, en passant, pawn promotion
	 *
	 * @param move	move to check
	 * @return 	move, if allowed
	 * @throws IllegalMoveException if not allowed
	 */
	private short validateMove(short move) throws IllegalMoveException {
		int sqiFrom = Move.getFromSqi(move);
		int sqiTo = Move.getToSqi(move);
		int toPlay = getPosition().getToPlay();

		if (getPosition().getPiece(sqiFrom) == Chess.KING) {
			// Castling?
			if (sqiFrom == Chess.E1 && sqiTo == Chess.G1 || sqiFrom == Chess.E8 && sqiTo == Chess.G8) {
				move = Move.getShortCastle(toPlay);
			} else if (sqiFrom == Chess.E1 && sqiTo == Chess.C1 || sqiFrom == Chess.E8 && sqiTo == Chess.C8) {
				move = Move.getLongCastle(toPlay);
			}
		} else if (getPosition().getPiece(sqiFrom) == Chess.PAWN
				&& (Chess.sqiToRow(sqiTo) == 7 || Chess.sqiToRow(sqiTo) == 0)) {
			// Promotion?
			boolean capturing = getPosition().getPiece(sqiTo) != Chess.NO_PIECE;
			move = Move.getPawnMove(sqiFrom, sqiTo, capturing, getPromotionPiece(toPlay));
		} else if (getPosition().getPiece(sqiFrom) == Chess.PAWN && getPosition().getPiece(sqiTo) == Chess.NO_PIECE) {
			// En passant?
			int toCol = Chess.sqiToCol(sqiTo);
			int fromCol = Chess.sqiToCol(sqiFrom);
			if ((toCol == fromCol - 1 || toCol == fromCol + 1)
					&& (Chess.sqiToRow(sqiFrom) == 4 && Chess.sqiToRow(sqiTo) == 5 || Chess.sqiToRow(sqiFrom) == 3
					&& Chess.sqiToRow(sqiTo) == 2)) {
				move = Move.getEPMove(sqiFrom, sqiTo);
			}
		}

		for (short aMove : getPosition().getAllMoves()) {
			if (move == aMove) {
				return move;
			}
		}
		throw new IllegalMoveException(move);
	}

	/**
	 * Given a move in SAN format, try to find the Chesspresso Move for that SAN move in the
	 * current position.
	 *
	 * @param moveSAN	the move in SAN format
	 * @return	the Move object, or null if not found (i.e the supplied move was not legal)
	 */
	public Move getMoveFromSAN(String moveSAN) {
		Position tempPos = new Position(getPosition().getFEN());

		for (short aMove : getPosition().getAllMoves()) {
			try {
				tempPos.doMove(aMove);
				Move m = tempPos.getLastMove();
				Debugger.getInstance().debug(2, "getMoveFromSAN: check supplied move: " + moveSAN + " vs possible: " + m.getSAN());
				if (moveSAN.equals(m.getSAN()))
					return m;
				tempPos.undoMove();
			} catch (IllegalMoveException e) {
				// shouldn't happen
				return null;
			}
		}
		return null;
	}

	public String getPGNResult() {
		switch (result) {
		case Chess.RES_NOT_FINISHED:
			return "*";
		case Chess.RES_WHITE_WINS:
			return "1-0";
		case Chess.RES_BLACK_WINS:
			return "0-1";
		case Chess.RES_DRAW:
			return "1/2-1/2";
		default:
			return "*";
		}
	}

	private void gameOver(int winnerColour, GameResult rt) {
		String p1, p2;
		setState(GameState.FINISHED);
		if (winnerColour == Chess.NOBODY) {
			p1 = getPlayer(Chess.WHITE).getDisplayName();
			p2 = getPlayer(Chess.BLACK).getDisplayName();
			result = Chess.RES_DRAW;
		} else {
			getPlayer(winnerColour).playEffect("game_won");
			getPlayer(Chess.otherPlayer(winnerColour)).playEffect("game_lost");
			p1 = getPlayer(winnerColour).getDisplayName();
			p2 = getPlayer(Chess.otherPlayer(winnerColour)).getDisplayName();
			result = winnerColour == Chess.WHITE ? Chess.RES_WHITE_WINS : Chess.RES_BLACK_WINS;
		}
		cpGame.setTag(PGN.TAG_RESULT, getPGNResult());

		String msg = Messages.getString(rt.getMsgKey(), p1, p2);
		if (p1.equals(p2)) {
			getPlayer(Chess.WHITE).alert(msg);
		} else {
			if (ChessCraft.getInstance().getConfig().getBoolean("broadcast_results")) {
				MiscUtil.broadcastMessage(msg);
			} else {
				alert(msg);
			}

			handlePayout();

			Results handler = Results.getResultsHandler();
			if (handler != null) {
				handler.logResult(this, rt);
			}
		}
	}

	private void handlePayout() {
		if (stake <= 0.0 || getPlayerId(Chess.WHITE).equals(getPlayerId(Chess.BLACK)) || getState() == GameState.SETTING_UP) {
			return;
		}

		switch (result) {
		case Chess.RES_WHITE_WINS:
			winner(Chess.WHITE);
			break;
		case Chess.RES_BLACK_WINS:
			winner(Chess.BLACK);
			break;
		case Chess.RES_DRAW:
		case Chess.RES_NOT_FINISHED:
			players[Chess.WHITE].depositFunds(stake);
			players[Chess.BLACK].depositFunds(stake);
			alert(Messages.getString("Game.getStakeBack", EconomyUtil.formatStakeStr(stake)));
			break;
		}

		stake = 0.0;
	}

	private void winner(int winningColour) {
		int losingColour = Chess.otherPlayer(winningColour);
		double winnings = stake * getPlayer(losingColour).getPayoutMultiplier();
		players[winningColour].depositFunds(winnings);
		players[winningColour].alert(Messages.getString("Game.youWon", EconomyUtil.formatStakeStr(winnings)));
		players[losingColour].alert(Messages.getString("Game.lostStake", EconomyUtil.formatStakeStr(stake)));
	}

	/**
	 * Called when a game is deleted; handle any cleanup tasks.
	 */
	void onDeleted(boolean permanent) {
        System.out.println("delete game " + getName() + " perm = " + permanent);
        if (permanent) {
            handlePayout();
            for (GameListener l : listeners) {
                l.gameDeleted();
            }
		}

		if (players[Chess.WHITE] != null) {
			players[Chess.WHITE].cleanup();
		}
		if (players[Chess.BLACK] != null) {
			players[Chess.BLACK].cleanup();
		}
	}

	/**
	 * Get the colour for the given player name.
	 *
	 * @param playerId ID of the player to check
	 * @return one of Chess.WHITE, Chess.BLACK or Chess.NOBODY
	 */
	public int getPlayerColour(String playerId) {
		if (playerId.equalsIgnoreCase(getPlayerId(Chess.WHITE))) {
			return Chess.WHITE;
		} else if (playerId.equalsIgnoreCase(getPlayerId(Chess.BLACK))) {
			return Chess.BLACK;
		} else {
			return Chess.NOBODY;
		}
	}

	public void alert(Player player, String message) {
		MiscUtil.alertMessage(player, Messages.getString("Game.alertPrefix", getName()) + message);
	}

	public void alert(UUID id, String message) {
		Player p = Bukkit.getServer().getPlayer(id);
		if (p != null) {
			alert(p, message);
		}
	}

	public void alert(String message) {
		if (hasPlayer(Chess.WHITE)) {
			getPlayer(Chess.WHITE).alert(message);
		}
		if (hasPlayer(Chess.BLACK) && !getPlayerId(Chess.WHITE).equals(getPlayerId(Chess.BLACK))) {
			getPlayer(Chess.BLACK).alert(message);
		}
	}

	public ChessPlayer getPlayerToMove() {
		return getPlayer(getPosition().getToPlay());
	}

	public boolean isPlayerInGame(String playerId) {
		return (playerId.equals(getPlayerId(Chess.WHITE)) || playerId.equals(getPlayerId(Chess.BLACK)));
	}

	public boolean isPlayerToMove(String playerId) {
		return playerId.equals(getPlayerToMove().getId());
	}

	/**
	 * Write a PGN file for the game.
	 *
	 * @param force if true, overwrite any existing PGN file for this game
	 * @return	the file that has been written
	 */
	public File writePGN(boolean force) {
		File f = makePGNFile();
		if (f.exists() && !force) {
			throw new ChessException(Messages.getString("Game.archiveExists", f.getName()));
		}

		try {
			PrintWriter pw = new PrintWriter(f);
			PGNWriter w = new PGNWriter(pw);
			w.write(cpGame.getModel());
			pw.close();
			return f;
		} catch (FileNotFoundException e) {
			throw new ChessException(Messages.getString("Game.cantWriteArchive", f.getName(), e.getMessage()));
		}
	}

	public String getPGN() {
		StringWriter strw = new StringWriter();
		PGNWriter w = new PGNWriter(strw);
		w.write(cpGame.getModel());
		return strw.toString();
	}

	private File makePGNFile() {
		String baseName = getName() + "_" + ChessUtils.dateToPGNDate(System.currentTimeMillis());

		int n = 1;
		File f;
		do {
			f = new File(DirectoryStructure.getPGNDirectory(), baseName + "_" + n + ".pgn");
			++n;
		} while (f.exists());

		return f;
	}

	/**
	 * Force the board position to the given FEN string.  This should only be used for testing
	 * since the move history for the game is invalidated.
	 *
	 * @param fen the FEN string
	 */
	public void setPositionFEN(String fen) {
		getPosition().set(new Position(fen));
		// manually overriding the position invalidates the move history
		getHistory().clear();
	}

	/**
	 * Check if a game needs to be auto-deleted:
	 * - ChessGame that has not been started after a certain duration
	 * - ChessGame that has been finished for a certain duration
	 * - ChessGame that has been running without any moves made for a certain duration
	 */
    private void checkForAutoDelete() {
		String alertStr = null;

        long now = System.currentTimeMillis();
        if (getState() == GameState.SETTING_UP) {
			long elapsed = now - created;
			Duration timeout = new Duration(ChessCraft.getInstance().getConfig().getString("auto_delete.not_started", "3 mins"));
			if (timeout.getTotalDuration() > 0 && elapsed > timeout.getTotalDuration() && !isFull()) {
				alertStr = Messages.getString("Game.autoDeleteNotStarted", timeout);
			}
		} else if (getState() == GameState.FINISHED) {
			long elapsed = now - finished;
			Duration timeout = new Duration(ChessCraft.getInstance().getConfig().getString("auto_delete.finished", "30 sec"));
			if (timeout.getTotalDuration() > 0 && elapsed > timeout.getTotalDuration()) {
				alertStr = Messages.getString("Game.autoDeleteFinished");
			}
		} else if (getState() == GameState.RUNNING) {
			long elapsed = now - lastMoved;
			Duration timeout = new Duration(ChessCraft.getInstance().getConfig().getString("auto_delete.running", "28 days"));
			if (timeout.getTotalDuration() > 0 && elapsed > timeout.getTotalDuration()) {
				alertStr = Messages.getString("Game.autoDeleteRunning", timeout);
			}
		}

		if (alertStr != null) {
			alert(alertStr);
			LogUtils.info(alertStr);
			ChessGameManager.getManager().deleteGame(getName(), true);
		}
	}

	/**
	 * Validate that the given player is in this game.
	 *
	 * @param playerId ID of player to check for
	 */
	public void ensurePlayerInGame(String playerId) {
		if (!playerId.equals(getPlayerId(Chess.WHITE)) && !playerId.equals(getPlayerId(Chess.BLACK))) {
			throw new ChessException(Messages.getString("Game.notInGame"));
		}
	}

	/**
	 * Validate that it's the given player's move in this game.
	 *
	 * @param playerId the player ID to check
	 */
	public void ensurePlayerToMove(String playerId) {
		ChessPlayer cp = getPlayerToMove();
		ChessValidate.isTrue(cp != null && playerId.equals(cp.getId()), Messages.getString("Game.notYourTurn"));
	}

	/**
	 * Validate that this game is in the given state.
	 *
	 * @param state the state to check for
	 */
	public void ensureGameState(GameState state) {
		ChessValidate.isTrue(getState() == state, Messages.getString("Game.shouldBeState", state));
	}

	/**
	 * Check if the given player is allowed to delete this game
	 *
	 * @param sender the command sender (console or player)
	 * @return true if the player is allowed to delete the game, false otherwise
	 */
	public boolean playerCanDelete(CommandSender sender) {
		if (sender instanceof ConsoleCommandSender) {
			return true;
		} else if (sender instanceof Player) {
			if (getState() != GameState.SETTING_UP) {
				return false;
			}
			Player player = (Player) sender;
			String playerId = player.getUniqueId().toString();
			String playerWhite = getPlayerId(Chess.WHITE);
			String playerBlack = getPlayerId(Chess.BLACK);

			if (!playerWhite.isEmpty() && playerBlack.isEmpty()) {
				return playerWhite.equals(playerId);
			} else if (playerWhite.isEmpty() && !playerBlack.isEmpty()) {
				return playerBlack.equals(playerId);
			} else if (playerWhite.equals(playerId)) {
				Player other = sender.getServer().getPlayer(UUID.fromString(playerBlack));
				return other == null || !other.isOnline();
			} else if (playerBlack.equals(playerId)) {
				Player other = sender.getServer().getPlayer(UUID.fromString(playerWhite));
				return other == null || !other.isOnline();
			}
			return false;
		} else {
			return false;
		}
	}

	/**
	 * Have the given player offer a draw.
	 *
	 * @param playerId 	ID of the player making the offer
	 * @throws ChessException
	 */
	public void offerDraw(String playerId) {
		ensureGameState(GameState.RUNNING);

		ChessPlayer cp = getPlayer(playerId);
		if (cp == null) {
			return;
		}
		ChessPlayer other = getPlayer(Chess.otherPlayer(cp.getColour()));
		if (other == null) {
			return;
		}

		cp.statusMessage(Messages.getString("ChessCommandExecutor.drawOfferedYou", other.getDisplayName()));
		other.drawOffered();
	}

	/**
	 * Have the given player offer to swap sides.
	 *
	 * @param playerId	ID of the player making the offer (could be human or AI)
	 * @throws ChessException
	 */
	public void offerSwap(String playerId) {
		ChessPlayer cp = getPlayer(playerId);
		if (cp != null) {
			ChessPlayer other = getPlayer(Chess.otherPlayer(cp.getColour()));
			if (other == null) {
				// no other player yet - just swap
				swapColours();
			} else {
				cp.statusMessage(Messages.getString("ChessCommandExecutor.swapOfferedYou", other.getDisplayName()));
				other.swapOffered();
			}
		}
	}

	/**
	 * Have the given player offer to undo the last move they made.
	 *
	 * @param playerId	Name of the player making the offer
	 * @throws ChessException
	 */
	public void offerUndoMove(String playerId) {
		ChessPlayer cp = getPlayer(playerId);
		if (cp == null) {
			return;
		}
		ChessPlayer other = getPlayer(Chess.otherPlayer(cp.getColour()));
		if (other == null) {
			return;
		}
		if (other.isHuman()) {
			// playing another human - we need to ask them if it's OK to undo
			other.undoOffered();
			cp.statusMessage(Messages.getString("ChessCommandExecutor.undoOfferedYou", other.getDisplayName()));
		} else {
			// playing AI - only allow undo if no stake
			ChessValidate.isTrue(getStake() == 0.0, Messages.getString("ChessCommandExecutor.undoAIWithStake"));
			undoMove(playerId);
		}
	}

	/**
	 * Undo the most recent moves until it's the turn of the given player again.  Not
	 * supported for AI vs AI games, only human vs human or human vs AI.  The undoer
	 * must be human.
	 *
	 * @param playerId ID of player undoing the move
	 */
	public void undoMove(String playerId) {
		ChessPlayer cp = getPlayer(playerId);
		if (cp == null) {
			return;
		}
		ChessPlayer other = getPlayer(Chess.otherPlayer(cp.getColour()));
		if (other == null) {
			return;
		}
		if (history.size() == 0 || history.size() == 1 && cp.getColour() == Chess.BLACK) {
			// first move for the player, can't undo yet
			return;
		}

		if (getPosition().getToPlay() == cp.getColour()) {
			// need to undo two moves - first the other player's last move
			other.undoLastMove();
			cpGame.getPosition().undoMove();
			history.remove(history.size() - 1);
		}
		// now undo the undoer's last move
		cp.undoLastMove();
		cpGame.getPosition().undoMove();
		history.remove(history.size() - 1);

		int toPlay = getPosition().getToPlay();
        getClock().setActivePlayer(toPlay);

		save();

		alert(Messages.getString("Game.moveUndone", ChessUtils.getDisplayColour(toPlay)));
	}

	/**
	 * Return detailed information about the game.
	 *
	 * @return a string list of game information
	 */
	public List<String> getGameDetail() {
        List<String> res = new ArrayList<>();

		String white = players[Chess.WHITE] == null ? "?" : players[Chess.WHITE].getDisplayName();
		String black = players[Chess.BLACK] == null ? "?" : players[Chess.BLACK].getDisplayName();
		String bullet = MessagePager.BULLET + ChatColor.YELLOW;

		res.add(Messages.getString("ChessCommandExecutor.gameDetail.name", getName(), getState()));
		res.add(bullet + Messages.getString("ChessCommandExecutor.gameDetail.players", white, black, "?"));  // TODO
		res.add(bullet +  Messages.getString("ChessCommandExecutor.gameDetail.halfMoves", getHistory().size()));
		if (EconomyUtil.enabled()) {
			res.add(bullet + Messages.getString("ChessCommandExecutor.gameDetail.stake", EconomyUtil.formatStakeStr(getStake())));
		}
		res.add(bullet + (getPosition().getToPlay() == Chess.WHITE ?
				Messages.getString("ChessCommandExecutor.gameDetail.whiteToPlay") :
		        Messages.getString("ChessCommandExecutor.gameDetail.blackToPlay")));

		res.add(bullet + Messages.getString("ChessCommandExecutor.gameDetail.timeControlType", getClock().getTimeControl().toString()));
		if (getState() == GameState.RUNNING) {
			res.add(bullet + Messages.getString("ChessCommandExecutor.gameDetail.clock",
                    getClock().getClockString(Chess.WHITE), getClock().getClockString(Chess.BLACK)));
        }
		if (getInvitedId() != null) {
			if (isOpenInvite()) {
				res.add(bullet + Messages.getString("ChessCommandExecutor.gameDetail.openInvitation"));
			} else {
				Player p = Bukkit.getPlayer(getInvitedId());
				if (p == null) {
					invited = null;
				} else {
					res.add(bullet + Messages.getString("ChessCommandExecutor.gameDetail.invitation", p.getDisplayName()));
				}
			}
		}
		res.add(Messages.getString("ChessCommandExecutor.gameDetail.moveHistory"));
		List<Short> h = getHistory();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < h.size(); i += 2) {
			sb.append(ChatColor.WHITE).append(Integer.toString((i / 2) + 1)).append(". ");
			sb.append(ChatColor.YELLOW).append(Move.getString(h.get(i)));
			if (i < h.size() - 1) {
				sb.append(" ").append(Move.getString(h.get(i + 1)));
			}
			sb.append(" ");
		}
		res.add(sb.toString());

		return res;
	}

	/**
	 * If it's been noted that the AI has moved in its game model, make the actual
	 * move in our game model too.  Also check if the AI has failed and we need to abandon.
	 */
	private synchronized void checkForAIActivity() {
		getPlayer(Chess.WHITE).checkPendingAction();
		getPlayer(Chess.BLACK).checkPendingAction();
	}

	/**
	 * Called when a (human) player has logged out.
	 *
	 * @param playerId ID of player who left
	 */
	public void playerLeft(String playerId) {
		int colour = getPlayerColour(playerId);
		if (hasPlayer(colour)) {
			getPlayer(colour).cleanup();
		}
	}

	/**
	 * Update an old-style player name with a UUID.
	 *
	 * @param colour player's colour
	 * @param oldStyleName player's old name
	 * @param uuid player's UUID
	 */
	public void migratePlayer(int colour, String oldStyleName, UUID uuid) {
		players[colour] = new HumanChessPlayer(uuid.toString(), oldStyleName, this, colour);
		LogUtils.info("migrated player " + oldStyleName + " to " + uuid + " in game " + getName());
	}

    public void addGameListener(GameListener listener) {
        listeners.add(listener);
    }

    public TwoPlayerClock getClock() {
        return clock;
    }

    public List<GameListener> getListeners() {
        return listeners;
    }
}
