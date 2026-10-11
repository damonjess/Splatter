#!/usr/bin/env python3
"""Write a synthetic 360° capture in the exact format the app saves, so the
on-device panorama path (BitmapFactory decode -> stitch -> JPEG + metadata) can
be exercised without holding a phone up and turning around.

The scene is an analytic function of direction, which is what makes the result
checkable: the app should recover roughly the same colours it was shown.

    python3 diagnostics/make_synthetic_360.py /tmp/pano360 --verify

Files written per frame (same names and formats as FrameSaver):
    pose_<ts>.txt        16 comma-separated floats, ARCore column-major
    intrinsics_<ts>.txt  fx,fy,cx,cy,width,height
    rgb_<ts>.jpg         the rendered photo
plus heading.txt and location.txt, which the capture side records for 360 mode.
"""

import argparse
import math
import os

import numpy as np
from PIL import Image

# Frame geometry: 640x480 with a 65° horizontal field of view.
WIDTH, HEIGHT = 640, 480
FX = FY = 500.0
CX, CY = (WIDTH - 1) / 2.0, (HEIGHT - 1) / 2.0

#: yaw (degrees) around each pitch ring — a hand-held sweep, not a lab gantry.
YAW_STEP = 45
PITCH_RINGS = (0.0, -35.0, 35.0, -65.0, 65.0)


def scene_color(dx: np.ndarray, dy: np.ndarray, dz: np.ndarray) -> np.ndarray:
    """Colour as a function of viewing direction: hue by azimuth, shade by elevation."""
    az = np.arctan2(dx, dz)
    el = np.arctan2(-dy, np.sqrt(dx * dx + dz * dz))
    r = ((np.sin(az) + 1.0) * 127.0).astype(np.uint8)
    g = ((np.cos(az * 2) + 1.0) * 127.0).astype(np.uint8)
    b = ((el / (math.pi / 2) + 1.0) * 127.0).astype(np.uint8)
    return np.stack([r, g, b], axis=-1)


def render(yaw_deg: float, pitch_deg: float) -> np.ndarray:
    """Renders one photo of the analytic scene from a camera rotation."""
    y, p = math.radians(yaw_deg), math.radians(pitch_deg)
    cy, sy = math.cos(y), math.sin(y)
    cp, sp = math.cos(p), math.sin(p)
    # R = Ry * Rx, column-major: column 0 = (cy, sy*sp, -sy*cp)
    rot = np.array([
        [cy, 0.0, sy],
        [sy * sp, cp, -cy * sp],
        [-sy * cp, sp, cy * cp],
    ])

    u = np.arange(WIDTH)
    v = np.arange(HEIGHT)
    cxx = (u - CX) / FX
    cyy = (v - CY) / FY
    grid_x = np.tile(cxx, (HEIGHT, 1))
    grid_y = np.tile(cyy[:, None], (1, WIDTH))
    cam = np.stack([grid_x, grid_y, np.ones_like(grid_x)], axis=-1)

    world = cam @ rot.T
    return scene_color(world[..., 0], world[..., 1], world[..., 2])


def accore_pose(yaw_deg: float, pitch_deg: float) -> str:
    """ARCore column-major camera-to-world matrix (camera looks along -Z).

    The app converts it with R_pipeline = R_arcore * diag(1, -1, -1), so writing
    the pipeline rotation with its Y and Z basis columns negated round-trips.
    """
    y, p = math.radians(yaw_deg), math.radians(pitch_deg)
    cy, sy = math.cos(y), math.sin(y)
    cp, sp = math.cos(p), math.sin(p)
    # Column-major: [m00,m10,m20,m30, m01,m11,m21,m31, ...]
    m = [
        cy, sy * sp, -sy * cp, 0.0,
        0.0, cp, sp, 0.0,
        sy, -cy * sp, cy * cp, 0.0,
        0.0, 0.0, 0.0, 1.0,
    ]
    for i in (4, 5, 6, 8, 9, 10):  # camera Y and Z basis columns
        m[i] = -m[i]
    return ",".join(repr(float(x)) for x in m)


def build(out_dir: str, base_ts: int = 1791680000000) -> int:
    os.makedirs(out_dir, exist_ok=True)
    count = 0
    for ring, pitch in enumerate(PITCH_RINGS):
        for yaw in range(0, 360, YAW_STEP):
            ts = base_ts + count * 100
            Image.fromarray(render(yaw, pitch)).save(
                os.path.join(out_dir, f"rgb_{ts}.jpg"), quality=90
            )
            with open(os.path.join(out_dir, f"pose_{ts}.txt"), "w") as fh:
                fh.write(accore_pose(yaw, pitch))
            with open(os.path.join(out_dir, f"intrinsics_{ts}.txt"), "w") as fh:
                fh.write(f"{FX},{FY},{CX},{CY},{WIDTH},{HEIGHT}")
            count += 1

    # Same files the capture side writes for 360 mode: a compass heading and a
    # position, which become GPano:PoseHeadingDegrees and EXIF GPS.
    with open(os.path.join(out_dir, "heading.txt"), "w") as fh:
        fh.write("137.5")
    with open(os.path.join(out_dir, "location.txt"), "w") as fh:
        fh.write("51.500729,-0.124625")
    return count


def verify(path: str) -> None:
    """Reads a stitched panorama back and reports what the app produced."""
    with open(path, "rb") as fh:
        data = fh.read()

    width = height = None
    i = 2
    while i + 4 <= len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        if marker in (0xDA, 0xD9):
            break
        length = (data[i + 2] << 8) | data[i + 3]
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            height = (data[i + 5] << 8) | data[i + 6]
            width = (data[i + 7] << 8) | data[i + 8]
            break
        i += 2 + length

    text = data.decode("latin-1")
    print(f"file            {path}")
    print(f"size            {len(data) / 1024 / 1024:.2f} MB")
    print(f"dimensions      {width} x {height}  ({width * height / 1e6:.1f} MP)")
    for tag in ("ProjectionType", "FullPanoWidthPixels", "FullPanoHeightPixels",
                "PoseHeadingDegrees", "CroppedAreaImageWidthPixels"):
        found = f'GPano:{tag}="' in text
        print(f"GPano:{tag:<28} {'present' if found else 'MISSING'}")

    gps = read_exif_gps(data)
    print(f"EXIF GPS        {gps if gps else 'MISSING'}")

    # The verdict Google's own requirements come down to.
    problems = []
    if width is None or abs(width / height - 2.0) > 0.02:
        problems.append("not 2:1")
    if width * height < 7_500_000:
        problems.append("under 7.5 MP")
    if len(data) > 75 * 1024 * 1024:
        problems.append("over 75 MB")
    if gps is None:
        problems.append("no GPS")
    if 'GPano:PoseHeadingDegrees="' not in text:
        problems.append("no compass heading")
    print("verdict         " + ("READY for Google Maps" if not problems
                               else "NOT publishable: " + ", ".join(problems)))


def read_exif_gps(data: bytes):
    """Walks APP1/Exif -> IFD0 -> GPS IFD and returns (latitude, longitude)."""
    i = 2
    while i + 4 <= len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        if marker in (0xDA, 0xD9):
            break
        length = (data[i + 2] << 8) | data[i + 3]
        payload = data[i + 4:i + 2 + length]
        if marker == 0xE1 and payload.startswith(b"Exif\x00\x00"):
            tiff = payload[6:]
            little = tiff[:2] == b"II"
            order = "<" if little else ">"

            def u16(at):
                return int.from_bytes(tiff[at:at + 2], "little" if little else "big")

            def u32(at):
                return int.from_bytes(tiff[at:at + 4], "little" if little else "big")

            ifd0 = u32(4)
            gps_ifd = None
            for k in range(u16(ifd0)):
                entry = ifd0 + 2 + k * 12
                if u16(entry) == 0x8825:
                    gps_ifd = u32(entry + 8)
            if gps_ifd is None:
                return None

            lat = lon = None
            lat_ref, lon_ref = "N", "E"
            for k in range(u16(gps_ifd)):
                entry = gps_ifd + 2 + k * 12
                tag = u16(entry)
                if tag == 0x0001:
                    lat_ref = chr(u32(entry + 8) & 0xFF)
                elif tag == 0x0003:
                    lon_ref = chr(u32(entry + 8) & 0xFF)
                elif tag in (0x0002, 0x0004):
                    off = u32(entry + 8)
                    value = 0.0
                    for part, divisor in ((0, 1), (1, 60), (2, 3600)):
                        num = u32(off + part * 8)
                        den = u32(off + part * 8 + 4) or 1
                        value += num / den / divisor
                    if tag == 0x0002:
                        lat = -value if lat_ref == "S" else value
                    else:
                        lon = -value if lon_ref == "W" else value
            if lat is None or lon is None:
                return None
            return f"{lat:.6f}, {lon:.6f}"
        i += 2 + length
    return None


def compare(path: str) -> None:
    """Measures a stitched panorama against the scene it was built from.

    The stitcher's reference frame is the first captured pose, which for the
    generated sweep is yaw 0 / pitch 0 — the identity — so the equirectangular
    image maps straight onto the analytic scene: u = 0.5 is azimuth 0 and the
    top row is the zenith. Any error in the projection, the pose convention or
    the blending shows up as a colour error rather than a vague impression.
    """
    img = np.asarray(Image.open(path).convert("RGB"), dtype=np.int16)
    height, width = img.shape[:2]

    ys = np.arange(2, height - 2, 4)
    xs = np.arange(0, width, 4)
    lon = (xs + 0.5) / width * 2 * math.pi - math.pi
    lat = math.pi / 2 - (ys + 0.5)[:, None] / height * math.pi

    dx = np.cos(lat) * np.sin(lon)[None, :]
    dy = -np.sin(lat) * np.ones_like(lon)[None, :]
    dz = np.cos(lat) * np.cos(lon)[None, :]
    expected = scene_color(
        np.broadcast_to(dx, (len(ys), len(xs))),
        np.broadcast_to(dy, (len(ys), len(xs))),
        np.broadcast_to(dz, (len(ys), len(xs))),
    ).astype(np.int16)

    got = img[ys][:, xs]
    error = np.abs(got - expected)
    print(f"compared        {len(ys) * len(xs):,} pixels")
    print(f"mean error      {error.mean():.2f} / 255")
    print(f"95th pct error  {np.percentile(error, 95):.1f} / 255")
    print(f"worst error     {error.max()} / 255")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("out_dir", nargs="?", default="/tmp/pano360")
    parser.add_argument("--verify", metavar="PANORAMA_JPEG", default=None)
    parser.add_argument("--compare", metavar="PANORAMA_JPEG", default=None)
    args = parser.parse_args()

    if args.verify:
        verify(args.verify)
    elif args.compare:
        compare(args.compare)
    else:
        frames = build(args.out_dir)
        print(f"wrote {frames} frames to {args.out_dir}")
