package com.mapcontrol.vehicle;

import android.content.Context;
import android.view.MotionEvent;

import java.util.function.BooleanSupplier;

/** Test derlemesinin boş yedeği: :app'teki vehicle/SunroofSwipeDispatcher.java; dokunuşu olduğu gibi geçirir, araca komut göndermez. */
public final class SunroofSwipeDispatcher {
    public interface Downstream {
        boolean dispatch(MotionEvent event);
    }

    public SunroofSwipeDispatcher(Context context, BooleanSupplier allowed) {}

    public boolean dispatch(MotionEvent event, Downstream downstream) {
        return downstream.dispatch(event);
    }
}
