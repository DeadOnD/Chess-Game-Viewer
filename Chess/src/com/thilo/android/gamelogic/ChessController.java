package com.thilo.android.gamelogic;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;


import com.thilo.android.chess.GUIInterface;
import com.thilo.android.chess.GameMode;
import com.thilo.android.chess.PGNOptions;
import com.thilo.android.database.DataBaseView;
import com.thilo.android.engine.ComputerPlayer;
import com.thilo.android.engine.EngineConfig;
import com.thilo.android.gamelogic.Game.GameState;
import com.thilo.android.gamelogic.GameTree.Node;

import android.util.Log;

/**
 * The glue between the chess engine and the GUI.
 *
 * @author petero
 */
public class ChessController {
	private ComputerPlayer computerPlayer = null;
	private PgnToken.PgnTokenReceiver gameTextListener = null;
	private String bookFileName = "";
	private Game game;
	private GUIInterface gui;
	private GameMode gameMode;
	private PGNOptions pgnOptions;
	private AnalysisTask analysisTask;

	private int timeControl;
	private int movesPerSession;
	private int timeIncrement;

	class SearchListener implements com.thilo.android.gamelogic.SearchListener {
		private int currDepth = 0;
		private int currMoveNr = 0;
		private String currMove = "";
		private int currNodes = 0;
		private int currNps = 0;
		private int currTime = 0;

		private int pvDepth = 0;
		private int pvScore = 0;
		private boolean pvIsMate = false;
		private boolean pvUpperBound = false;
		private boolean pvLowerBound = false;
		private String bookInfo = "";
		private String pvStr = "";
		private List<Move> pvMoves = null;
		private List<Move> bookMoves = null;
		private boolean whiteMove = true;

		public final void clearSearchInfo() {
			pvDepth = 0;
			currDepth = 0;
			bookInfo = "";
			pvMoves = null;
			bookMoves = null;
			setSearchInfo();
		}

		private final void setSearchInfo() {
			StringBuilder buf = new StringBuilder();
			if (pvDepth > 0) {
				buf.append(String.format("[%d] ", pvDepth));
				boolean negateScore = !whiteMove;
				if (pvUpperBound || pvLowerBound) {
					boolean upper = pvUpperBound ^ negateScore;
					buf.append(upper ? "<=" : ">=");
				}
				int score = negateScore ? -pvScore : pvScore;
				if (pvIsMate) {
					buf.append(String.format("m%d", score));
				} else {
					buf.append(String.format("%.2f", score / 100.0));
				}
				buf.append(pvStr);
				buf.append("\n");
			}
			if (currDepth > 0) {
				buf.append(String.format("d:%d %d:%s t:%.2f n:%d nps:%d",
						currDepth, currMoveNr, currMove, currTime / 1000.0,
						currNodes, currNps));
			}
			final String newPV = buf.toString();
			final String newBookInfo = bookInfo;
			final SearchStatus localSS = ss;
			gui.runOnUIThread(new Runnable() {
				public void run() {
					if (!localSS.searchResultWanted && (bookMoves != null))
						return;
					gui.setThinkingInfo(newPV, newBookInfo, pvMoves, bookMoves);
				}
			});
		}

		public void notifyDepth(int depth) {
			currDepth = depth;
			setSearchInfo();
		}

		public void notifyCurrMove(Position pos, Move m, int moveNr) {
			currMove = TextIO.moveToString(pos, m, false);
			currMoveNr = moveNr;
			setSearchInfo();
		}

		public void notifyPV(Position pos, int depth, int score, int time,
				int nodes, int nps, boolean isMate, boolean upperBound,
				boolean lowerBound, ArrayList<Move> pv) {
			pvDepth = depth;
			pvScore = score;
			currTime = time;
			currNodes = nodes;
			currNps = nps;
			pvIsMate = isMate;
			pvUpperBound = upperBound;
			pvLowerBound = lowerBound;
			whiteMove = pos.whiteMove;

			StringBuilder buf = new StringBuilder();
			Position tmpPos = new Position(pos);
			UndoInfo ui = new UndoInfo();
			for (Move m : pv) {
				buf.append(String.format(" %s",
						TextIO.moveToString(tmpPos, m, false)));
				tmpPos.makeMove(m, ui);
			}
			pvStr = buf.toString();
			pvMoves = pv;
			setSearchInfo();
		}

		public void notifyStats(int nodes, int nps, int time) {
			currNodes = nodes;
			currNps = nps;
			currTime = time;
			setSearchInfo();
		}

		@Override
		public void notifyBookInfo(String bookInfo, List<Move> moveList) {
			this.bookInfo = bookInfo;
			bookMoves = moveList;
			setSearchInfo();
		}

		public void prefsChanged() {
			setSearchInfo();
		}
	}

	SearchListener listener;

	public ChessController(GUIInterface gui,
			PgnToken.PgnTokenReceiver gameTextListener, PGNOptions options) {
		this.gui = gui;
		this.gameTextListener = gameTextListener;
		pgnOptions = options;
		listener = new SearchListener();
	}

	public final void setBookFileName(String bookFileName) {
		if (!this.bookFileName.equals(bookFileName)) {
			this.bookFileName = bookFileName;
			if (computerPlayer != null) {
				computerPlayer.setBookFileName(bookFileName);
				if (analysisTask != null) {
					stopAnalysis();
					startAnalysis();
				}
				updateBookHints();
			}
		}
	}

	private final void updateBookHints() {
		if (computerPlayer != null && gameMode != null) {
			boolean analysis = gameMode.analysisMode();
			if (!analysis) {
				ss = new SearchStatus();
				Pair<String, ArrayList<Move>> bi = computerPlayer
						.getBookHints(game.currPos());
				listener.notifyBookInfo(bi.first, bi.second);
			}
		}
	}

	private final class SearchStatus {
		boolean searchResultWanted = true;
	}

	SearchStatus ss = new SearchStatus();

	public final void newGame(GameMode gameMode) {
		ss.searchResultWanted = false;
		stopAnalysis();
		this.gameMode = gameMode;
		game = new Game(computerPlayer, gameTextListener, timeControl,
				movesPerSession, timeIncrement);
		setPlayerNames(game);
		updateGameMode();
	}

	public final void startEngine(EngineConfig engineDef) {
		if (computerPlayer == null) {
			computerPlayer = new ComputerPlayer(engineDef);
			computerPlayer.setListener(listener);
			computerPlayer.setBookFileName(bookFileName);
			game.setComputerPlayer(computerPlayer);
			gameTextListener.clear();
		}
	}

	public EngineConfig getEngineConfig() {
		if (computerPlayer != null && computerPlayer.getEngine() != null) {
			return computerPlayer.getEngine().getEngineConfig();
		}
		return null;
	}

	public final void startGame() {
		updateComputeThreads(true);
		setSelection();
		updateGUI();
		updateGameMode();
	}

	private boolean guiPaused = false;

	public final void setGuiPaused(boolean paused) {
		guiPaused = paused;
		updateGameMode();
		if (paused) {
			stopAnalysis();
		} else {
			updateComputeThreads(true);
		}
	}

	private final void updateGameMode() {
		if (game != null) {
			boolean gamePaused = gameMode.analysisMode() || guiPaused;
			game.setGamePaused(gamePaused);
		}
	}

	private final void updateComputeThreads(boolean clearPV) {
		boolean analysis = gameMode.analysisMode();
		if (!analysis)
			stopAnalysis();
		if (clearPV) {
			listener.clearSearchInfo();
			updateBookHints();
		}
		if (analysis && this.computerPlayer != null)
			startAnalysis();
	}

	private final synchronized void startAnalysis() {
		if (gameMode.analysisMode()) {
			if (analysisTask == null) {
				ss = new SearchStatus();
				final Pair<Position, ArrayList<Move>> ph = game.getUCIHistory();
				final Position currPos = new Position(game.currPos());
				final boolean valid = game.tree.getGameState() != GameState.WHITE_MATE
						&& game.tree.getGameState() != GameState.BLACK_MATE
						&& game.tree.getGameState() != GameState.BLACK_STALEMATE
						&& game.tree.getGameState() != GameState.WHITE_STALEMATE;
				listener.clearSearchInfo();
				if (valid) {
					analysisTask = (AnalysisTask) new AnalysisTask().execute(
							computerPlayer.getEngine(), listener, ph.first,
							ph.second, currPos);
				}
				updateGUI();
			}
		}
	}

	private final synchronized void stopAnalysis() {
		if (analysisTask != null) {
			synchronized (analysisTask) {
				analysisTask.cancel(false);
				while (!analysisTask.isFinished()) {
					try {
						Thread.sleep(10);
					} catch (InterruptedException e) {
					}
				}
				analysisTask = null;
				listener.clearSearchInfo();
				updateGUI();
			}
		}
	}

	/** Set game mode. */
	public final boolean setGameMode(GameMode newMode) {
		boolean changed = false;
		if (!gameMode.equals(newMode)) {
			changed = true;
			ss.searchResultWanted = false;
			gameMode = newMode;
			updateGameMode();
			updateComputeThreads(true);
			updateGUI();
		}
		return changed;
	}

	public final void prefsChanged() {
		updateBookHints();
		updateMoveList();
		listener.prefsChanged();
	}

	private final void setPlayerNames(Game game) {
		if (game != null) {
			String white = "";
			String black = "";
			game.tree.setPlayerNames(white, black);
		}
	}

	public final void fromByteArray(byte[] data) {
		game.fromByteArray(data);
	}

	public final byte[] toByteArray() {
		return game.tree.toByteArray();
	}

	public final String getFEN() {
		return TextIO.toFEN(game.tree.currentPos);
	}

	/** Convert current game to PGN format. */
	public final String getPGN() {
		return game.tree.toPGN(pgnOptions);
	}

	public final void setPGN(String pgn) throws ChessParseError {
		Game newGame = new Game(null, gameTextListener, timeControl,
				movesPerSession, timeIncrement);
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd-hh:mm:ss.SSS");
		df.setTimeZone(TimeZone.getTimeZone("UTC"));
		Date d1 = new Date();
		if (newGame.readPGN(pgn, pgnOptions)) {
			Date d2 = new Date();
			Log.d("SCID",
					"before/after readPGN " + df.format(d1) + "/"
							+ df.format(d2));
			game = newGame;
			updateGame();
		}
	}

	public void updateGame() {
		ss.searchResultWanted = false;
		game.setComputerPlayer(computerPlayer);
		gameTextListener.clear();
		updateGameMode();
		stopAnalysis();
		if (computerPlayer != null) {
			updateComputeThreads(true);
		}
		gui.setSelection(-1);
		gui.setFromSelection(-1);
		updateGUI();
	}

	public boolean hasEngineStarted() {
		return (computerPlayer != null);
	}

	public final void setFENOrPGN(String fenPgn) throws ChessParseError {
		try {
			Game newGame = new Game(null, gameTextListener, timeControl,
					movesPerSession, timeIncrement);
			Position pos = TextIO.readFEN(fenPgn);
			newGame.setPos(pos);
			setPlayerNames(newGame);
			game = newGame;
			updateGame();
		} catch (ChessParseError e) {
			// Try read as PGN instead
			setPGN(fenPgn);
		}
	}

	/** Return true if computer player is using CPU power. */
	public final boolean computerBusy() {
		if (game.getGameState() != GameState.ALIVE)
			return false;
		return gameMode.analysisMode();
	}

	private final boolean undoMoveNoUpdate() {
		if (game.getLastMove() == null)
			return false;
		ss.searchResultWanted = false;
		game.undoMove();
		return true;
	}

	public final boolean canUndoMove() {
		return game.getLastMove() != null;
	}

	public final void undoMove() {
		if (game.getLastMove() != null) {
			ss.searchResultWanted = false;
			stopAnalysis();
			boolean didUndo = undoMoveNoUpdate();
			updateComputeThreads(true);
			setSelection();
			if (didUndo)
				setAnimMove(game.currPos(), game.getNextMove(), false);
			updateGUI();
		}
	}

	private final void redoMoveNoUpdate() {
		if (game.canRedoMove()) {
			ss.searchResultWanted = false;
			game.redoMove();
		}
	}

	public final boolean canRedoMove() {
		return game.canRedoMove();
	}

	public final void redoMove() {
		if (canRedoMove()) {
			ss.searchResultWanted = false;
			stopAnalysis();
			redoMoveNoUpdate();
			updateComputeThreads(true);
			setSelection();
			setAnimMove(game.prevPos(), game.getLastMove(), true);
			updateGUI();
		}
	}

	public final int numVariations() {
		return game.numVariations();
	}

	public final void changeVariation(int delta) {
		if (game.numVariations() > 1) {
			ss.searchResultWanted = false;
			stopAnalysis();
			game.changeVariation(delta);
			updateComputeThreads(true);
			setSelection();
			updateGUI();
		}
	}

	public final void removeSubTree() {
		ss.searchResultWanted = false;
		stopAnalysis();
		game.removeSubTree();
		updateComputeThreads(true);
		setSelection();
		updateGUI();
	}

	public final void moveVariation(int delta) {
		if (game.numVariations() > 1) {
			game.moveVariation(delta);
			updateGUI();
		}
	}

	public final void gotoMove(int moveNr) {
		gotoHalfMove(moveNr * 2);
	}

	public final void gotoHalfMove(int moveNr) {
		boolean needUpdate = false;
		while (game.currPos().halfMoveCounter > moveNr) { // Go backward
			int before = game.currPos().halfMoveCounter * 2
					+ (game.currPos().whiteMove ? 0 : 1);
			undoMoveNoUpdate();
			int after = game.currPos().halfMoveCounter * 2
					+ (game.currPos().whiteMove ? 0 : 1);
			if (after >= before)
				break;
			needUpdate = true;
		}
		while (game.currPos().halfMoveCounter < moveNr) { // Go forward
			int before = game.currPos().halfMoveCounter * 2
					+ (game.currPos().whiteMove ? 0 : 1);
			redoMoveNoUpdate();
			int after = game.currPos().halfMoveCounter * 2
					+ (game.currPos().whiteMove ? 0 : 1);
			if (after <= before)
				break;
			needUpdate = true;
		}
		if (needUpdate) {
			ss.searchResultWanted = false;
			stopAnalysis();
			updateComputeThreads(true);
			setSelection();
			updateGUI();
		}
	}

	public final void makeHumanMove(Move m) {
		if (doMove(m)) {
			ss.searchResultWanted = false;
			stopAnalysis();
			updateComputeThreads(true);
			updateGUI();
			if (gameMode.studyMode()) {
				redoMove();
			}
		} else {
			gui.setSelection(-1);
		}
	}

	Move promoteMove;

	public final void reportPromotePiece(int choice) {
		final boolean white = game.currPos().whiteMove;
		int promoteTo;
		switch (choice) {
		case 1:
			promoteTo = white ? Piece.WROOK : Piece.BROOK;
			break;
		case 2:
			promoteTo = white ? Piece.WBISHOP : Piece.BBISHOP;
			break;
		case 3:
			promoteTo = white ? Piece.WKNIGHT : Piece.BKNIGHT;
			break;
		default:
			promoteTo = white ? Piece.WQUEEN : Piece.BQUEEN;
			break;
		}
		promoteMove.promoteTo = promoteTo;
		Move m = promoteMove;
		promoteMove = null;
		makeHumanMove(m);
	}

	/**
	 * Move a piece from one square to another.
	 *
	 * @return True if the move was legal, false otherwise.
	 */
	final private boolean doMove(Move move) {
		Position pos = game.currPos();
		ArrayList<Move> moves = new MoveGen().pseudoLegalMoves(pos);
		moves = MoveGen.removeIllegal(pos, moves);
		int promoteTo = move.promoteTo;
		for (Move m : moves) {
			if ((m.from == move.from) && (m.to == move.to)) {
				if ((m.promoteTo != Piece.EMPTY) && (promoteTo == Piece.EMPTY)) {
					promoteMove = m;
					gui.requestPromotePiece();
					return false;
				}
				if (m.promoteTo == promoteTo) {
					String strMove = TextIO.moveToString(pos, m, false);
					boolean isCorrect = game.processString(strMove,
							gameMode.studyMode());
					if (!isCorrect && this.gameMode.studyMode()) {
						gui.setSelection(-1);
						gui.reportInvalidMove(m);
						return false;
					}
					return true;
				}
			}
		}
		gui.reportInvalidMove(move);
		return false;
	}

	final public void updateGUI() {
		updateStatus();
		updateMoveList();

		StringBuilder sb = new StringBuilder();
		if (game.tree.currentNode != game.tree.rootNode) {
			game.tree.goBack();
			Position pos = game.currPos();
			List<Move> prevVarList = game.tree.variations();
			for (int i = 0; i < prevVarList.size(); i++) {
				if (i > 0)
					sb.append(' ');
				if (i == game.tree.currentNode.defaultChild)
					sb.append("<b>");
				sb.append(TextIO.moveToString(pos, prevVarList.get(i), false));
				if (i == game.tree.currentNode.defaultChild)
					sb.append("</b>");
			}
			game.tree.goForward(-1);
		}
		gui.setPosition(game.currPos(), sb.toString(), game.tree.variations());
		// TODO: all this does not belong to ChessController
		DataBaseView dbv = gui.getScidAppContext().getDataBaseView();
		String gameTitle = "";
		if (dbv != null) {
			int position = dbv.getPosition() + 1, id = dbv.getGameId() + 1,
					count = dbv.getCount(), total = dbv.getTotalGamesInFile();
			gameTitle = "" + position + "/" + count
					+ ((count == total)
							? ""
							: " (" + id + "/" + total + ")");
		}
		gui.setGameInformation(game.tree.white, game.tree.black, gameTitle);
	}

	private void updateStatus() {
		String str = "";
		String result = game.tree.getResult();
		if (!result.equals("*")) {
			str += result + " ";
		}
		String date = game.tree.date.replaceAll("\\.\\?\\?", "");
		if (!date.equals("?") && !date.equals("????")) {
			str += date + " ";
		}
		if (!game.tree.site.equals("?")) {
			str += game.tree.site + ": ";
		}
		if (!game.tree.event.equals("?")) {
			str += game.tree.event + " ";
		}
		if (!game.tree.round.equals("?")) {
			str += "(" + game.tree.round + ")";
		}
		gui.setStatusString(str);
	}

	final private void updateMoveList() {
		if (game != null) {
			if (!gameTextListener.isUpToDate()) {
				PGNOptions tmpOptions = new PGNOptions();
				tmpOptions.exp.variations = pgnOptions.view.variations;
				tmpOptions.exp.comments = pgnOptions.view.comments;
				tmpOptions.exp.nag = pgnOptions.view.nag;
				tmpOptions.exp.playerAction = false;
				tmpOptions.exp.clockInfo = false;
				tmpOptions.exp.moveNrAfterNag = false;
				gameTextListener.clear();
				game.tree.pgnTreeWalker(tmpOptions, gameTextListener);
			}
			gameTextListener.setCurrent(game.tree.currentNode);
			gui.moveListUpdated();
		}
	}

	final private void setSelection() {
		Move m = game.getLastMove();
		int fromSq = (m != null) ? m.from : -1;
		gui.setFromSelection(fromSq);
		int sq = (m != null) ? m.to : -1;
		gui.setSelection(sq);
	}

	public final synchronized void setTimeLimit(int time, int moves, int inc) {
		timeControl = time;
		movesPerSession = moves;
		timeIncrement = inc;
		if (game != null)
			game.timeController.setTimeControl(timeControl, movesPerSession,
					timeIncrement);
	}

	private Object shutdownEngineLock = new Object();

	public final void shutdownEngine() {
		synchronized (shutdownEngineLock) {
			ss.searchResultWanted = false;
			stopAnalysis();
			if (computerPlayer != null) {
				computerPlayer.shutdownEngine();
				computerPlayer = null;
			}
		}
	}

	/**
	 * Help human to claim a draw by trying to find and execute a valid draw
	 * claim.
	 */
	public final boolean claimDrawIfPossible() {
		if (!findValidDrawClaim())
			return false;
		updateGUI();
		return true;
	}

	private final boolean findValidDrawClaim() {
		if (game.getGameState() != GameState.ALIVE)
			return true;
		game.processString("draw accept", false);
		if (game.getGameState() != GameState.ALIVE)
			return true;
		game.processString("draw rep", false);
		if (game.getGameState() != GameState.ALIVE)
			return true;
		game.processString("draw 50", false);
		if (game.getGameState() != GameState.ALIVE)
			return true;
		return false;
	}

	public final void setHeaders(Map<String, String> headers) {
		game.tree.setHeaders(headers);
		gameTextListener.clear();
		updateGUI();
	}

	public final void getHeaders(Map<String, String> headers) {
		game.tree.getHeaders(headers);
	}

	public final void resignGame() {
		if (game.getGameState() == GameState.ALIVE) {
			game.processString("resign", false);
			updateGUI();
		}
	}

	private void setAnimMove(Position sourcePos, Move move, boolean forward) {
		gui.setAnimMove(sourcePos, move, forward);
	}

	public void gotoNode(Node node) {
		this.game.tree.gotoNode(node);
		this.startGame();
	}
}
