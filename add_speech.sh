#!/bin/bash
# Adds the free offline speech model (Vosk, English, small) and its Android library to the build.
# If anything here fails, the build still continues without the background wake (safe fallback).
set -e
MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
ASSETS="android/app/src/main/assets/model-en-us"
rm -rf /tmp/vosk && mkdir -p /tmp/vosk
curl -L -s -f -o /tmp/vosk/model.zip "$MODEL_URL"
unzip -q -o /tmp/vosk/model.zip -d /tmp/vosk
mkdir -p "$ASSETS"
cp -r /tmp/vosk/vosk-model-small-en-us-0.15/* "$ASSETS/"
# Vosk Android library + JNA (needed by Vosk)
sed -i "/^dependencies {/a\    implementation 'com.alphacephei:vosk-android:0.3.47'\n    implementation 'net.java.dev.jna:jna:5.13.0@aar'" android/app/build.gradle
echo "SPEECH ADD-ON: model and Vosk library added"
