#!/usr/bin/env bash
# One-click ADB export script to transfer ONLY 3D PLY models from phone to PC
EXPORT_DIR="${1:-$HOME/Desktop/Splatter3D_Models}"
mkdir -p "$EXPORT_DIR"

echo "=========================================="
echo "  Splatter 3D - Export PLY Models to PC"
echo "=========================================="

echo "Pulling PLY models saved to Downloads..."
adb pull /sdcard/Download/Splatter3D/ "$EXPORT_DIR/" 2>/dev/null

echo "Searching phone for all .ply model files..."
adb shell "find /sdcard/Android/data/com.example.splatter/files/scans/ -name '*.ply'" 2>/dev/null | while read -r remote_file; do
    # Remove carriage return if present
    remote_file=$(echo "$remote_file" | tr -d '\r')
    if [ -n "$remote_file" ]; then
        echo "Pulling: $remote_file"
        parent_dir=$(basename "$(dirname "$remote_file")")
        adb pull "$remote_file" "$EXPORT_DIR/${parent_dir}_model.ply"
    fi
done

echo ""
echo "=========================================="
echo "Export complete! PLY files saved to:"
echo " -> $EXPORT_DIR"
echo "You can open these .ply files directly in MeshLab!"
echo "=========================================="
