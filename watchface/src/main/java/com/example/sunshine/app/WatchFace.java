/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import android.support.annotation.NonNull;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.example.WatchFaceUtil;
import com.example.android.sunshine.app.R;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private static final String LOG_TAG = WatchFace.class.getSimpleName();

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WatchFace.Engine> mWeakReference;

        public EngineHandler(WatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine
            extends CanvasWatchFaceService.Engine
            implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {
        GoogleApiClient mGoogleApiClient;
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimeTextPaint;
        Paint mTempHighTextPaint;
        Paint mTempLowTextPaint;
        Paint mDateTextPaint;
        Paint mDescriptionTextPaint;
        Paint mSecondary;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;

        boolean isFirstLoad;
        float mTempYOffset;
        float mTempXOffsetHigh;
        private float mTempXOffsetLow;
        private String mTempLow;
        private String mTempHigh;

        private String mDesc;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private Paint mIconPaint;
        private Bitmap mGrayBackgroundBitmap;
        private Bitmap mBitMap;
        private Node mNode;
        private SimpleDateFormat mCalendarFormat;
        private float mDateXOffset;
        private float mDateYOffset;
        private float mDescXOffset;
        private float mDescYOffset;
        private boolean mBurnInProtection;
        private Paint mGrayPaint;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mTempHigh = "-";
            mTempLow = "-";
            mDesc = "-";
            isFirstLoad = true;
            setWatchFaceStyle(new WatchFaceStyle.Builder(WatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = WatchFace.this.getResources();
            //mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mTempYOffset = resources.getDimension(R.dimen.temp_y_offset_high);
            mCalendarFormat = new SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault());

            initIconPaint();
            initGrayPaint();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTimeTextPaint = new Paint();
            mTimeTextPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);

            mTempHighTextPaint = new Paint();
            mTempHighTextPaint = createTextPaint(resources.getColor(R.color.digital_text), Typeface.SANS_SERIF);

            mTempLowTextPaint = new Paint();
            mTempLowTextPaint = createTextPaint(resources.getColor(R.color.digital_text_secondary), Typeface.SANS_SERIF);

            mDescriptionTextPaint = new Paint();
            mDescriptionTextPaint = createTextPaint(resources.getColor(R.color.digital_text), Typeface.DEFAULT_BOLD);

            mDateTextPaint = new Paint();
            mDateTextPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);



            mCalendar = Calendar.getInstance();

            mGoogleApiClient = new GoogleApiClient.Builder(WatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
        }

        private void initIconPaint() {
            float scale = 0.8f;
            mIconPaint = new Paint();
            mIconPaint.setColor(Color.BLACK);
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0f);
            colorMatrix.setScale(scale, scale, scale, 1f);
            mIconPaint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));// darken
        }

        private void initGrayPaint() {
            float lr = 0.2126f;
            float lg = 0.7152f;
            float lb = 0.0722f;
            ColorMatrix colorMatrix = new ColorMatrix(new float[] {
                    lr, lg, lb, 0, 0, //
                    lr, lg, lb, 0, 0, //
                    lr, lg, lb, 0, 0, //
                    0, 0, 0, 0, 255, //
            });
            mGrayPaint = new Paint();
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            mGrayPaint.setColorFilter(filter);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();
                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
                mGoogleApiClient.disconnect();
                Wearable.DataApi.removeListener(mGoogleApiClient, this);
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_y_offset_round : R.dimen.digital_y_offset
            );
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            mTempXOffsetHigh = resources.getDimension(isRound
                    ? R.dimen.temp_x_offset_round_high : R.dimen.temp_x_offset_high);
            mTempXOffsetLow = resources.getDimension(isRound
                    ? R.dimen.temp_x_offset_round_low : R.dimen.temp_x_offset_low);

            mDateXOffset = resources.getDimension(isRound
                    ? R.dimen.date_x_offset_round : R.dimen.date_x_offset);
            mDateYOffset = resources.getDimension(isRound
                    ? R.dimen.date_y_offset_round : R.dimen.date_y_offset);

            mDescXOffset = resources.getDimension(isRound
                    ? R.dimen.desc_x_offset_round : R.dimen.desc_x_offset);
            mDescYOffset = resources.getDimension(isRound
                    ? R.dimen.desc_y_offset_round : R.dimen.desc_y_offset);

            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float highTempTextSize = resources.getDimension(isRound
                    ? R.dimen.temp_high_text_size_round : R.dimen.temp_high_text_size);
            float lowTempTextSize = resources.getDimension(isRound
                    ? R.dimen.temp_low_text_size_round : R.dimen.temp_low_text_size);
            float descTempTextSize = resources.getDimension(isRound
                    ? R.dimen.status_text_size_round : R.dimen.status_text_size);
            float dateTempTextSize = resources.getDimension(isRound
                    ? R.dimen.date_text_size : R.dimen.date_text_size);

            mTimeTextPaint.setTextSize(timeTextSize);
            mTempLowTextPaint.setTextSize(lowTempTextSize);
            mTempHighTextPaint.setTextSize(highTempTextSize);
            mDateTextPaint.setTextSize(dateTempTextSize);
            mDescriptionTextPaint.setTextSize(descTempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }


        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimeTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    Log.d(LOG_TAG, "onTapCommand: Click");
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.


            if (mBitMap != null) {
                if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                    canvas.drawColor(Color.BLACK);
                } else if (mAmbient) {
                    canvas.drawBitmap(mBitMap, 0, 0, mGrayPaint);
                } else {
                    canvas.drawBitmap(mBitMap, 0, 0, mIconPaint);
                }
            } else {
                if (isInAmbientMode()) {
                    canvas.drawColor(Color.BLACK);
                } else {
                    canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
                }
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            String text;
            if (mAmbient){
                text = String.format(Locale.getDefault(), "%d:%02d", mCalendar.get(Calendar.HOUR),
                        mCalendar.get(Calendar.MINUTE));
            } else {
                text = String.format(Locale.getDefault(), "%d:%02d:%02d", mCalendar.get(Calendar.HOUR),
                        mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));
            }

            canvas.drawText(mCalendarFormat.format(mCalendar.getTime()), mDateXOffset, mDateYOffset, mDateTextPaint);
            canvas.drawText(mDesc, mDescXOffset, mDescYOffset, mDescriptionTextPaint);
            canvas.drawText(mTempHigh, mTempXOffsetHigh, mTempYOffset, mTempHighTextPaint);
            canvas.drawText(mTempLow, mTempXOffsetLow, mTempYOffset, mTempLowTextPaint);
            canvas.drawText(text, mXOffset, mYOffset, mTimeTextPaint);

        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private void initNode() {
            Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                @Override
                public void onResult(@NonNull NodeApi.GetConnectedNodesResult nodes) {
                    for (Node node : nodes.getNodes()) {
                        mNode = node;
                        Log.d(LOG_TAG, "Sending message " + mNode.getId());
                    }
                    Wearable.MessageApi.sendMessage(mGoogleApiClient, mNode.getId(), WatchFaceUtil.INIT_SUNSHINE_PATH, null);

                }
            });
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnected(Bundle connectionHint) {
            Log.d(LOG_TAG, "onConnected: ");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Log.d(LOG_TAG, "first load?" + isFirstLoad);
            if (isFirstLoad) {
                initNode();
            }
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnectionSuspended(int cause) {
            Log.d(LOG_TAG, "onConnectionSuspended: " + cause);
        }

        @Override  // GoogleApiClient.OnConnectionFailedListener
        public void onConnectionFailed(ConnectionResult result) {
            Log.d(LOG_TAG, "onConnectionFailed: " + result);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d(LOG_TAG, "onDataChanged");
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }
                DataItem dataItem = dataEvent.getDataItem();
                Log.d(LOG_TAG, dataItem.getUri().getPath());
                if (!dataItem.getUri().getPath().equals(WatchFaceUtil.SUNSHINE_PATH)) {
                    continue;
                }

                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                DataMap tempDataMap = dataMapItem.getDataMap();
                updateUiForTempDataMap(tempDataMap);
                isFirstLoad = false;
            }
        }

        private void updateUiForTempDataMap(DataMap tempDataMap) {
            mTempHigh = getString(R.string.format_temperature, tempDataMap.getDouble(WatchFaceUtil.TEMP_HIGH, 0));
            mTempLow = getString(R.string.format_temperature, tempDataMap.getDouble(WatchFaceUtil.TEMP_LOW, 0));
            mDesc = tempDataMap.getString(WatchFaceUtil.WEATHER_DESC, "");
            Asset asset = tempDataMap.getAsset(WatchFaceUtil.WEATHER_ICON);
            new LoadBitmapAsyncTask().execute(asset);
            Log.d(LOG_TAG, "updateUiForTempDataMap: " + tempDataMap.toString());
            invalidate();
        }

        private class LoadBitmapAsyncTask extends AsyncTask<Asset, Void, Bitmap> {

            @Override
            protected Bitmap doInBackground(Asset... params) {

                if (params.length > 0) {

                    Asset asset = params[0];

                    InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                            mGoogleApiClient, asset).await().getInputStream();

                    if (assetInputStream == null) {
                        Log.w(LOG_TAG, "Requested an unknown Asset.");
                        return null;
                    }
                    return BitmapFactory.decodeStream(assetInputStream);

                } else {
                    Log.e(LOG_TAG, "Asset must be non-null");
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {

                if (bitmap != null) {
                    Log.d(LOG_TAG, "Setting bitmap");
                    mBitMap = bitmap;
                    invalidate();
                }
            }
        }
    }
}
