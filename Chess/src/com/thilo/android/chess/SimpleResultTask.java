package com.thilo.android.chess;

import android.app.Activity;

public abstract class SimpleResultTask extends ProgressingTask<Boolean> {
	public SimpleResultTask(Activity activity, int titleResourceId) {
		super(activity, titleResourceId, R.string.please_wait);
	}

	@Override
	protected void onPostExecute(Boolean result) {
		dismissProgress();
		if (result) {
			activity.setResult(Activity.RESULT_OK);
		} else {
			activity.setResult(Activity.RESULT_CANCELED);
		}
		activity.finish();
	}
}
