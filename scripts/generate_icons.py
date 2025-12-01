import os
import sys
from PIL import Image, ImageDraw, ImageOps

INPUT_DIR = "app/src/main/assets/app_logo_input"
RES_DIR = "app/src/main/res"

def find_logo():
    if not os.path.exists(INPUT_DIR):
        print(f"Directory {INPUT_DIR} does not exist.")
        return None
    files = [f for f in os.listdir(INPUT_DIR) if f.lower().endswith('.png')]
    if not files:
        print("No PNG found.")
        return None
    return os.path.join(INPUT_DIR, files[0])

def create_round_icon(img):
    # Create circular mask
    mask = Image.new('L', img.size, 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0) + img.size, fill=255)

    # Fit image and apply mask
    output = ImageOps.fit(img, mask.size, centering=(0.5, 0.5))
    output.putalpha(mask)
    return output

def process_icons(logo_path):
    print(f"Processing logo: {logo_path}")
    img = Image.open(logo_path).convert("RGBA")

    # Sizes for Legacy Icons
    sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192
    }

    # 1. Generate Legacy Icons (Square & Round)
    for folder, size in sizes.items():
        out_dir = os.path.join(RES_DIR, folder)
        os.makedirs(out_dir, exist_ok=True)

        # Square (Standard)
        resized = img.resize((size, size), Image.Resampling.LANCZOS)
        resized.save(os.path.join(out_dir, "ic_launcher.png"))

        # Round
        round_img = create_round_icon(resized)
        round_img.save(os.path.join(out_dir, "ic_launcher_round.png"))
        print(f"Generated {folder} ({size}x{size})")

    # 2. Generate Adaptive Foreground
    # Target 432x432 for xxxhdpi (high res master)
    # Safe zone is center 66% (288px).
    # We fit the logo into 288x288 and center it on 432x432 canvas.
    fg_size = 432
    safe_zone = int(fg_size * 0.66) # 285

    fg_img = Image.new("RGBA", (fg_size, fg_size), (0,0,0,0))

    # Resize logo to fit in safe zone
    logo_resized = img.resize((safe_zone, safe_zone), Image.Resampling.LANCZOS)
    offset = (fg_size - safe_zone) // 2

    fg_img.paste(logo_resized, (offset, offset), logo_resized)

    # Save to drawable (as master foreground)
    fg_dir = os.path.join(RES_DIR, "drawable")
    os.makedirs(fg_dir, exist_ok=True)
    fg_img.save(os.path.join(fg_dir, "ic_launcher_foreground.png"))
    print("Generated Adaptive Foreground")

    # 3. Generate Background Color Resource
    values_dir = os.path.join(RES_DIR, "values")
    os.makedirs(values_dir, exist_ok=True)
    bg_xml_path = os.path.join(values_dir, "ic_launcher_background.xml")

    # Overwrite only if creating? Or just force White.
    with open(bg_xml_path, "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write('<resources>\n')
        f.write('    <color name="ic_launcher_background">#FFFFFF</color>\n')
        f.write('</resources>')
    print("Generated Background Color XML")

    # 4. Generate Adaptive Icon XMLs
    anydpi_dir = os.path.join(RES_DIR, "mipmap-anydpi-v26")
    os.makedirs(anydpi_dir, exist_ok=True)

    xml_content = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>'''

    with open(os.path.join(anydpi_dir, "ic_launcher.xml"), "w") as f:
        f.write(xml_content)

    with open(os.path.join(anydpi_dir, "ic_launcher_round.xml"), "w") as f:
        f.write(xml_content)
    print("Generated Adaptive XMLs")

    # 5. Monochrome Icon (Optional - Android 13)
    # Convert alpha/logo to flat color?
    # Simple monochrome: just the logo alpha mask?
    # For now, we skip monochrome to keep it simple, or use the foreground.
    # <monochrome android:drawable="@mipmap/ic_launcher_foreground"/> could be added to XML.

if __name__ == "__main__":
    logo = find_logo()
    if logo:
        process_icons(logo)
    else:
        print("No PNG logo found in app_logo_input/")
