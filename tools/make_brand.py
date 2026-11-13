"""Generates the Loungo brand images: app icon, TV banner and Play Store graphics.

Run from the repo root:  python tools/make_brand.py
Outputs (overwritten):
  app/src/main/res/mipmap-*/ic_launcher.png      launcher icon
  app/src/main/res/drawable-xhdpi/banner.png     Android TV launcher banner (320x180 dp @2x)
  docs/store/icon-512.png                        Play hi-res icon
  docs/store/feature-graphic-1024x500.png        Play feature graphic
  docs/store/tv-banner-1280x720.png              Play Android TV banner
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

BG_TOP = (14, 18, 32)
BG_BOTTOM = (27, 36, 64)
ACCENT_A = (10, 132, 255)
ACCENT_B = (94, 92, 230)
FONT = r'C:\Windows\Fonts\segoeuib.ttf'
FONT_LIGHT = r'C:\Windows\Fonts\segoeui.ttf'


def gradient(size, top, bottom, horizontal=False):
    w, h = size
    img = Image.new('RGB', size)
    px = img.load()
    n = w if horizontal else h
    for i in range(n):
        t = i / max(1, n - 1)
        c = tuple(round(top[k] + (bottom[k] - top[k]) * t) for k in range(3))
        if horizontal:
            for y in range(h):
                px[i, y] = c
        else:
            for x in range(w):
                px[x, i] = c
    return img


def mark(size):
    """TV screen outline with a play triangle, in the blue-violet accent gradient (RGBA)."""
    s = size
    m = Image.new('L', (s, s), 0)
    d = ImageDraw.Draw(m)
    pad = s * 0.08
    w = s * 0.075
    # Screen
    d.rounded_rectangle([pad, s * 0.16, s - pad, s * 0.74], radius=s * 0.09, outline=255, width=round(w))
    # Stand
    d.rounded_rectangle([s * 0.36, s * 0.80, s * 0.64, s * 0.80 + w], radius=w / 2, fill=255)
    # Play triangle
    cx, cy, r = s * 0.51, s * 0.45, s * 0.15
    d.polygon([(cx - r * 0.8, cy - r), (cx - r * 0.8, cy + r), (cx + r, cy)], fill=255)
    grad = gradient((s, s), ACCENT_A, ACCENT_B, horizontal=True).convert('RGBA')
    grad.putalpha(m)
    return grad


def glow(img, mark_img, pos):
    g = Image.new('RGBA', img.size, (0, 0, 0, 0))
    g.paste(mark_img, pos, mark_img)
    g = g.filter(ImageFilter.GaussianBlur(mark_img.size[0] * 0.06))
    img.alpha_composite(g)
    img.alpha_composite(mark_img, pos)


def icon(size):
    img = gradient((size, size), BG_TOP, BG_BOTTOM).convert('RGBA')
    mk = mark(round(size * 0.78))
    off = (size - mk.size[0]) // 2
    glow(img, mk, (off, off))
    return img


def banner(w, h, tagline=None):
    img = gradient((w, h), BG_TOP, BG_BOTTOM).convert('RGBA')
    mk = mark(round(h * 0.52))
    x = round(w * 0.07)
    y = (h - mk.size[1]) // 2
    glow(img, mk, (x, y))
    d = ImageDraw.Draw(img)
    tx = x + mk.size[0] + round(w * 0.04)
    # Largest title size that leaves a right margin of 7% of the width.
    size = round(h * 0.26)
    while ImageFont.truetype(FONT, size).getlength('Loungo') > w * 0.93 - tx:
        size -= 2
    title = ImageFont.truetype(FONT, size)
    if tagline:
        ssize = round(h * 0.075)
        while ImageFont.truetype(FONT_LIGHT, ssize).getlength(tagline) > w * 0.93 - tx:
            ssize -= 1
        sub = ImageFont.truetype(FONT_LIGHT, ssize)
        th = title.getbbox('Loungo')[3]
        ty = (h - th - round(h * 0.13)) // 2 - round(h * 0.03)
        d.text((tx, ty), 'Loungo', font=title, fill=(255, 255, 255))
        d.text((tx + 4, ty + th + round(h * 0.05)), tagline, font=sub, fill=(170, 182, 214))
    else:
        th = title.getbbox('Loungo')[3]
        d.text((tx, (h - th) // 2 - round(h * 0.04)), 'Loungo', font=title, fill=(255, 255, 255))
    return img


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.convert('RGB').save(path, optimize=True)
    print(path, img.size)


if __name__ == '__main__':
    for d, px in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
        save(icon(px * 4).resize((px, px), Image.LANCZOS), f'app/src/main/res/mipmap-{d}/ic_launcher.png')
    save(banner(640, 360), 'app/src/main/res/drawable-xhdpi/banner.png')
    save(icon(512), 'docs/store/icon-512.png')
    save(banner(1024, 500, 'Fast, light browser for your TV'), 'docs/store/feature-graphic-1024x500.png')
    save(banner(1280, 720, 'Browse and watch on your TV, from your phone'), 'docs/store/tv-banner-1280x720.png')
