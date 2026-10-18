"""Image steps of tools/make_screens.sh (Pillow; ffmpeg and gifsicle for the GIF, pngquant for PNGs over 1 MB).

  screens.py png <in.png> <out.png>                 1920×1080 PNG of at most 1 MB (pngquant when full colour is heavier)
  screens.py sheet <out.png> <in.png=Label>...      the README's overview: a labelled 3-column grid
  screens.py gif <ffmpeg> <in.mp4> <out.gif> <a-b@speed,...>
                                                    cut the recording into segments (seconds, played speed× faster),
                                                    12 fps, 960 px, one 192-colour palette; gifsicle compresses after
"""
import os
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT = ROOT / "app/src/main/res/font/inter.ttf"
SERIF = ROOT / "app/src/main/res/font/instrument_serif.ttf"
INK, FG, MUTED, LIGHT = (5, 7, 12), (241, 236, 226), (160, 156, 150), (242, 179, 91)
MAX_PNG = 1_000_000


def shrink(out):
    """Full colour when it fits in 1 MB; else pngquant's 256 colours, which keep the cams' tones (Pillow's own
    quantizer shifted them and posterised the sky)."""
    if os.path.getsize(out) > MAX_PNG:
        subprocess.run(["pngquant", "--quality", "65-95", "--speed", "1", "--force", "--output", out, out], check=True)


def png(src, out):
    im = Image.open(src).convert("RGB")
    if im.size != (1920, 1080):
        sys.exit(f"{src} is {im.size[0]}×{im.size[1]}, not 1920×1080")
    im.save(out, optimize=True)
    shrink(out)
    size = os.path.getsize(out)
    print(f"{out}: {size // 1000} kB")
    if size > MAX_PNG:
        sys.exit(f"{out} is still over 1 MB")


def sheet(out, items, cols=3, tile=(600, 338), gap=30, label_h=56, title_h=120):
    rows = (len(items) + cols - 1) // cols
    w = cols * tile[0] + (cols + 1) * gap
    h = title_h + rows * (tile[1] + label_h) + gap
    canvas = Image.new("RGB", (w, h), INK)
    draw = ImageDraw.Draw(canvas)
    draw.text((gap, 34), "lukarne", font=ImageFont.truetype(str(SERIF), 64), fill=FG)
    draw.text((gap + 230, 62), "live webcams of the world, on Android TV", font=ImageFont.truetype(str(FONT), 24), fill=MUTED)
    font = ImageFont.truetype(str(FONT), 22)
    mask = Image.new("L", tile, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, tile[0] - 1, tile[1] - 1), radius=14, fill=255)
    for i, item in enumerate(items):
        path, label = item.split("=", 1)
        x = gap + (i % cols) * (tile[0] + gap)
        y = title_h + (i // cols) * (tile[1] + label_h)
        canvas.paste(Image.open(path).convert("RGB").resize(tile, Image.LANCZOS), (x, y), mask)
        draw.ellipse((x + 2, y + tile[1] + 22, x + 10, y + tile[1] + 30), fill=LIGHT)
        draw.text((x + 20, y + tile[1] + 13), label, font=font, fill=FG)
    canvas.save(out, optimize=True)
    shrink(out)
    print(f"{out}: {canvas.size[0]}×{canvas.size[1]}, {os.path.getsize(out) // 1000} kB")


def gif(ffmpeg, src, out, segments, fps=12, colors=192):
    chains, labels = [], []
    for i, seg in enumerate(segments.split(",")):
        span, speed = seg.split("@")
        a, b = span.split("-")
        chains.append(f"[0:v]trim={a}:{b},setpts=(PTS-STARTPTS)/{speed},fps={fps},scale=960:-1:flags=lanczos[s{i}]")
        labels.append(f"[s{i}]")
    graph = ";".join(chains) + ";" + "".join(labels) + (
        f"concat=n={len(labels)}:v=1:a=0,split[a][b];[a]palettegen=max_colors={colors}:stats_mode=diff[p];"
        "[b][p]paletteuse=dither=none:diff_mode=rectangle")
    subprocess.run([ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", src, "-filter_complex", graph,
                    "-loop", "0", out], check=True)


if __name__ == "__main__":
    cmd, *args = sys.argv[1:]
    if cmd == "png":
        png(*args)
    elif cmd == "sheet":
        sheet(args[0], args[1:])
    elif cmd == "gif":
        gif(*args)
    else:
        sys.exit(__doc__)
