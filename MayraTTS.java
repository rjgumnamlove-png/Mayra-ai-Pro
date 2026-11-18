package com.rjbilal.mayraai;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.Locale;
import java.util.Set;

// Reads the guide text aloud with the phone's own voice engine (free, works offline).
// It picks a female English voice when the phone has one.
// The page calls: Capacitor.Plugins.MayraTTS.speak({ text, rate, pitch }), .isSpeaking(), .stop()
@CapacitorPlugin(name = "MayraTTS")
public class MayraTTS extends Plugin {

    private TextToSpeech tts;
    private boolean ready = false;
    private String pendingText = null;
    private float pendingRate = 0.95f;
    private float pendingPitch = 1.08f;

    @PluginMethod
    public void speak(PluginCall call) {
        String text = call.getString("text", "");
        double rate = call.getDouble("rate", 0.95);
        double pitch = call.getDouble("pitch", 1.08);
        pendingRate = (float) rate;
        pendingPitch = (float) pitch;
        if (tts == null) {
            tts = new TextToSpeech(getContext(), status -> {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true;
                    chooseVoice();
                    if (pendingText != null) {
                        doSpeak(pendingText);
                        pendingText = null;
                    }
                }
            });
        }
        if (ready) {
            doSpeak(text);
        } else {
            pendingText = text;
        }
        call.resolve();
    }

    @PluginMethod
    public void isSpeaking(PluginCall call) {
        JSObject result = new JSObject();
        result.put("speaking", tts != null && tts.isSpeaking());
        call.resolve(result);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        pendingText = null;
        if (tts != null) {
            tts.stop();
        }
        call.resolve();
    }

    private void doSpeak(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        tts.setSpeechRate(pendingRate);
        tts.setPitch(pendingPitch);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mayra-guide");
    }

    private void chooseVoice() {
        if (tts == null) return;
        tts.setLanguage(Locale.US);
        Set<Voice> voices = null;
        try {
            voices = tts.getVoices();
        } catch (Exception e) {
            voices = null;
        }
        if (voices == null) return;
        Voice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Voice v : voices) {
            Locale l = v.getLocale();
            if (l == null || !"en".equalsIgnoreCase(l.getLanguage())) continue;
            String name = v.getName() == null ? "" : v.getName().toLowerCase(Locale.US);
            int score = 0;
            if (name.contains("female")) score += 4;
            if (name.contains("tpf") || name.contains("sfg")) score += 2;
            if (!v.isNetworkConnectionRequired()) score += 2;
            if (l.getCountry() != null && "US".equalsIgnoreCase(l.getCountry())) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = v;
            }
        }
        if (best != null) {
            tts.setVoice(best);
        }
    }

    @Override
    protected void handleOnDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.handleOnDestroy();
    }
}
