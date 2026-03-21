import os
import struct
import zlib

def create_png(width, height, filename):
    # Purple background (#6C5CE7), white "WT" text approximated as shapes
    pixels = []
    for y in range(height):
        row = []
        for x in range(width):
            # Background: purple
            r, g, b, a = 108, 92, 231, 255

            # Draw a simple white rounded square in center (60% of size)
            margin = width * 0.2
            if margin < x < width - margin and margin < y < height - margin:
                # Inner white area
                cx, cy = width / 2, height / 2
                # Draw "W" left bar
                bar_w = width * 0.06
                bar_h = height * 0.35
                top = cy - bar_h / 2
                # Left vertical of W
                if (cx - width*0.2 - bar_w/2) < x < (cx - width*0.2 + bar_w/2) and top < y < top + bar_h:
                    r, g, b = 255, 255, 255
                # Right vertical of W
                elif (cx - width*0.07 - bar_w/2) < x < (cx - width*0.07 + bar_w/2) and top < y < top + bar_h:
                    r, g, b = 255, 255, 255
                # Middle of W
                elif (cx - width*0.135 - bar_w/2) < x < (cx - width*0.135 + bar_w/2) and (cy - bar_h/2) < y < cy:
                    r, g, b = 255, 255, 255
                # T vertical
                elif (cx + width*0.13 - bar_w/2) < x < (cx + width*0.13 + bar_w/2) and top < y < top + bar_h:
                    r, g, b = 255, 255, 255
                # T horizontal
                elif (cx + width*0.05) < x < (cx + width*0.21) and top < y < top + bar_w*1.5:
                    r, g, b = 255, 255, 255

            row.extend([r, g, b, a])
        pixels.append(row)

    # Build PNG
    def make_chunk(chunk_type, data):
        c = chunk_type + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)

    raw = b''
    for row in pixels:
        raw += b'\x00' + bytes(row)

    compressed = zlib.compress(raw, 9)
    ihdr_data = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)

    png = b'\x89PNG\r\n\x1a\n'
    png += make_chunk(b'IHDR', ihdr_data)
    png += make_chunk(b'IDAT', compressed)
    png += make_chunk(b'IEND', b'')

    os.makedirs(os.path.dirname(filename), exist_ok=True)
    with open(filename, 'wb') as f:
        f.write(png)
    print(f"Created: {filename} ({width}x{height})")

# All required mipmap sizes
sizes = {
    'app/src/main/res/mipmap-mdpi/ic_launcher.png': 48,
    'app/src/main/res/mipmap-mdpi/ic_launcher_round.png': 48,
    'app/src/main/res/mipmap-hdpi/ic_launcher.png': 72,
    'app/src/main/res/mipmap-hdpi/ic_launcher_round.png': 72,
    'app/src/main/res/mipmap-xhdpi/ic_launcher.png': 96,
    'app/src/main/res/mipmap-xhdpi/ic_launcher_round.png': 96,
    'app/src/main/res/mipmap-xxhdpi/ic_launcher.png': 144,
    'app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png': 144,
    'app/src/main/res/mipmap-xxxhdpi/ic_launcher.png': 192,
    'app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png': 192,
}

for path, size in sizes.items():
    create_png(size, size, path)

print("\nAll icons generated!")
