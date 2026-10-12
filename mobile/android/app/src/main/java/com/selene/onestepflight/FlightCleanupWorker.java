package com.selene.onestepflight;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
public class FlightCleanupWorker extends Worker {
    public FlightCleanupWorker(Context context,WorkerParameters params) {super(context,params);}
    @NonNull @Override public Result doWork() {FlightCoordinator.get(getApplicationContext()).sweepShares();return Result.success();}
}
