#!/usr/bin/env python3
"""Native-font PNG specimens for nonshipping consolidated reference candidates."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
FACES = ROOT / 'assets/fonts/experimental/consolidated'
SAMPLES = (
    'Chunks!   TORRENTS, YOUR WAY.',
    '“Résumé” [Season 1] (26 episodes) 100%',
    'Files {156} | 2.7 GB/s @ 2026-10-27',
    'A/B\\C : ! ? . , ; + - = < > & # $ %',
)
STYLE = ['Regular', 'Medium', 'Semibold', 'Bold', 'ExtraBold']


def main():
    width = 1770
    height = 5 * 240 + 100
    img = Image.new('RGB', (width, height), '#F7F8FA')
    d = ImageDraw.Draw(img)
    header = ImageFont.load_default(size=24)
    labels = ImageFont.load_default(size=17)
    d.text((50, 28), 'CHUNKS | CONSOLIDATED REFERENCE FONT COMPARISON',
           fill='#111827', font=header)
    y = 88
    for style in STYLE:
        path = FACES / f'ChunksReferencePanel-{style}-Consolidated.otf'
        if not path.is_file():
            raise FileNotFoundError(path)
        d.text((50, y), f'{style} | native OpenType/CFF (nonshipping)',
               fill='#526273', font=labels)
        d.line((50, y + 28, width - 50, y + 28), fill='#C8D2DF', width=2)
        for i, sample in enumerate(SAMPLES):
            size = (46, 30, 25, 25)[i]
            font = ImageFont.truetype(str(path), size, layout_engine=ImageFont.Layout.RAQM)
            d.text((50, y + 40 + i * 46), sample, font=font, fill='#131D2A')
        y += 240
    output = Path('reference-panel-consolidated-five-weights.png')
    img.save(output)
    print(output.resolve())


if __name__ == '__main__':
    main()
