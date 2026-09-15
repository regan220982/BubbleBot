package com.regan.bubblebot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "data";
    private static final String CHANNEL = "bubblebot";
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private long lastAnalyze = 0;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Bubble Bot",NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        Notification n = new Notification.Builder(this, CHANNEL).setContentTitle("Bubble Bot running").setContentText("Local screen analysis is active").setSmallIcon(android.R.drawable.ic_media_play).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(7, n);
        if (projection == null) startCapture(intent);
        return START_NOT_STICKY;
    }

    private void startCapture(Intent intent) {
        int result = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (result != -1 && data != null) {
            MediaProjectionManager mpm = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(result, data);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = dm.widthPixels, h = dm.heightPixels;
            reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(r -> processLatest(r, w, h), null);
            display = projection.createVirtualDisplay("BubbleBotCapture", w, h, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
            projection.registerCallback(new MediaProjection.Callback() { @Override public void onStop() { stopSelf(); } }, null);
        }
    }

    private void processLatest(ImageReader r, int width, int height) {
        long now = System.currentTimeMillis();
        if (now - lastAnalyze < 65 || !busy.compareAndSet(false,true)) return;
        Image image = null;
        try {
            image = r.acquireLatestImage();
            if (image == null) return;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            Bitmap padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            buffer.rewind(); padded.copyPixelsFromBuffer(buffer);
            Bitmap bmp = Bitmap.createBitmap(padded, 0, 0, width, height);
            padded.recycle();
            lastAnalyze = now;
            GameAnalyzer.process(bmp, width, height);
            bmp.recycle();
        } catch (Throwable ignored) { }
        finally { if (image != null) image.close(); busy.set(false); }
    }

    @Override public void onDestroy() {
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
