package com.mcai.ubuntudsu.core.dna;

import android.app.Activity;
import android.os.Bundle;

import com.mcai.ubuntudsu.R;

public class DnaBaseActivity extends Activity {
    private static final String TRANSPARENCY_KEY = "window_transparency";
    private boolean finishing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyTransparency();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyTransparency();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    public void finish() {
        if (finishing) return;
        finishing = true;
        super.finish();
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
    }

    private void applyTransparency() {
        int transparency = getSharedPreferences("settings", MODE_PRIVATE).getInt(TRANSPARENCY_KEY, 100);
        float alpha = transparency / 100f;
        getWindow().getAttributes().alpha = alpha;
    }
}
