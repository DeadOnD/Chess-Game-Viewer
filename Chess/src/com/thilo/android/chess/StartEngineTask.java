package com.thilo.android.chess;


import com.thilo.android.engine.EngineConfig;
import com.thilo.android.gamelogic.ChessController;

import android.os.AsyncTask;

public class StartEngineTask extends AsyncTask {
	private ScidAndroidActivity activity;
	private ChessController ctrl;

	@Override
	protected Object doInBackground(Object... params) {
		this.activity = (ScidAndroidActivity) params[0];
		this.ctrl = (ChessController) params[1];
		EngineConfig engineConfig = (EngineConfig) params[2];
		ctrl.startEngine(engineConfig);
		return null;
	}

	@Override
	protected void onPostExecute(Object result) {
		activity.onFinishStartAnalysis();
	}
}
