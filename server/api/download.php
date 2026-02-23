<?php

/**
 * Offline Package Download API
 *
 * POST /api/offline-package  – request a new offline MBTiles package
 * GET  /api/offline-package/{id} – download the package when ready
 */

declare(strict_types=1);

if (!function_exists('db')) {
    require_once __DIR__ . '/../config/database.php';
}

define('MBTILES_STORAGE', __DIR__ . '/../storage/layers');

/**
 * POST /api/offline-package
 *
 * Body: { "layerId": 3, "minZoom": 8, "maxZoom": 14, "bbox": "minx,miny,maxx,maxy" }
 */
function createOfflinePackage(): void
{
    $input = json_decode(file_get_contents('php://input'), true);

    if (!is_array($input)) {
        http_response_code(400);
        echo json_encode(['error' => 'Invalid JSON body']);
        return;
    }

    $layerId = isset($input['layerId']) ? (int)$input['layerId'] : 0;
    $minZoom = isset($input['minZoom']) ? (int)$input['minZoom'] : 0;
    $maxZoom = isset($input['maxZoom']) ? (int)$input['maxZoom'] : 14;
    $bbox    = trim((string)($input['bbox'] ?? ''));

    // Validate layer exists and is ready
    $stmt = db()->prepare('SELECT id, status FROM layers WHERE id = :id');
    $stmt->execute([':id' => $layerId]);
    $layer = $stmt->fetch();

    if (!$layer) {
        http_response_code(404);
        echo json_encode(['error' => 'Layer not found']);
        return;
    }

    if ($layer['status'] !== 'ready') {
        http_response_code(503);
        echo json_encode(['error' => 'Layer not ready', 'status' => $layer['status']]);
        return;
    }

    // Validate zoom levels
    if ($minZoom < 0 || $minZoom > 22 || $maxZoom < 0 || $maxZoom > 22 || $minZoom > $maxZoom) {
        http_response_code(400);
        echo json_encode(['error' => 'minZoom / maxZoom must be 0–22 and min ≤ max']);
        return;
    }

    // Validate bbox
    if (!preg_match('/^-?[\d.]+,-?[\d.]+,-?[\d.]+,-?[\d.]+$/', $bbox)) {
        http_response_code(400);
        echo json_encode(['error' => 'bbox must be "minx,miny,maxx,maxy"']);
        return;
    }

    $pdo = db();

    $ins = $pdo->prepare(
        'INSERT INTO offline_packages (layer_id, min_zoom, max_zoom, bbox, status)
         VALUES (:layer_id, :min_zoom, :max_zoom, :bbox, \'pending\')'
    );
    $ins->execute([
        ':layer_id' => $layerId,
        ':min_zoom' => $minZoom,
        ':max_zoom' => $maxZoom,
        ':bbox'     => $bbox,
    ]);

    $packageId = (int)$pdo->lastInsertId();

    // Enqueue processing job
    $jobStmt = $pdo->prepare(
        'INSERT INTO jobs (type, payload, status) VALUES (:type, :payload, \'pending\')'
    );
    $jobStmt->execute([
        ':type'    => 'build_mbtiles',
        ':payload' => json_encode([
            'package_id' => $packageId,
            'layer_id'   => $layerId,
            'min_zoom'   => $minZoom,
            'max_zoom'   => $maxZoom,
            'bbox'       => $bbox,
        ]),
    ]);

    http_response_code(202);
    echo json_encode([
        'packageId' => $packageId,
        'status'    => 'pending',
    ]);
}

/**
 * GET /api/offline-package/{id}
 *
 * Returns the MBTiles file when the package is ready, or status JSON otherwise.
 */
function downloadOfflinePackage(int $id): void
{
    $stmt = db()->prepare('SELECT * FROM offline_packages WHERE id = :id');
    $stmt->execute([':id' => $id]);
    $pkg = $stmt->fetch();

    if (!$pkg) {
        http_response_code(404);
        echo json_encode(['error' => 'Package not found']);
        return;
    }

    if ($pkg['status'] !== 'ready') {
        echo json_encode([
            'packageId' => $id,
            'status'    => $pkg['status'],
        ]);
        return;
    }

    $filePath = $pkg['file_path'];

    if (!$filePath || !file_exists($filePath)) {
        http_response_code(500);
        echo json_encode(['error' => 'Package file not found on server']);
        return;
    }

    $filename = sprintf('offline_layer%d_pkg%d.mbtiles', $pkg['layer_id'], $id);

    header('Content-Type: application/x-sqlite3');
    header('Content-Disposition: attachment; filename="' . $filename . '"');
    header('Content-Length: ' . filesize($filePath));
    header('Cache-Control: private, no-cache');
    readfile($filePath);
}
