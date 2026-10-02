"""QR-метки тестового оборудования для проверки сканера (экран Э5).

Берёт единицы оборудования из server/ServicePult/SeedData/seed.json и рисует для каждой
PNG с QR-кодом метки компании «BK-EQ-000123» и подписью — как наклейка на корпусе.
Картинку можно показать камере эмулятора (плакат виртуальной сцены) или распечатать.

Запуск:  python make_qr.py
Результат: tools/qr/BK-EQ-000123.png и т. д.
"""
import json
from pathlib import Path

import qrcode
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
SEED = ROOT.parent / "server" / "ServicePult" / "SeedData" / "seed.json"
OUT = ROOT / "qr"


def label(code: str, caption: str) -> Image.Image:
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=24, border=4)
    qr.add_data(code)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white").convert("RGB")
    w, h = img.size
    sheet = Image.new("RGB", (w, h + 150), "white")
    sheet.paste(img, (0, 0))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("arial.ttf", 56)
        small = ImageFont.truetype("arial.ttf", 36)
    except OSError:
        font = small = ImageFont.load_default()
    draw.text((w // 2, h + 10), code, fill="black", font=font, anchor="mt")
    draw.text((w // 2, h + 85), caption, fill="black", font=small, anchor="mt")
    return sheet


def main():
    seed = json.loads(SEED.read_text(encoding="utf-8"))
    OUT.mkdir(exist_ok=True)
    for eq in seed["equipment"]:
        path = OUT / f"{eq['inv_code']}.png"
        label(eq["inv_code"], f"{eq['vendor']} {eq['model']}").save(path)
        print(path.name)


if __name__ == "__main__":
    main()
