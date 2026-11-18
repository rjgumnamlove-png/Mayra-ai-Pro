#!/bin/bash
# Native advanced ad support. Runs after "npx cap add android".
# 1) reports whether the installed AdMob plugin supports the rewarded interstitial
# 2) adds the Google Mobile Ads library to the app, so MayraNativeAd.java can compile
# 3) leaves a marker only when step 2 really worked; patch-manifest.js includes the Java file only then
set -e
if grep -rq "prepareRewardInterstitialAd" node_modules/@capacitor-community/admob 2>/dev/null; then
  echo "REWARDED INTERSTITIAL: supported by the installed AdMob plugin"
else
  echo "REWARDED INTERSTITIAL: NOT found in the installed AdMob plugin (standard rewarded video still works)"
fi
rm -f android/.native_ad_ok
sed -i "/^dependencies {/a\    implementation 'com.google.android.gms:play-services-ads:23.6.0'" android/app/build.gradle
if grep -q "play-services-ads" android/app/build.gradle; then
  touch android/.native_ad_ok
  echo "NATIVE AD: Google ads library added"
else
  echo "NATIVE AD: could not add the ads library; native ad is skipped"
fi
