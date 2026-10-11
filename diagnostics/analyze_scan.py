#!/usr/bin/env python3
"""Offline analysis of a Splatter scan folder pulled off the phone.

Answers, with numbers and pictures, the questions you cannot answer from the
app's UI when a scan comes out wrong:

  1. Was raw depth captured, and is it usable?           (depth availability)
  2. Do the ARCore poses and the depth sensor agree?     (reprojection test)
  3. What does the captured data actually reconstruct?   (fusion + renders)

Usage:
    adb pull /sdcard/Android/data/com.example.splatter/files/scans/scan_XXXX ./scan_XXXX
    python3 diagnostics/analyze_scan.py ./scan_XXXX [-o report.html]

Needs only numpy. Writes an HTML report with embedded renders.
"""
import argparse
import base64
import glob
import os
import struct
import zlib

import numpy as np

POSE_GLOB = "pose_*.txt"


def load_pose(path):
    """ARCore camera-to-world pose -> pipeline space (+Z forward, y down).

    Column-major on disk: M[row][col] = vals[row + 4*col], so the C-order
    reshape has to be transposed. ARCore is +X right / +Y up / -Z forward, and
    the pipeline wants +Z forward, which is R * diag(1,-1,-1) -- i.e. negate
    the Y and Z basis columns of R.
    """
    vals = np.array([float(v) for v in open(path).read().strip().split(",")])
    if vals.size != 16:
        return None
    m = vals.reshape(4, 4).T.copy()
    m[:3, 1] *= -1
    m[:3, 2] *= -1
    return m


def load_intrinsics(path):
    return [float(v) for v in open(path).read().strip().split(",")]  # fx fy cx cy w h


def load_depth(path, w, h):
    d = np.frombuffer(open(path, "rb").read(), dtype="<u2", count=w * h)
    return d.reshape(h, w) / 1000.0          # metres, 0 = invalid


def depth_shape(dataset, ts):
    path = os.path.join(dataset, f"depthdims_{ts}.txt")
    if os.path.exists(path):
        w, h = (int(float(x)) for x in open(path).read().strip().split(",")[:2])
        return w, h
    size = os.path.getsize(os.path.join(dataset, f"depth_{ts}.raw")) // 2
    return (160, size // 160) if size % 160 == 0 else (0, 0)


def scaled_k(k, w, h):
    """Camera intrinsics scaled into a smaller image's pixel space."""
    return (k[0] * w / k[4], k[1] * h / k[5], k[2] * w / k[4], k[3] * h / k[5])


def unproject(depth, K, pose, stride=2):
    fx, fy, cx, cy = K
    h, w = depth.shape
    ys, xs = np.mgrid[0:h:stride, 0:w:stride]
    z = depth[ys, xs]
    ok = (z > 0.05) & (z < 8.0)
    cam = np.stack([(xs - cx) * z / fx, (ys - cy) * z / fy, z], axis=-1)[ok]
    return cam @ pose[:3, :3].T + pose[:3, 3]


def project(world, K, pose):
    fx, fy, cx, cy = K
    cam = (world - pose[:3, 3]) @ pose[:3, :3]
    z = cam[:, 2]
    front = z > 0.05
    u = np.where(front, (fx * cam[:, 0] / np.where(front, z, 1) + cx).round(), -1).astype(int)
    v = np.where(front, (fy * cam[:, 1] / np.where(front, z, 1) + cy).round(), -1).astype(int)
    return u, v, z


def png_data_uri(gray):
    h, w = gray.shape
    raw = b"".join(b"\x00" + gray[y].tobytes() for y in range(h))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n" +
           chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 0, 0, 0, 0)) +
           chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))
    return "data:image/png;base64," + base64.b64encode(png).decode()


def render_tiles(points, size=460):
    centre = (points.max(axis=0) + points.min(axis=0)) / 2
    p = points - centre
    span = float(np.max(p.max(axis=0) - p.min(axis=0)))
    out = []
    for ax, ay in ((0, 1), (2, 1), (2, 0)):          # front, side, top
        img = np.zeros((size, size), dtype=np.float32)
        xs = ((p[:, ax] / span + 0.5) * (size - 1)).astype(int)
        ys = ((0.5 - p[:, ay] / span) * (size - 1)).astype(int)
        ok = (xs >= 0) & (xs < size) & (ys >= 0) & (ys < size)
        np.add.at(img, (ys[ok], xs[ok]), 1.0)
        nz = img[img > 0]
        out.append(np.clip(img / max(np.percentile(nz, 97) if nz.size else 1.0, 1.0), 0, 1))
    return (np.concatenate(out, axis=1) ** 0.6 * 255).astype(np.uint8), span


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dataset", help="scan folder pulled from the phone")
    ap.add_argument("-o", "--out", default=None, help="output HTML report")
    args = ap.parse_args()
    dataset = args.dataset
    report = args.out or os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                      f"report-{os.path.basename(dataset.rstrip('/'))}.html")

    poses = {}
    for f in glob.glob(os.path.join(dataset, POSE_GLOB)):
        ts = os.path.basename(f)[len("pose_"):-len(".txt")]
        p = load_pose(f)
        if p is not None:
            poses[ts] = p
    if not poses:
        raise SystemExit(f"no {POSE_GLOB} files found in {dataset}")
    stamps = sorted(poses, key=lambda t: int(t))
    centres = np.array([poses[t][:3, 3] for t in stamps])
    extent = centres.max(axis=0) - centres.min(axis=0)

    # 1. depth availability
    depth_stamps = [t for t in stamps if os.path.exists(os.path.join(dataset, f"depth_{t}.raw"))]
    valid_fracs, shapes = [], set()
    for t in depth_stamps[::max(1, len(depth_stamps) // 24)]:
        w, h = depth_shape(dataset, t)
        if w <= 0:
            continue
        d = load_depth(os.path.join(dataset, f"depth_{t}.raw"), w, h)
        shapes.add((w, h))
        valid_fracs.append(float(((d > 0.3) & (d < 5.0)).mean()))

    # 2. pose/depth consistency
    # Consecutive frames only (0.1 s apart): this measures whether the pose and
    # the depth sensor agree frame to frame, which is what decides whether the
    # fusion stitches cleanly. Comparing frames seconds apart would instead
    # measure AR tracking drift, which shows up as a separate line below.
    consistency = []
    windows = range(0, max(1, len(depth_stamps) - 1), max(1, len(depth_stamps) // 8))
    tested = [depth_stamps[i] for i in windows if i + 1 < len(depth_stamps)]
    for a, b in zip(tested, [depth_stamps[depth_stamps.index(a) + 1] for a in tested]):
        wa, ha = depth_shape(dataset, a)
        wb, hb = depth_shape(dataset, b)
        if min(wa, ha, wb, hb) <= 0:
            continue
        ka = scaled_k(load_intrinsics(os.path.join(dataset, f"intrinsics_{a}.txt")), wa, ha)
        kb = scaled_k(load_intrinsics(os.path.join(dataset, f"intrinsics_{b}.txt")), wb, hb)
        world = unproject(load_depth(os.path.join(dataset, f"depth_{a}.raw"), wa, ha), ka, poses[a], 1)
        u, v, z = project(world, kb, poses[b])
        inside = (u >= 0) & (u < wb) & (v >= 0) & (v < hb)
        measured = load_depth(os.path.join(dataset, f"depth_{b}.raw"), wb, hb)[v[inside], u[inside]]
        good = (measured > 0.2) & (measured < 5.0) & (z[inside] > 0.2)
        if good.sum() < 500:
            continue
        err = np.abs(measured[good] - z[inside][good])
        consistency.append((a, b, int(good.sum()), float(np.median(err)), float(np.percentile(err, 90))))

    # 3. fuse + render
    points = []
    for t in stamps:
        path = os.path.join(dataset, f"depth_{t}.raw")
        if not os.path.exists(path):
            continue
        w, h = depth_shape(dataset, t)
        if w <= 0:
            continue
        d = load_depth(path, w, h)
        if ((d > 0.3) & (d < 5.0)).mean() < 0.05:
            continue
        k = scaled_k(load_intrinsics(os.path.join(dataset, f"intrinsics_{t}.txt")), w, h)
        points.append(unproject(d, k, poses[t]))
    pts = np.concatenate(points)
    raw_count = len(pts)
    cells = np.round(pts / 0.01).astype(np.int64)
    _, keep = np.unique(cells, axis=0, return_index=True)
    pts = pts[np.sort(keep)]
    merged_count = len(pts)
    c = np.round(pts / 0.03).astype(np.int64)
    uq, counts = np.unique(c, axis=0, return_counts=True)
    dense = set(map(tuple, uq[counts >= 3]))
    pts = pts[np.array([tuple(r) in dense for r in c])]
    tile_img, span = render_tiles(pts)
    closeness = np.linalg.norm(pts - centres.mean(axis=0), axis=1)
    near_object = pts[closeness < 1.5]

    rows = "".join(
        f"<tr><td>{a[-6:]} &rarr; {b[-6:]}</td><td>{n}</td>"
        f"<td>{med*100:.1f} cm</td><td>{p90*100:.1f} cm</td></tr>"
        for a, b, n, med, p90 in consistency
    )
    vf = np.array(valid_fracs) if valid_fracs else np.array([0.0])
    med_err = float(np.median([c[3] for c in consistency])) if consistency else 1.0
    if not consistency:
        verdict = "no comparable frames — cannot judge the capture"
    elif med_err < 0.10:
        verdict = (f"poses and depth agree frame to frame (median {med_err*100:.1f} cm) — "
                   "the capture supports a solid model")
    else:
        verdict = (f"poses and depth disagree frame to frame (median {med_err*100:.1f} cm) — "
                   "the poses or the depth noise are the suspect")

    # tracking drift: same depth reprojected across a whole second of motion
    drift = None
    if len(depth_stamps) > 20:
        a, b = depth_stamps[len(depth_stamps) // 2], depth_stamps[min(len(depth_stamps) - 1, len(depth_stamps) // 2 + 10)]
        wa, ha = depth_shape(dataset, a)
        wb, hb = depth_shape(dataset, b)
        if min(wa, ha, wb, hb) > 0:
            ka = scaled_k(load_intrinsics(os.path.join(dataset, f"intrinsics_{a}.txt")), wa, ha)
            kb = scaled_k(load_intrinsics(os.path.join(dataset, f"intrinsics_{b}.txt")), wb, hb)
            world = unproject(load_depth(os.path.join(dataset, f"depth_{a}.raw"), wa, ha), ka, poses[a], 1)
            u, v, z = project(world, kb, poses[b])
            inside = (u >= 0) & (u < wb) & (v >= 0) & (v < hb)
            measured = load_depth(os.path.join(dataset, f"depth_{b}.raw"), wb, hb)[v[inside], u[inside]]
            good = (measured > 0.2) & (measured < 5.0) & (z[inside] > 0.2)
            if good.sum() >= 500:
                drift = (a, b, float(np.median(np.abs(measured[good] - z[inside][good]))))
    html = f"""<!doctype html><html><head><meta charset="utf-8"><title>Scan report</title>
<style>body{{background:#0a0b0f;color:#f5f7fa;font:13px system-ui;padding:20px;max-width:1400px}}
img{{width:1380px;max-width:100%;border:1px solid #272b36;background:#000}}
table{{border-collapse:collapse;margin:8px 0}}td,th{{border:1px solid #272b36;padding:4px 10px;color:#c8cfda}}
h2,h3{{color:#4ce0b3}}p,li{{color:#9aa3b2}}code{{color:#ffc46b}}</style></head><body>
<h2>Scan report &mdash; {os.path.basename(dataset.rstrip('/'))}</h2>
<ul>
<li>frames with a pose: <b>{len(stamps)}</b></li>
<li>camera path extent: <b>{extent[0]:.2f} &times; {extent[1]:.2f} &times; {extent[2]:.2f} m</b></li>
<li>frames with raw depth: <b>{len(depth_stamps)}</b>, depth shapes {sorted(shapes) or '&mdash;'}</li>
<li>valid depth pixels (0.3&ndash;5 m): median <b>{np.median(vf):.1%}</b>, min {vf.min():.1%}, max {vf.max():.1%}</li>
<li>reconstruction: <b>{raw_count:,} raw &rarr; {merged_count:,} at 1 cm voxels &rarr; {len(pts):,} after trimming</b></li>
<li>extent of trimmed cloud: <b>{span:.2f} m</b>; points within 1.5 m of the camera path centre: <b>{len(near_object):,}</b></li>
<li><b>verdict:</b> {verdict}</li>
<li>drift check (10 frames / ~1 s of motion apart): {(f"median {drift[2]*100:.1f} cm" if drift else "not available")}</li>
</ul>
<h3>Pose / depth consistency (predicted vs measured depth)</h3>
<table><tr><th>frame pair</th><th>samples</th><th>median error</th><th>p90 error</th></tr>{rows or '<tr><td colspan=4>no comparable pairs</td></tr>'}</table>
<h3>Reconstruction (front | side | top)</h3>
<img src="{png_data_uri(tile_img)}" alt="orthographic renders of the reconstruction">
<p>Rendered from the pulled dataset, outside the app. Continuous surfaces mean the capture is
usable; isolated speckle means the depth or poses are the problem.</p>
</body></html>"""
    open(report, "w").write(html)

    print(f"frames={len(stamps)} depth_frames={len(depth_stamps)} "
          f"valid_depth_median={np.median(vf):.1%} path_extent={np.round(extent, 2)}")
    for a, b, n, med, p90 in consistency:
        print(f"  {a[-6:]} -> {b[-6:]}: n={n:6d} median {med*100:5.1f} cm  p90 {p90*100:5.1f} cm")
    print(f"points: {raw_count:,} raw -> {merged_count:,} (1 cm) -> {len(pts):,} (trimmed)")
    print(f"report: {report}")


if __name__ == "__main__":
    main()
