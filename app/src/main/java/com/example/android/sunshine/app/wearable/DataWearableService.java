package com.example.android.sunshine.app.wearable;

import android.app.IntentService;
import android.content.Intent;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;


import com.bumptech.glide.Glide;
import com.example.WatchFaceUtil;
import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static com.example.android.sunshine.app.sync.SunshineSyncAdapter.ACTION_DATA_UPDATED;

public class DataWearableService extends IntentService implements
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener
{
    private static final String LOG_TAG = DataWearableService.class.getSimpleName();

    private static final String[] NOTIFY_WEATHER_PROJECTION = new String[]{
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_SHORT_DESC
    };

    // these indices must match the projection
    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;
    private static final int INDEX_SHORT_DESC = 3;

    public DataWearableService() {
        super("DataWearableService");
    }

    GoogleApiClient mGoogleApiClient;

    @Override
    public void onCreate() {
        super.onCreate();
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mGoogleApiClient.disconnect();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            if (ACTION_DATA_UPDATED.equals(action)) {
                mGoogleApiClient.blockingConnect();
                if (mGoogleApiClient.isConnected()){
                    updateDataMap();
                }
            }
        }
    }

    private void updateDataMap() {
        String locationQuery = Utility.getPreferredLocation(this);

        Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());

        Cursor cursor = getContentResolver().query(weatherUri, NOTIFY_WEATHER_PROJECTION, null, null, null);
        if (cursor.moveToFirst()) {
            int weatherId = cursor.getInt(INDEX_WEATHER_ID);
            double high = cursor.getDouble(INDEX_MAX_TEMP);
            double low = cursor.getDouble(INDEX_MIN_TEMP);

            int artResourceId = Utility.getArtResourceForWeatherCondition(weatherId);
            String imageUrl = Utility.getImageUrlForWeatherCondition(weatherId);
            if (imageUrl != null) {
                Bitmap largeIcon;
                try {
                    largeIcon = Glide.with(this)
                            .load(imageUrl)
                            .asBitmap()
                            .error(artResourceId)
                            .centerCrop()
                            .into(320, 320)
                            .get();
                } catch (InterruptedException | ExecutionException e) {
                    Log.e(LOG_TAG, "Error retrieving large icon from " + imageUrl, e);
                    largeIcon = BitmapFactory.decodeResource(getResources(), artResourceId);
                }
                PutDataMapRequest dataMap = PutDataMapRequest.create(WatchFaceUtil.SUNSHINE_PATH);
                dataMap.getDataMap().putDouble(WatchFaceUtil.TEMP_HIGH, high);
                dataMap.getDataMap().putDouble(WatchFaceUtil.TEMP_LOW, low);
                dataMap.getDataMap().putString(WatchFaceUtil.WEATHER_DESC, Utility.getStringForWeatherCondition(this, weatherId));
                dataMap.getDataMap().putAsset(WatchFaceUtil.WEATHER_ICON, toAsset(largeIcon));
                dataMap.getDataMap().putLong("Time",System.currentTimeMillis());
                PutDataRequest request = dataMap.asPutDataRequest();
                request.setUrgent();
                Log.d(LOG_TAG, "Sending Data");
                Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                        .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                            @Override
                            public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                                Log.d(LOG_TAG, "Send result successful: " + dataItemResult.getStatus().isSuccess());
                            }
                        });
            }

        }
        cursor.close();
    }

    /**
     * Builds an {@link com.google.android.gms.wearable.Asset} from a bitmap. The image that we get
     * back from the camera in "data" is a thumbnail size. Typically, your image should not exceed
     * 320x320 and if you want to have zoom and parallax effect in your app, limit the size of your
     * image to 640x400. Resize your image before transferring to your wearable device.
     */
    private static Asset toAsset(Bitmap bitmap) {
        ByteArrayOutputStream byteStream = null;
        try {
            byteStream = new ByteArrayOutputStream();
            //bitmap = Bitmap.createScaledBitmap(bitmap, 320, 320, false);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteStream);

            final ByteArrayOutputStream baos = new
                    ByteArrayOutputStream();

            return Asset.createFromBytes(byteStream.toByteArray());
        } finally {
            if (null != byteStream) {
                try {
                    byteStream.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.d(LOG_TAG, "onConnected()");
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(LOG_TAG, "onSuspended()");
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Log.e(LOG_TAG, "onConnectionFailed()");
    }
}
