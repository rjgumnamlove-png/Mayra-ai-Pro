# Builds the Android launcher icons from mayra_icon.png (run after "cap add android")
from PIL import Image, ImageDraw
import os
src = Image.open('mayra_icon.png').convert('RGBA')
w, h = src.size; m = min(w, h)
src = src.crop(((w-m)//2, (h-m)//2, (w-m)//2+m, (h-m)//2+m))
res = 'android/app/src/main/res'
for d, sc in {'mdpi':1,'hdpi':1.5,'xhdpi':2,'xxhdpi':3,'xxxhdpi':4}.items():
    p = f'{res}/mipmap-{d}'; os.makedirs(p, exist_ok=True)
    n = int(48*sc)
    sq = Image.new('RGBA', (n, n), (0,0,0,255)); sq.alpha_composite(src.resize((n,n), Image.LANCZOS)); sq.save(f'{p}/ic_launcher.png')
    mk = Image.new('L', (n*4,n*4), 0); ImageDraw.Draw(mk).ellipse((0,0,n*4-1,n*4-1), fill=255); mk = mk.resize((n,n), Image.LANCZOS)
    rd = Image.new('RGBA', (n,n), (0,0,0,0)); rd.paste(sq, (0,0), mk); rd.save(f'{p}/ic_launcher_round.png')
    f = int(108*sc); k = int(f*0.70)
    fg = Image.new('RGBA', (f,f), (0,0,0,0)); fg.alpha_composite(src.resize((k,k), Image.LANCZOS), ((f-k)//2,(f-k)//2)); fg.save(f'{p}/ic_launcher_foreground.png')
os.makedirs(f'{res}/values', exist_ok=True)
open(f'{res}/values/ic_launcher_background.xml','w').write('<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="ic_launcher_background">#000000</color></resources>\n')
os.makedirs(f'{res}/mipmap-anydpi-v26', exist_ok=True)
x = '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/ic_launcher_background"/><foreground android:drawable="@mipmap/ic_launcher_foreground"/></adaptive-icon>\n'
for n in ('ic_launcher.xml','ic_launcher_round.xml'): open(f'{res}/mipmap-anydpi-v26/{n}','w').write(x)
print('icons ok')
