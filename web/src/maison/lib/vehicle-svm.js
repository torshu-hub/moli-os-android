// MIT: native browser rendering of the public hyundai_kia_connect_api
// equidistant fisheye model. No network, storage, position or image export.
export function segments(sizes, width, height) {
  if (!Array.isArray(sizes) || sizes.length < 6 || sizes.some((v) => !Number.isInteger(v) || v <= 0)) return [];
  if (sizes[0] !== width || sizes[1] !== height || 4 * sizes[2] + sizes[4] !== width || sizes[3] > height || sizes[5] > height) return [];
  return ['front', 'rear', 'left', 'right', 'top'].map((key, i) => ({ key, x: i < 4 ? i * sizes[2] : 4 * sizes[2], width: i < 4 ? sizes[2] : sizes[4], height: i < 4 ? sizes[3] : sizes[5] }));
}
export function dewarp(data, width, height, fov) {
  if (!Number.isFinite(fov) || fov <= 0 || fov > 180 || width * height > 1600000) return null;
  const out = new Uint8ClampedArray(data.length), half = fov * Math.PI / 360;
  const fish = Math.min(width, height) / 2 / half, rect = width / 2 / Math.tan(Math.min(fov, 120) * Math.PI / 360);
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const dx = x - (width - 1) / 2, dy = y - (height - 1) / 2, radius = Math.hypot(dx, dy), theta = Math.atan(radius / rect), at = (y * width + x) * 4;
    out[at + 3] = 255;
    if (theta > half) continue;
    const ratio = radius ? fish * theta / radius : 0;
    const sx = (width - 1) / 2 + dx * ratio, sy = (height - 1) / 2 + dy * ratio;
    const x0 = Math.floor(sx), y0 = Math.floor(sy);
    if (x0 < 0 || y0 < 0 || x0 + 1 >= width || y0 + 1 >= height) continue;
    const fx = sx - x0, fy = sy - y0;
    for (let c = 0; c < 3; c++) out[at + c] = data[(y0 * width + x0) * 4 + c] * (1 - fx) * (1 - fy) + data[(y0 * width + x0 + 1) * 4 + c] * fx * (1 - fy) + data[((y0 + 1) * width + x0) * 4 + c] * (1 - fx) * fy + data[((y0 + 1) * width + x0 + 1) * 4 + c] * fx * fy;
  }
  return out;
}
