#!/bin/bash
# Native ads and mediation. Runs after "npx cap add android".
# 1) reports whether the installed AdMob plugin supports the rewarded interstitial
# 2) adds the Google Mobile Ads library and the Unity Ads mediation adapter to the app
# 3) leaves a marker only when step 2 really worked; patch-manifest.js includes the Java file only then
set -e
if grep -rq "prepareRewardInterstitialAd" node_modules/@capacitor-community/admob 2>/dev/null; then
  echo "REWARDED INTERSTITIAL: supported by the installed AdMob plugin"
else
  echo "REWARDED INTERSTITIAL: NOT found in the installed AdMob plugin (standard rewarded video still works)"
fi
rm -f android/.native_ad_ok
python3 - <<'PYEOF'
import re, sys
path = 'android/app/build.gradle'
WANT = [
    "implementation 'com.google.android.gms:play-services-ads:25.5.0'",
    "implementation 'com.unity3d.ads:unity-ads:4.21.0'",
    "implementation 'com.google.ads.mediation:unity:4.21.0.0'",
    "implementation 'com.google.ads.mediation:inmobi:11.5.1.0'",
    "implementation 'com.google.ads.mediation:chartboost:9.14.1.0'",
]
try:
    src = open(path, encoding='utf-8').read()
except Exception as e:
    print('NATIVE AD: could not read ' + path + ': ' + str(e))
    sys.exit(1)
out, added, done = [], 0, False
for line in src.split('\n'):
    out.append(line)
    if not done and re.match(r'^dependencies\s*\{', line):
        done = True
        for w in WANT:
            if w not in src:
                out.append('    ' + w)
                added += 1
new = '\n'.join(out)
if done and 'play-services-ads' in new:
    open(path, 'w', encoding='utf-8').write(new)
    open('android/.native_ad_ok', 'w').close()
    print('NATIVE AD: Google ads library and Unity mediation ' + ('added (' + str(added) + ' new lines)' if added else 'already present'))
else:
    print('NATIVE AD: could not add the ads library; native ad is skipped')
PYEOF
