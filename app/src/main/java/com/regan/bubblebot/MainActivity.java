package com.regan.bubblebot;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.opencv.android.OpenCVLoader;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_NOTIFY = 1002;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if (!OpenCVLoader.initLocal()) Toast.makeText(this, "OpenCV failed to load", Toast.LENGTH_LONG).show();
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    private void buildUi() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL); box.setPadding(40,50,40,40);
        TextView title = new TextView(this); title.setText("Bubble Bot"); title.setTextSize(28); box.addView(title);
        TextView info = new TextView(this);
        info.setText("1. Enable Bubble Bot in Accessibility.\n2. Press Start.\n3. Allow screen capture.\n4. Open the game.\n\nThe bot analyzes the board locally and shoots automatically.");
        info.setTextSize(17); box.addView(info);
        Button access = new Button(this); access.setText("1 — Enable Accessibility"); box.addView(access);
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        Button start = new Button(this); start.setText("2 — Start Bot / Screen Capture"); box.addView(start);
        start.setOnClickListener(v -> requestCapture());
        Button stop = new Button(this); stop.setText("STOP BOT"); box.addView(stop);
        stop.setOnClickListener(v -> stopService(new Intent(this, CaptureService.class)));
        setContentView(box);
    }

    private void requestCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent i = new Intent(this, CaptureService.class);
            i.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
            i.putExtra(CaptureService.EXTRA_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            Toast.makeText(this, "Bot running. Open the game.", Toast.LENGTH_SHORT).show();
        }
    }
}
