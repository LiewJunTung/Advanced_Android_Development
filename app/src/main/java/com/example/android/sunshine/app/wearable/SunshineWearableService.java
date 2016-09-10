package com.example.android.sunshine.app.wearable;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.example.WatchFaceUtil;
import com.example.android.sunshine.app.sync.SunshineSyncAdapter;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class SunshineWearableService extends WearableListenerService {

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);
        if (messageEvent.getPath().equals(WatchFaceUtil.INIT_SUNSHINE_PATH)){
            startService(new Intent(this, DataWearableService.class).setAction(SunshineSyncAdapter.ACTION_DATA_UPDATED));
        }
    }
}
