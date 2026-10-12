package com.selene.onestepflight;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
public class FlightFreshnessWorker extends Worker {
    public FlightFreshnessWorker(Context context,WorkerParameters params) {super(context,params);}
    @NonNull @Override public Result doWork() {FlightCoordinator.get(getApplicationContext()).refreshCard(getInputData().getString("journeyKey"));return Result.success();}
}
