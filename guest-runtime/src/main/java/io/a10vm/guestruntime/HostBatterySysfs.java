package io.a10vm.guestruntime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Android 9 only: materialise the host battery snapshot as the sysfs files
 * healthd reads.
 *
 * Android 10 reads the host battery over the project's health HAL
 * ({@link HostBatteryBridge} + the guest health HAL).  The API 28 runtime does
 * not currently ship its project health HAL, so its fallback healthd reads
 * /sys/class/power_supply, and the guest path layer leaves /sys on the host
 * sysfs, which an untrusted app cannot read.
 * So this class writes the same values to
 * {@code <stateRoot>/battery-sysfs/class/power_supply/battery/}, and
 * liba9vm-healthd-sysfs.so redirects only that prefix inside the guest.
 *
 * The files use the units healthd expects: capacity 0..100, status text,
 * voltage in µV, temperature in 0.1 °C, current in µA, charge counter in µAh.
 */
public final class HostBatterySysfs implements AutoCloseable {
    private final Context context;
    private final BatteryManager manager;
    private final File directory;
    // AOSP 9's BatteryMonitor decides "is it charging" from a supply whose type
    // is USB/AC/Wireless and whose "online" file is 1.  A single battery/
    // directory gives capacity but leaves plugged=0, which is why the status bar
    // showed a percentage with no charging state.  Mirror the host charger as a
    // separate usb/ supply so BatteryMonitor::init() picks it up.
    private final File chargerDirectory;
    private Intent battery;
    private boolean registered;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            synchronized (HostBatterySysfs.this) {
                battery = intent;
                publish();
            }
        }
    };

    public HostBatterySysfs(Context context, File stateRoot) {
        this.context = context.getApplicationContext();
        manager = context.getSystemService(BatteryManager.class);
        directory = new File(stateRoot, "battery-sysfs/class/power_supply/battery");
        chargerDirectory = new File(stateRoot, "battery-sysfs/class/power_supply/usb");
    }

    public synchronized void start() {
        directory.mkdirs();
        chargerDirectory.mkdirs();
        // ACTION_BATTERY_CHANGED is sticky, so registerReceiver returns the
        // current snapshot immediately instead of waiting for the next change.
        battery = context.registerReceiver(receiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        registered = true;
        publish();
    }

    private void publish() {
        Intent snapshot = battery;
        if (snapshot == null) return;
        directory.mkdirs();
        int level = snapshot.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = snapshot.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int capacity = scale > 0 ? (int) Math.max(0, Math.min(100, (long) level * 100 / scale)) : 0;
        String statusText = status(snapshot.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN));
        String healthText = health(snapshot.getIntExtra(BatteryManager.EXTRA_HEALTH,
                BatteryManager.BATTERY_HEALTH_UNKNOWN));
        // AOSP 9's BatteryMonitor::init() walks /sys/class/power_supply and keeps
        // a supply only when its "type" file reads "Battery".  Without this file
        // the whole directory is skipped and healthd reports capacity 0.
        write("type", "Battery");
        write("capacity", Integer.toString(capacity));
        write("status", statusText);
        write("health", healthText);
        int plugged = snapshot.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        write("online", plugged != 0 ? "1" : "0");
        // The charger is a separate supply in AOSP 9.  BatteryMonitor::update()
        // reads usb/online and folds it into plugged; without it BatteryService
        // sees a battery that is never connected to a charger, which is exactly
        // the "percentage only, no charging state" symptom.
        chargerDirectory.mkdirs();
        write(chargerDirectory, "type", "USB");
        write(chargerDirectory, "online", plugged != 0 ? "1" : "0");
        write(chargerDirectory, "present", "1");
        write("present", snapshot.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) ? "1" : "0");
        long voltage = snapshot.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) * 1000L;
        write("voltage_now", Long.toString(voltage));
        int temperature = snapshot.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        write("temperature", Integer.toString(temperature));
        int current = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        write("current_now", Integer.toString(current));
        int charge = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        write("charge_counter", Integer.toString(charge));
        String technology = snapshot.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
        if (technology == null || technology.isEmpty()) technology = "Li-ion";
        write("technology", technology);
        // Android 9's BatteryMonitor reads the per-property files above, but the
        // image also links the uevent collector and some 9.0 builds parse
        // /sys/class/power_supply/<name>/uevent instead.  Publishing the same
        // snapshot in uevent form costs one small file and removes the whole
        // "which reader does this image use" question.
        StringBuilder uevent = new StringBuilder();
        uevent.append("POWER_SUPPLY_NAME=battery\n");
        uevent.append("POWER_SUPPLY_TYPE=Battery\n");
        uevent.append("POWER_SUPPLY_STATUS=").append(statusText).append('\n');
        uevent.append("POWER_SUPPLY_PRESENT=").append(snapshot.getBooleanExtra(
                BatteryManager.EXTRA_PRESENT, true) ? "1" : "0").append('\n');
        uevent.append("POWER_SUPPLY_CAPACITY=").append(capacity).append('\n');
        uevent.append("POWER_SUPPLY_VOLTAGE_NOW=").append(voltage).append('\n');
        uevent.append("POWER_SUPPLY_TEMP=").append(temperature).append('\n');
        uevent.append("POWER_SUPPLY_CURRENT_NOW=").append(current).append('\n');
        uevent.append("POWER_SUPPLY_CHARGE_COUNTER=").append(charge).append('\n');
        uevent.append("POWER_SUPPLY_TECHNOLOGY=").append(technology).append('\n');
        uevent.append("POWER_SUPPLY_HEALTH=").append(healthText).append('\n');
        uevent.append("POWER_SUPPLY_ONLINE=").append(plugged != 0 ? "1" : "0").append('\n');
        write("uevent", uevent.toString().trim());
    }

    private void write(String name, String value) {
        write(directory, name, value);
    }

    private void write(File dir, String name, String value) {
        try (FileOutputStream stream = new FileOutputStream(new File(dir, name))) {
            stream.write((value + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            android.util.Log.w("A10VM", "Battery sysfs " + name + ": " + failure.getMessage());
        }
    }

    private static String status(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "Full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Not charging";
            default: return "Unknown";
        }
    }

    private static String health(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Over voltage";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Cold";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "Unspecified failure";
            default: return "Unknown";
        }
    }

    @Override public synchronized void close() {
        if (registered) {
            context.unregisterReceiver(receiver);
            registered = false;
        }
        battery = null;
    }
}
