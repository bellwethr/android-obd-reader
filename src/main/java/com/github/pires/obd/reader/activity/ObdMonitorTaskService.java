package com.github.pires.obd.reader.activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.github.pires.obd.commands.ObdCommand;
import com.github.pires.obd.commands.fuel.FuelLevelCommand;
import com.github.pires.obd.reader.R;
import com.github.pires.obd.reader.io.AbstractGatewayService;
import com.github.pires.obd.reader.io.ObdCommandJob;
import com.github.pires.obd.reader.io.ObdGatewayService;
import com.github.pires.obd.reader.io.ObdProgressListener;
import com.google.android.gms.gcm.GcmNetworkManager;
import com.google.android.gms.gcm.GcmTaskService;
import com.google.android.gms.gcm.PeriodicTask;
import com.google.android.gms.gcm.Task;
import com.google.android.gms.gcm.TaskParams;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static com.github.pires.obd.reader.activity.MainActivity.LookUpCommand;

public class ObdMonitorTaskService extends GcmTaskService implements ObdProgressListener {

    private static final String TAG = ObdMonitorTaskService.class.getSimpleName();

    private boolean isServiceBound;
    private AbstractGatewayService service;
    public Map<String, String> commandResult = new HashMap<String, String>();


    private ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder binder) {
            Log.d(TAG, className.toString() + " service is bound");
            isServiceBound = true;
            service = ((AbstractGatewayService.AbstractGatewayServiceBinder) binder).getService();
            service.setContext(ObdMonitorTaskService.this);
            Log.d(TAG, "Starting live data");
            try {
                service.startService();
            } catch (IOException ioe) {
                Log.e(TAG, "Failure Starting live data");
                doUnbindService();
            }
        }

        @Override
        protected Object clone() throws CloneNotSupportedException {
            return super.clone();
        }

        // This method is *only* called when the connection to the service is lost unexpectedly
        // and *not* when the client unbinds (http://developer.android.com/guide/components/bound-services.html)
        // So the isServiceBound attribute should also be set to false when we unbind from the service.
        @Override
        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, className.toString() + " service is unbound");
            isServiceBound = false;
        }
    };

    private final Runnable mQueueCommands = new Runnable() {
        public void run() {
            if (service != null && service.isRunning() && service.queueEmpty()) {
                queueCommands();
                Map<String, String> temp = new HashMap<String, String>();
                temp.putAll(commandResult);
                commandResult.clear();
            }
        }
    };

    @Override
    public void onInitializeTasks() {
        super.onInitializeTasks();
    }

    @Override
    public int onRunTask(TaskParams taskParams) {
        Log.v(TAG, "starting task");
        doBindService();
        //Looper.prepare();
        Log.v(TAG, "posting commands");

        new Handler(getMainLooper()).post(mQueueCommands);
        Log.v(TAG, "posted commands");
//        Handler h = new Handler(getMainLooper());
//        h.post(new Runnable() {
//            @Override
//            public void run() {
//                Toast.makeText(ObdMonitorTaskService.this, "Executed service", Toast.LENGTH_LONG).show();
//            }
//        });
        //getMainLooper().quit();
        return GcmNetworkManager.RESULT_SUCCESS;
    }

    public static void schedule(Context context) {
        try {
            PeriodicTask periodic = new PeriodicTask.Builder()
                    .setService(ObdMonitorTaskService.class)
                    .setPeriod(10)
                    .setFlex(1)
                    .setTag(TAG)
                    .setPersisted(false)
                    .setUpdateCurrent(true)
                    .setRequiredNetwork(Task.NETWORK_STATE_ANY)
                    .setRequiresCharging(false)
                    .build();
            GcmNetworkManager.getInstance(context).schedule(periodic);
            Log.v(TAG, "repeating task scheduled");
        } catch (Exception e) {
            Log.e(TAG, "scheduling failed");
            e.printStackTrace();
        }
    }

    private void queueCommands() {
        if (isServiceBound) {
            for (ObdCommand Command : getCommands()) {
                service.queueJob(new ObdCommandJob(Command));
            }
        }
    }


    private void doBindService() {
        if (!isServiceBound) {
            Log.d(TAG, "Binding OBD service..");
            Intent serviceIntent = new Intent(this, ObdGatewayService.class);
            bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE);
        }
    }

    private void doUnbindService() {
        if (isServiceBound) {
            if (service.isRunning()) {
                service.stopService();
//                if (preRequisites)
//                    btStatusTextView.setText(getString(R.string.status_bluetooth_ok));
            }
            Log.d(TAG, "Unbinding OBD service..");
            unbindService(serviceConn);
            isServiceBound = false;
//            obdStatusTextView.setText(getString(R.string.status_obd_disconnected));
        }
    }

    public ArrayList<ObdCommand> getCommands() {
        ArrayList<ObdCommand> cmds = new ArrayList<>();
        cmds.add(new FuelLevelCommand());
        return cmds;
    }

    public void stateUpdate(final ObdCommandJob job) {
        final String cmdName = job.getCommand().getName();
        final String cmdID = LookUpCommand(cmdName);
        Looper.prepare();
        Handler h = new Handler(getMainLooper());
//        h.post(new Runnable() {
//            @Override
//            public void run() {
//                Toast.makeText(ObdMonitorTaskService.this, "Executed service", Toast.LENGTH_LONG).show();
//            }
//        });
        if (job.getState().equals(ObdCommandJob.ObdCommandJobState.EXECUTION_ERROR)) {
            final String cmdResult = job.getCommand().getResult();
            if (cmdResult != null && isServiceBound) {
                h.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ObdMonitorTaskService.this, cmdResult.toLowerCase(), Toast.LENGTH_LONG).show();
            }
        });
            }
        } else if (job.getState().equals(ObdCommandJob.ObdCommandJobState.NOT_SUPPORTED)) {
            final String cmdResult = getString(R.string.status_obd_no_support);
            h.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(ObdMonitorTaskService.this, cmdResult.toLowerCase(), Toast.LENGTH_LONG).show();
                }
            });
        } else {
            final String cmdResult = job.getCommand().getFormattedResult();
            if (isServiceBound)
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(ObdMonitorTaskService.this, cmdResult.toLowerCase(), Toast.LENGTH_LONG).show();
                    }
                });
        }
        Log.v(TAG, "stateUpdate completed");

    }


}