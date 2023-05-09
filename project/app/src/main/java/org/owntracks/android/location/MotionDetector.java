package org.owntracks.android.location;

import android.location.Location;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import javax.inject.Inject;

public class MotionDetector {
    private static final int MAX_STATIONARY_SECONDS = 300;

    private Location previousLocation = null;

    private boolean moving = false;

    private long previousMovingTime = 0;

    private MutableLiveData<Boolean> currentMotion = new MutableLiveData<Boolean>();

    @Inject
    public MotionDetector() {
    }

    public void onLocationChanged(Location location) {
        boolean newMoving = false;
        if (previousLocation != null) {
            float distance = location.distanceTo(previousLocation);
            if (distance > location.getAccuracy() && distance > previousLocation.getAccuracy()) {
                newMoving = true;
                previousMovingTime = location.getElapsedRealtimeNanos();
                previousLocation = location;
            } else if ((((location.getElapsedRealtimeNanos() - previousMovingTime)) / 1000000000) < MAX_STATIONARY_SECONDS) {
                newMoving = true;
            }
        } else {
            previousLocation = location;
        }
        if (moving != newMoving) {
            moving = newMoving;
            currentMotion.postValue(moving);
        }
    }

    public LiveData<Boolean> getCurrentMotion() {
        return currentMotion;
    }

    public boolean isMoving() {
        return moving;
    }
}
