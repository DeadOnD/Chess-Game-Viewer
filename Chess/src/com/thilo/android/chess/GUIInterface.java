package com.thilo.android.chess;

import java.util.List;

import com.thilo.android.gamelogic.Move;
import com.thilo.android.gamelogic.Position;

/** Interface between the gui and the ChessController. */
public interface GUIInterface {

	/** Update the displayed board position. */
	public void setPosition(Position pos, String variantInfo,
			List<Move> variantMoves);

	/** Mark square i as selected. Set to -1 to clear selection. */
	public void setSelection(int sq);

	/** Set the status text. */
	public void setStatusString(String str);

	/** Update the list of moves. */
	public void moveListUpdated();

	/** Update the computer thinking information. */
	public void setThinkingInfo(String pvStr, String bookInfo,
			List<Move> pvMoves, List<Move> bookMoves);

	/**
	 * Ask what to promote a pawn to. Should call reportPromotePiece() when
	 * done.
	 */
	public void requestPromotePiece();

	/** Run code on the GUI thread. */
	public void runOnUIThread(Runnable runnable);

	/** Report that user attempted to make an invalid move. */
	public void reportInvalidMove(Move m);

	/** Report remaining thinking time to GUI. */
	public void setGameInformation(String white, String black, String gameNo);

	/** Report a move made that is a candidate for GUI animation. */
	public void setAnimMove(Position sourcePos, Move move, boolean forward);

	public ScidApplication getScidAppContext();

	public void setFromSelection(int fromSq);
}
