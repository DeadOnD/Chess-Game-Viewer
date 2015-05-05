package com.thilo.android.chess;

public interface Progress {
	boolean isCancelled();
	void publishProgress(int value);
}
