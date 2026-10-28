#!/usr/bin/env python3
"""Generate the redistributable core pack. Host-only dependency: Pillow 13.0.0.

PNG/JPEG/WebP encoder versions can change bytes: check in generated outputs and manifest;
devices compare the exact shipped hashes, not newly encoded content on each OS.
"""
import hashlib
import io
import json
from pathlib import Path
import random
import zipfile
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'app/src/renderBench/assets/bench'


def page(width, height, index, kind):
    rng = random.Random(77001 + index)
    texture = bytes(rng.randrange(256) for _ in range(128 * 128 * 3))
    image = Image.frombytes('RGB', (128, 128), texture).resize((width, height))
    draw = ImageDraw.Draw(image)
    for y in range(0, height, 128):
        if y + 8 >= height:
            break
        draw.rectangle((32, y + 8, width - 32, min(height - 1, y + 104)),
                       fill=(220 + index % 30, 225, 230))
        draw.text((64, y + 40), f'ComicViewer synthetic page {index:02d} row {y // 128:03d}', fill=(10, 20, 30))
    draw.rectangle((0, 0, 23, height - 1), fill=(240, 20, 30))
    draw.rectangle((width - 24, 0, width - 1, height - 1), fill=(20, 30, 240))
    draw.rectangle((0, height - 32, width - 1, height - 1), fill=(20, 210, 40))
    data = io.BytesIO()
    image.save(data, format=kind, **({'quality': 88} if kind in ('JPEG', 'WEBP') else {}))
    image.close()
    return data.getvalue()


def archive(name, entries):
    with zipfile.ZipFile(OUT / name, 'w') as z:
        for path, data in entries:
            info = zipfile.ZipInfo(path, (2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            z.writestr(info, data)


def pdf():
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'']
    kids = []
    for i in range(8):
        page_id, stream_id = len(objects) + 1, len(objects) + 2
        kids.append(f'{page_id} 0 R')
        content = f'0.95 0.95 0.95 rg 0 0 800 1200 re f\n'.encode()
        for row in range(60):
            content += f'{(row+i)%9/10:.1f} 0.3 0.6 rg 20 {row*20} 760 12 re f\n'.encode()
        objects.append(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 800 1200] /Contents {stream_id} 0 R >>'.encode())
        objects.append(f'<< /Length {len(content)} >>\nstream\n'.encode() + content + b'endstream')
    objects[1] = f'<< /Type /Pages /Count 8 /Kids [{" ".join(kids)}] >>'.encode()
    data = bytearray(b'%PDF-1.4\n')
    offsets = [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data += f'{i} 0 obj\n'.encode() + obj + b'\nendobj\n'
    start = len(data)
    data += f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode()
    data += b''.join(f'{offset:010d} 00000 n \n'.encode() for offset in offsets[1:])
    data += f'trailer\n<< /Root 1 0 R /Size {len(offsets)} >>\nstartxref\n{start}\n%%EOF\n'.encode()
    (OUT / 'vector.pdf').write_bytes(data)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    raster = [(f'{i:03d}.jpg', page(1988, 3056, i, 'JPEG')) for i in range(10)]
    archive('raster.cbz', raster)
    for issue, offset in zip(('a', 'b', 'c'), (0, 2, 4)):
        archive(f'issue-{issue}.cbz', raster[offset:offset + 2])
    archive('strips.cbz', [(f'{i:03d}.png', page(w, h, i, 'PNG')) for i, (w, h) in enumerate(
        [(800, 6000), (1200, 4000), (800, 10000), (1600, 2560), (900, 7000), (800, 6000)])])
    archive('fallback.cbz', [(f'{i:03d}.gif', page(1988, 3056, i, 'GIF')) for i in range(3)])
    entries = [('mimetype', b'application/epub+zip'),
        ('META-INF/container.xml', b'<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')]
    manifest, spine = [], []
    for i in range(6):
        name = f'{i:03d}.webp'
        entries.append((name, page(1200, 2200 + i * 120, i, 'WEBP')))
        entries.append((f'{i}.xhtml', f'<html xmlns="http://www.w3.org/1999/xhtml"><head><title>{i}</title></head><body><img src="{name}" alt="synthetic"/></body></html>'.encode()))
        manifest.append(f'<item id="p{i}" href="{i}.xhtml" media-type="application/xhtml+xml"/><item id="i{i}" href="{name}" media-type="image/webp"/>')
        spine.append(f'<itemref idref="p{i}"/>')
    entries.append(('book.opf', ('<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">synthetic-render-bench</dc:identifier><dc:title>Synthetic benchmark</dc:title></metadata><manifest>' + ''.join(manifest) + '</manifest><spine>' + ''.join(spine) + '</spine></package>').encode()))
    archive('images.epub', entries)
    pdf()
    fixtures = [{'name': p.name, 'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
                for p in sorted(OUT.iterdir()) if p.suffix in ('.cbz', '.epub', '.pdf')]
    (OUT / 'manifest.json').write_text(json.dumps({'version': 1, 'seed': 77001, 'fixtures': fixtures}, indent=2) + '\n')
    print(f'{len(fixtures)} fixtures; {sum(f["bytes"] for f in fixtures):,} encoded bytes')


if __name__ == '__main__':
    main()
