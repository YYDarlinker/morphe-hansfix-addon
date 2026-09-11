package io.github.yydarlinker.hansfix.diagnostics;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Standalone native Preference, usable directly as the XML element's fully-qualified class. */
@SuppressWarnings("deprecation")
public final class CaptionDiagnosticsPreference extends Preference {
    public CaptionDiagnosticsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
        setKey("yydarlinker_caption_diagnostics");
        setTitle("字幕诊断（Caption diagnostics）");
        setSummary("默认关闭 · 仅内存 · 最多80条 · 15分钟自动停止");
    }

    @Override protected void onClick() {
        try { showMenu(); } catch (Throwable ignored) { }
    }

    private void showMenu() {
        final boolean wasRecording = CaptionDiagnosticsRuntime.isRecording();
        new AlertDialog.Builder(getContext())
                .setTitle("字幕诊断（Caption diagnostics）")
                .setItems(new CharSequence[] {
                    wasRecording ? "停止记录（保留报告）" : "开始记录（替换旧报告，15分钟）",
                    "查看 / 刷新报告", "复制报告到系统剪贴板", "清空并停止"
                }, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        try {
                            switch (which) {
                                case 0:
                                    // Use the displayed action, not a toggle that might start after expiry.
                                    if (wasRecording) CaptionDiagnosticsRuntime.stopRecording();
                                    else CaptionDiagnosticsRuntime.startRecording();
                                    showReport();
                                    break;
                                case 1: showReport(); break;
                                case 2: copyReport(); break;
                                case 3: CaptionDiagnosticsRuntime.clearReport(); showReport(); break;
                                default: break;
                            }
                        } catch (Throwable ignored) { }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showReport() {
        final TextView text = new TextView(getContext());
        text.setText(CaptionDiagnosticsRuntime.report());
        text.setTextSize(13);
        // No automatic copying, persistence, sharing intent, or text selection export.
        int pad = (int) (16 * getContext().getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(getContext());
        scroll.addView(text);
        final AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle("字幕诊断报告")
                .setView(scroll)
                .setPositiveButton("刷新", null)
                .setNeutralButton("复制", null)
                .setNegativeButton("关闭", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignoredDialog) {
                try {
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View view) {
                            try { text.setText(CaptionDiagnosticsRuntime.report()); } catch (Throwable ignored) { }
                        }
                    });
                    dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View view) {
                            try { copyReport(); } catch (Throwable ignored) { }
                        }
                    });
                } catch (Throwable ignored) { }
            }
        });
        dialog.show();
    }

    /** This method is reachable only from an explicit copy-button/menu-item click. */
    private void copyReport() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(ClipData.newPlainText("字幕诊断", CaptionDiagnosticsRuntime.report()));
            Toast.makeText(getContext(), "已复制；系统或其他应用可能读取剪贴板。清空诊断不会清空剪贴板。", Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) { }
    }
}
