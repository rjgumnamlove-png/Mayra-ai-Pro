// Adds the permissions and app-visibility entries Mayra needs to AndroidManifest.xml
const fs = require('fs');
const mode = process.argv[2] || 'native';
const f = 'android/app/src/main/AndroidManifest.xml';
let x = fs.readFileSync(f, 'utf8');
const perms = [
  'android.permission.INTERNET', 'android.permission.RECORD_AUDIO', 'android.permission.MODIFY_AUDIO_SETTINGS',
  'android.permission.CAMERA', 'android.permission.READ_CONTACTS', 'android.permission.CALL_PHONE',
  'android.permission.ACCESS_COARSE_LOCATION', 'android.permission.ACCESS_FINE_LOCATION',
  'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_MICROPHONE',
  'android.permission.POST_NOTIFICATIONS', 'android.permission.WAKE_LOCK',
  'android.permission.USE_EXACT_ALARM', 'android.permission.SCHEDULE_EXACT_ALARM',
  'android.permission.SEND_SMS', 'android.permission.READ_PHONE_STATE', 'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION', 'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS'
];
let add = '';
for (const p of perms) if (!x.includes('"' + p + '"')) add += '    <uses-permission android:name="' + p + '" />\n';
if (!x.includes('android.hardware.camera')) add += '    <uses-feature android:name="android.hardware.camera" android:required="false" />\n';
if (!x.includes('<queries>')) {
  add += `    <queries>
        <package android:name="com.whatsapp" />
        <package android:name="com.whatsapp.w4b" />
        <package android:name="com.google.android.youtube" />
        <package android:name="com.zhiliaoapp.musically" />
        <package android:name="com.ss.android.ugc.trill" />
        <package android:name="com.instagram.android" />
        <package android:name="com.snapchat.android" />
        <package android:name="com.facebook.katana" />
        <package android:name="com.mxtech.videoplayer.ad" />
        <package android:name="com.mxtech.videoplayer.pro" />
        <package android:name="com.android.chrome" />
        <package android:name="com.google.android.apps.maps" />
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="whatsapp" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="tel" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="https" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="tiktok" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="instagram" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="snapchat" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="fb" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="geo" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="google.navigation" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="sms" /></intent>
    </queries>
`;
}
const pkg = JSON.parse(fs.readFileSync('capacitor.config.json', 'utf8')).appId;
const dir = 'android/app/src/main/java/' + pkg.replace(/\./g, '/');
fs.mkdirSync(dir, { recursive: true });
const javaFiles = ['MayraContactsPlugin.java'];
let reg = '        registerPlugin(MayraContactsPlugin.class);\n';
if (mode === 'native' || mode === 'access') {
  const withMedia = mode === 'native';
  let svc = '    <service android:name=".MayraAccessService" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" android:exported="true"><intent-filter><action android:name="android.accessibilityservice.AccessibilityService" /></intent-filter><meta-data android:name="android.accessibilityservice" android:resource="@xml/mayra_access_config" /></service>\n';
  if (withMedia) svc += '    <service android:name=".ScreenCaptureService" android:exported="false" android:foregroundServiceType="mediaProjection" />\n';
  x = x.replace('</application>', svc + '</application>');
  javaFiles.push('MayraAccessPlugin.java', 'MayraAccessService.java');
  reg += '        registerPlugin(MayraAccessPlugin.class);\n';
  if (withMedia) { javaFiles.push('MayraMediaPlugin.java', 'ScreenCaptureService.java'); reg += '        registerPlugin(MayraMediaPlugin.class);\n'; }
  fs.mkdirSync('android/app/src/main/res/xml', { recursive: true });
  fs.copyFileSync('mayra_access_config.xml', 'android/app/src/main/res/xml/mayra_access_config.xml');
  fs.mkdirSync('android/app/src/main/res/values', { recursive: true });
  fs.copyFileSync('mayra_strings.xml', 'android/app/src/main/res/values/mayra_strings.xml');
}
for (const jf of javaFiles) {
  if (!fs.existsSync(jf)) throw new Error('MISSING FILE in the repository root: ' + jf);
  const src = fs.readFileSync(jf, 'utf8').replace(/^package\s+[\w.]+;/m, 'package ' + pkg + ';');
  fs.writeFileSync(dir + '/' + jf, src);
}
fs.writeFileSync(dir + '/MainActivity.java', 'package ' + pkg + ';\n\nimport android.os.Bundle;\nimport com.getcapacitor.BridgeActivity;\n\npublic class MainActivity extends BridgeActivity {\n    @Override\n    public void onCreate(Bundle savedInstanceState) {\n' + reg + '        super.onCreate(savedInstanceState);\n    }\n}\n');
x = x.replace('</application>', '<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="ca-app-pub-3076525107457572~8103665304"/></application>');
x = x.replace('</manifest>', add + '</manifest>');
fs.writeFileSync(f, x);
console.log('Manifest patched (' + mode + ').');

// Google "Android developer verification": if the file adi-registration.properties exists in the repository root,
// put it into the app's assets folder so it ends up inside the APK (token comes from Play Console).
if (fs.existsSync('adi-registration.properties')) {
  fs.mkdirSync('android/app/src/main/assets', { recursive: true });
  fs.copyFileSync('adi-registration.properties', 'android/app/src/main/assets/adi-registration.properties');
  console.log('adi-registration.properties copied into the app assets');
} else {
  console.log('adi-registration.properties not found in the repository root (skipped)');
}
