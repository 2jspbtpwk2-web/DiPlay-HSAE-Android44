package local.airuize.diplayprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Compiled against API 18, with no AndroidX, native libraries or accessory identity. */
public final class ProbeActivity extends Activity {
    private static final UUID IAP2 = UUID.fromString("00000000-deca-fade-deca-deafdecacafe");
    private final StringBuilder report = new StringBuilder();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean destroyed;
    private volatile BluetoothSocket connecting;
    private volatile Surface surface;
    private TextView output;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(12, 8, 12, 8);
        TextView title = new TextView(this);
        title.setText("Android 4.3 无线 CarPlay 适配检测\n这是检测工具，尚不能显示 CarPlay。报告不记录热点密码或蓝牙地址。");
        title.setTextSize(18);
        layout.addView(title);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        addButton(buttons, "检测系统", new Runnable() { public void run() { scan(); } });
        addButton(buttons, "测试解码", new Runnable() { public void run() { decodeTest(); } });
        addButton(buttons, "测试 iPhone 蓝牙", new Runnable() { public void run() { choosePhone(); } });
        layout.addView(buttons);
        LinearLayout wirelessActions = new LinearLayout(this);
        addButton(wirelessActions, "开启车机热点", new Runnable() { public void run() { enableHotspot(); } });
        addButton(wirelessActions, "恢复 Wi-Fi", new Runnable() { public void run() { restoreWifi(); } });
        layout.addView(wirelessActions);
        LinearLayout actions = new LinearLayout(this);
        addButton(actions, "Wi-Fi 设置", new Runnable() { public void run() {
            try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
            catch (Exception e) { line("Wi-Fi 设置入口: " + error(e)); }
        } });
        addButton(actions, "保存报告", new Runnable() { public void run() { save(); } });
        addButton(actions, "复制报告", new Runnable() { public void run() {
            ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("DiPlay compatibility", text()));
            Toast.makeText(ProbeActivity.this, "报告已复制", Toast.LENGTH_SHORT).show();
        } });
        layout.addView(actions);
        SurfaceView video = new SurfaceView(this);
        layout.addView(video, new LinearLayout.LayoutParams(-1, 160));
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { surface = h.getSurface(); }
            public void surfaceChanged(SurfaceHolder h, int f, int w, int v) { surface = h.getSurface(); }
            public void surfaceDestroyed(SurfaceHolder h) { surface = null; }
        });
        output = new TextView(this);
        output.setTextSize(14);
        output.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        scan();
    }

    private void addButton(LinearLayout row, String label, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void work(final Runnable task) {
        if (!running.compareAndSet(false, true)) {
            Toast.makeText(this, "检测正在运行", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(new Runnable() { public void run() {
            try { task.run(); } catch (Throwable e) { line("检测异常: " + error(e)); }
            finally { running.set(false); }
        } }, "diplay-probe").start();
    }

    private void scan() { work(new Runnable() { public void run() {
        line("\n=== 系统和无线通道检测 ===");
        line("tool=0.2; Android=" + Build.VERSION.RELEASE + "; sdk=" + Build.VERSION.SDK_INT);
        line("model=" + Build.MODEL + "; hardware=" + Build.HARDWARE + "; abi=" + Build.CPU_ABI);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        line("显示=" + dm.widthPixels + "x" + dm.heightPixels + "; density=" + dm.densityDpi);
        line("Wi-Fi feature=" + getPackageManager().hasSystemFeature("android.hardware.wifi"));
        line("Bluetooth feature=" + getPackageManager().hasSystemFeature("android.hardware.bluetooth"));
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            line("Android BluetoothAdapter=" + (bt == null ? "不存在" : "存在; enabled=" + bt.isEnabled()));
            if (bt != null && bt.isEnabled()) {
                line("已配对设备数量=" + bt.getBondedDevices().size());
                BluetoothServerSocket socket = bt.listenUsingRfcommWithServiceRecord("DiPlay capability probe", IAP2);
                try { line("本地 RFCOMM 创建=PASS（尚未验证 iPhone 通信）"); }
                finally { socket.close(); }
            }
        } catch (Throwable e) { line("本地 RFCOMM 创建=FAIL: " + error(e)); }
        try {
            WifiManager wifi = (WifiManager)getSystemService(WIFI_SERVICE);
            line("Wi-Fi station enabled=" + wifi.isWifiEnabled());
            try {
                Object state = wifi.getClass().getMethod("getWifiApState").invoke(wifi);
                line("热点状态=" + state + "（常见 13 为开启，需结合接口判断）");
                WifiConfiguration conf = (WifiConfiguration)wifi.getClass().getMethod("getWifiApConfiguration").invoke(wifi);
                line("热点配置可读=" + (conf != null));
                if (conf != null) {
                    line("热点 SSID 已设置=" + (conf.SSID != null && conf.SSID.length() > 0));
                    line("热点密码已设置=" + (conf.preSharedKey != null && conf.preSharedKey.length() > 0));
                    line("热点 KeyManagement=" + conf.allowedKeyManagement);
                    try { Field f = conf.getClass().getField("apChannel"); line("热点信道=" + f.getInt(conf)); }
                    catch (Exception e) { line("热点信道=该固件未公开 apChannel"); }
                }
            } catch (Exception e) { line("旧版热点反射接口不可读: " + error(e)); }
        } catch (Throwable e) { line("Wi-Fi configuration=FAIL: " + error(e)); }
        LegacyNetworkProbe.run(ProbeActivity.this, new LegacyNetworkProbe.Reporter() {
            public void line(String value) { ProbeActivity.this.line(value); }
        });
        try {
            for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (type.equals("video/avc") || type.equals("audio/mp4a-latm"))
                        line("decoder=" + info.getName() + "; mime=" + type);
                }
            }
        } catch (Throwable e) { line("解码器枚举=FAIL: " + error(e)); }
        line("下一步：先让 Wi-Fi 建立实际连接或开启车机热点，再检测系统并保存报告。v0.2 会记录连接状态、IP 和组播失败的具体步骤。");
    } }); }

    /** Uses the already-saved hotspot configuration; never invents or logs credentials. */
    private void enableHotspot() { work(new Runnable() { public void run() {
        WifiManager wifi = (WifiManager)getSystemService(WIFI_SERVICE);
        boolean wifiDisabledHere = false;
        boolean enablingRequested = false;
        try {
            final WifiConfiguration conf = (WifiConfiguration)wifi.getClass()
                .getMethod("getWifiApConfiguration").invoke(wifi);
            if (conf == null || conf.SSID == null || conf.SSID.length() == 0
                || conf.preSharedKey == null || conf.preSharedKey.length() < 8
                || conf.preSharedKey.length() > 63 || !conf.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
                line("未找到可用的已保存 WPA-PSK 热点配置，请先在车机设置里配置热点。未改变 Wi-Fi 状态。");
                return;
            }
            java.lang.reflect.Method setAp = wifi.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            int current = ((Number)wifi.getClass().getMethod("getWifiApState").invoke(wifi)).intValue();
            if (current != 13) {
                boolean previousWifi = wifi.isWifiEnabled();
                getSharedPreferences("hotspot-probe", MODE_PRIVATE).edit()
                    .putBoolean("previousWifiEnabled", previousWifi).putBoolean("restoreAvailable", true).commit();
                if (previousWifi) {
                    if (!wifi.setWifiEnabled(false)) throw new IllegalStateException("Wi-Fi disable request rejected");
                    wifiDisabledHere = true;
                    long stopDeadline = System.nanoTime() + 8000000000L;
                    while (!destroyed && wifi.getWifiState() != WifiManager.WIFI_STATE_DISABLED
                        && System.nanoTime() < stopDeadline) Thread.sleep(150);
                    if (destroyed) throw new InterruptedException();
                    if (wifi.getWifiState() != WifiManager.WIFI_STATE_DISABLED)
                        throw new IllegalStateException("Wi-Fi did not stop");
                }
                enablingRequested = true;
                if (!Boolean.TRUE.equals(setAp.invoke(wifi, conf, true))) throw new IllegalStateException("Hotspot request rejected");
                long deadline = System.nanoTime() + 12000000000L;
                int state;
                do {
                    state = ((Number)wifi.getClass().getMethod("getWifiApState").invoke(wifi)).intValue();
                    if (state == 13 || state == 14 || destroyed) break;
                    Thread.sleep(200);
                } while (System.nanoTime() < deadline);
                if (destroyed) throw new InterruptedException();
                if (state != 13) throw new IllegalStateException("Hotspot did not reach enabled state");
            }
            line("车机热点=ENABLED；请关闭 iPhone 的个人热点，让 iPhone 加入车机热点，再点击检测系统。");
            if (!destroyed) runOnUiThread(new Runnable() { public void run() {
                if (destroyed) return;
                new AlertDialog.Builder(ProbeActivity.this).setTitle("让 iPhone 加入车机热点")
                    .setMessage("热点名称：" + conf.SSID + "\n密码：" + conf.preSharedKey
                        + "\n\n请先关闭 iPhone 个人热点，再在 iPhone 的 Wi-Fi 设置中加入此热点。热点名称和密码不会写入检测报告。")
                    .setPositiveButton("知道了", null).show();
            } });
            LegacyNetworkProbe.run(ProbeActivity.this, new LegacyNetworkProbe.Reporter() {
                public void line(String value) { ProbeActivity.this.line(value); }
            });
        } catch (Exception e) {
            line("开启车机热点=FAIL: " + error(e));
            // Undo only the changes made by this request, including a late-starting AP.
            boolean apStopped = !enablingRequested;
            if (enablingRequested) {
                try {
                    apStopped = Boolean.TRUE.equals(wifi.getClass()
                        .getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class).invoke(wifi, null, false));
                } catch (Exception rollback) { line("停止热点回滚=FAIL: " + error(rollback)); }
            }
            if (wifiDisabledHere && apStopped) {
                try { line("恢复原 Wi-Fi 请求=" + wifi.setWifiEnabled(true)); }
                catch (Exception rollback) { line("恢复原 Wi-Fi=FAIL: " + error(rollback)); }
            }
            line("可点击恢复 Wi-Fi，或通过车机设置恢复原连接。");
        }
    } }); }

    private void restoreWifi() { work(new Runnable() { public void run() {
        try {
            WifiManager wifi = (WifiManager)getSystemService(WIFI_SERVICE);
            if (!getSharedPreferences("hotspot-probe", MODE_PRIVATE).getBoolean("restoreAvailable", false)) {
                line("没有由本工具保存的 Wi-Fi 状态，请使用 Wi-Fi 设置入口。"); return;
            }
            boolean previous = getSharedPreferences("hotspot-probe", MODE_PRIVATE).getBoolean("previousWifiEnabled", true);
            Object result = wifi.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class)
                .invoke(wifi, null, false);
            if (!Boolean.TRUE.equals(result)) throw new IllegalStateException("Hotspot stop request rejected");
            long deadline = System.nanoTime() + 10000000000L;
            int state;
            do {
                state = ((Number)wifi.getClass().getMethod("getWifiApState").invoke(wifi)).intValue();
                if (state == 11 || destroyed) break;
                Thread.sleep(200);
            } while (System.nanoTime() < deadline);
            if (state != 11) throw new IllegalStateException("Hotspot did not stop");
            if (!wifi.setWifiEnabled(previous)) throw new IllegalStateException("Wi-Fi restore request rejected");
            getSharedPreferences("hotspot-probe", MODE_PRIVATE).edit().remove("restoreAvailable").commit();
            line("已请求恢复原 Wi-Fi 开关状态=" + previous + "；手机热点的重新连接请在 Wi-Fi 设置中确认。");
        } catch (Exception e) { line("恢复 Wi-Fi=FAIL: " + error(e)); }
    } }); }

    private void choosePhone() {
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null || !bt.isEnabled()) {
                line("请先在车机设置中开启 Android 蓝牙并配对 iPhone。"); return;
            }
            final List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>(bt.getBondedDevices());
            if (devices.isEmpty()) { line("Android 蓝牙没有已配对设备，专用蓝牙电话模块不一定支持应用 RFCOMM。"); return; }
            String[] names = new String[devices.size()];
            for (int i = 0; i < names.length; i++) {
                String name = devices.get(i).getName();
                names[i] = name == null ? "设备 " + (i + 1) : name;
            }
            new AlertDialog.Builder(this).setTitle("选择已配对的 iPhone（手机保持解锁）")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) { connectPhone(devices.get(which)); }
                }).setNegativeButton("取消", null).show();
        } catch (Throwable e) { line("无法选择蓝牙设备: " + error(e)); }
    }

    private void connectPhone(final BluetoothDevice phone) { work(new Runnable() { public void run() {
        line("\n=== iPhone RFCOMM 连接测试（最长 12 秒，无协议数据发送） ===");
        BluetoothSocket socket = null;
        final AtomicBoolean timedOut = new AtomicBoolean();
        Thread timeout = null;
        try {
            socket = phone.createRfcommSocketToServiceRecord(IAP2);
            connecting = socket;
            final BluetoothSocket owned = socket;
            timeout = new Thread(new Runnable() { public void run() {
                try { Thread.sleep(12000); timedOut.set(true); owned.close(); }
                catch (InterruptedException expected) {} catch (Exception ignored) {}
            } }, "rfcomm-timeout");
            timeout.start();
            socket.connect();
            line("iPhone iAP2 UUID 的 RFCOMM connect=PASS（未验证 MFi/CarPlay）");
        } catch (Throwable e) { line("iPhone RFCOMM connect=FAIL; timeout=" + timedOut.get() + "; " + error(e)); }
        finally {
            if (timeout != null) timeout.interrupt();
            connecting = null;
            if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        }
        line("FAIL 也可能是手机状态、配对或 iAP2 服务问题，不能单凭一次失败判定硬件不支持。");
    } }); }

    private void decodeTest() {
        final Surface target = surface;
        if (target == null || !target.isValid()) { line("显示 Surface 尚未就绪"); return; }
        work(new Runnable() { public void run() {
            line("\n=== H.264 800x480/30fps Surface 实际解码 ===");
            List<String> decoders = new ArrayList<String>();
            for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) if (type.equals("video/avc")) decoders.add(info.getName());
            }
            for (String name : decoders) {
                if (destroyed || !target.isValid()) break;
                decodeOne(name, target);
            }
        } });
    }

    private void decodeOne(String name, Surface target) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        AssetFileDescriptor fd = null;
        boolean started = false;
        try {
            fd = getAssets().openFd("h264-test.mp4");
            extractor.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++)
                if ("video/avc".equals(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME))) { track = i; break; }
            if (track < 0) throw new IllegalStateException("Test clip has no AVC track");
            extractor.selectTrack(track);
            codec = MediaCodec.createByCodecName(name);
            codec.configure(extractor.getTrackFormat(track), target, null, 0);
            codec.start();
            started = true;
            ByteBuffer[] inputs = codec.getInputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            int frames = 0;
            long begin = System.nanoTime(), deadline = begin + 12000000000L;
            while (!destroyed && target.isValid() && !outputDone && System.nanoTime() < deadline) {
                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        inputs[index].clear();
                        int size = extractor.readSampleData(inputs[index], 0);
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index >= 0) {
                    boolean render = info.size > 0 || (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                    if (render) frames++;
                    codec.releaseOutputBuffer(index, render);
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                }
            }
            long elapsedMs = (System.nanoTime() - begin) / 1000000L;
            line("decoder=" + name + "; " + (outputDone && frames >= 55 ? "PASS" : "INCOMPLETE")
                + "; renderedBuffers=" + frames + "; elapsedMs=" + elapsedMs + "; eos=" + outputDone);
            line("请确认检测画面是否出现彩色测试图；此测试不代表持续无线 CarPlay 性能。");
        } catch (Throwable e) { line("decoder=" + name + "; FAIL: " + error(e)); }
        finally {
            if (codec != null) {
                if (started) try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            extractor.release();
            if (fd != null) try { fd.close(); } catch (Exception ignored) {}
        }
    }

    private void save() {
        try {
            File directory = new File(Environment.getExternalStorageDirectory(), "DiPlayProbe");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建报告目录");
            File destination = new File(directory, "report-" + System.currentTimeMillis() + ".txt");
            FileOutputStream stream = new FileOutputStream(destination);
            try { stream.write(text().getBytes("UTF-8")); } finally { stream.close(); }
            line("报告已保存：" + destination.getAbsolutePath());
        } catch (Exception e) { line("保存失败，可使用复制报告：" + error(e)); }
    }

    private synchronized String text() { return report.toString(); }
    private void line(String value) {
        synchronized (this) { report.append(value).append('\n'); }
        if (!destroyed) runOnUiThread(new Runnable() { public void run() { if (!destroyed) output.setText(text()); } });
    }
    private static String error(Throwable e) {
        return LegacyNetworkProbe.error(e);
    }
    @Override public void onDestroy() {
        destroyed = true;
        BluetoothSocket socket = connecting;
        if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
