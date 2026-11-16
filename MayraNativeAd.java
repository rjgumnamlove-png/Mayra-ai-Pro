package com.rjbilal.mayraai;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdLoader;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.nativead.MediaView;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdView;

// Shows a Google "native advanced" ad in a card on top of the app.
// The page calls: Capacitor.Plugins.MayraNativeAd.show({ adId: "ca-app-pub-..." })
@CapacitorPlugin(name = "MayraNativeAd")
public class MayraNativeAd extends Plugin {

    private boolean showing = false;

    @PluginMethod
    public void show(PluginCall call) {
        final String adId = call.getString("adId", "");
        final Activity activity = getActivity();
        if (activity == null || adId == null || adId.isEmpty()) {
            call.reject("no activity or ad id");
            return;
        }
        if (showing) {
            call.resolve();
            return;
        }
        showing = true;
        activity.runOnUiThread(() -> {
            AdLoader loader = new AdLoader.Builder(activity, adId)
                    .forNativeAd(nativeAd -> {
                        if (activity.isFinishing()) {
                            nativeAd.destroy();
                            showing = false;
                            return;
                        }
                        showCard(activity, nativeAd);
                    })
                    .withAdListener(new AdListener() {
                        @Override
                        public void onAdFailedToLoad(LoadAdError error) {
                            showing = false;
                        }
                    })
                    .build();
            loader.loadAd(new AdRequest.Builder().build());
        });
        call.resolve();
    }

    private void showCard(Activity activity, NativeAd nativeAd) {
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        NativeAdView adView = new NativeAdView(activity);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(new ColorDrawable(Color.parseColor("#14102A")));

        TextView label = new TextView(activity);
        label.setText("Ad · اشتہار");
        label.setTextColor(Color.parseColor("#BDB3E6"));
        label.setTextSize(12);
        card.addView(label);

        MediaView media = new MediaView(activity);
        LinearLayout.LayoutParams mediaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (180 * density));
        mediaLp.topMargin = (int) (8 * density);
        card.addView(media, mediaLp);

        TextView headline = new TextView(activity);
        headline.setTextColor(Color.WHITE);
        headline.setTextSize(18);
        headline.setTypeface(null, android.graphics.Typeface.BOLD);
        headline.setPadding(0, (int) (10 * density), 0, 0);
        card.addView(headline);

        TextView body = new TextView(activity);
        body.setTextColor(Color.parseColor("#DDD8F0"));
        body.setTextSize(14);
        card.addView(body);

        Button cta = new Button(activity);
        cta.setAllCaps(false);
        cta.setTextColor(Color.WHITE);
        cta.setBackgroundColor(Color.parseColor("#7C3AED"));
        LinearLayout.LayoutParams ctaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ctaLp.topMargin = (int) (12 * density);
        card.addView(cta, ctaLp);

        Button close = new Button(activity);
        close.setAllCaps(false);
        close.setText("بند کریں · Close");
        close.setTextColor(Color.WHITE);
        close.setBackgroundColor(Color.parseColor("#2A2440"));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = (int) (8 * density);
        card.addView(close, closeLp);

        // register the views with the ad, so taps and impressions are counted correctly
        adView.addView(card);
        adView.setHeadlineView(headline);
        adView.setBodyView(body);
        adView.setCallToActionView(cta);
        adView.setMediaView(media);

        headline.setText(nativeAd.getHeadline() == null ? "" : nativeAd.getHeadline());
        if (nativeAd.getBody() != null) body.setText(nativeAd.getBody()); else body.setVisibility(View.GONE);
        if (nativeAd.getCallToAction() != null) cta.setText(nativeAd.getCallToAction()); else cta.setVisibility(View.GONE);
        if (nativeAd.getMediaContent() != null) media.setMediaContent(nativeAd.getMediaContent());
        adView.setNativeAd(nativeAd);

        FrameLayout wrap = new FrameLayout(activity);
        wrap.setBackgroundColor(Color.parseColor("#B0000000"));
        wrap.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        wrap.addView(adView, lp);

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(wrap);
        dialog.setCancelable(true);
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            showing = false;
            nativeAd.destroy();
        });
        dialog.show();
    }
}
