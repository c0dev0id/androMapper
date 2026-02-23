<?php

/**
 * Layers API
 *
 * POST /api/layers  – register a new layer (triggers background job)
 * GET  /api/layers  – list all layers
 * GET  /api/layers/{id} – get a single layer
 */

declare(strict_types=1);

if (!function_exists('db')) {
    require_once __DIR__ . '/../config/database.php';
}

/** Allowed layer types. */
const ALLOWED_TYPES = ['wms', 'wfs', 'geotiff', 'geopdf', 'shapefile', 'geojson'];

/** Maximum upload URL / path length. */
const MAX_URL_LENGTH = 2083;

/**
 * POST /api/layers
 */
function createLayer(): void
{
    $input = json_decode(file_get_contents('php://input'), true);

    if (!is_array($input)) {
        http_response_code(400);
        echo json_encode(['error' => 'Invalid JSON body']);
        return;
    }

    $name      = trim((string)($input['name']      ?? ''));
    $type      = trim((string)($input['type']      ?? ''));
    $sourceUrl = trim((string)($input['source_url'] ?? ''));
    $minZoom   = isset($input['min_zoom']) ? (int)$input['min_zoom'] : 0;
    $maxZoom   = isset($input['max_zoom']) ? (int)$input['max_zoom'] : 18;

    // Validation
    if ($name === '') {
        http_response_code(400);
        echo json_encode(['error' => 'name is required']);
        return;
    }

    if (!in_array($type, ALLOWED_TYPES, true)) {
        http_response_code(400);
        echo json_encode(['error' => 'type must be one of: ' . implode(', ', ALLOWED_TYPES)]);
        return;
    }

    if ($sourceUrl === '' || strlen($sourceUrl) > MAX_URL_LENGTH) {
        http_response_code(400);
        echo json_encode(['error' => 'source_url is required and must be ≤ ' . MAX_URL_LENGTH . ' characters']);
        return;
    }

    // Only allow http(s) or relative file paths (no javascript:, data:, etc.)
    if (!preg_match('#^https?://#i', $sourceUrl) && !preg_match('#^/#', $sourceUrl)) {
        http_response_code(400);
        echo json_encode(['error' => 'source_url must be an http(s) URL or an absolute file path']);
        return;
    }

    if ($minZoom < 0 || $minZoom > 22 || $maxZoom < 0 || $maxZoom > 22 || $minZoom > $maxZoom) {
        http_response_code(400);
        echo json_encode(['error' => 'min_zoom / max_zoom must be between 0 and 22, min ≤ max']);
        return;
    }

    $pdo = db();

    // Insert layer
    $stmt = $pdo->prepare(
        'INSERT INTO layers (name, type, source_url, min_zoom, max_zoom, status)
         VALUES (:name, :type, :source_url, :min_zoom, :max_zoom, \'pending\')'
    );
    $stmt->execute([
        ':name'       => $name,
        ':type'       => $type,
        ':source_url' => $sourceUrl,
        ':min_zoom'   => $minZoom,
        ':max_zoom'   => $maxZoom,
    ]);

    $layerId = (int)$pdo->lastInsertId();

    // Enqueue processing job
    $jobStmt = $pdo->prepare(
        'INSERT INTO jobs (type, payload, status) VALUES (:type, :payload, \'pending\')'
    );
    $jobStmt->execute([
        ':type'    => 'process_layer',
        ':payload' => json_encode(['layer_id' => $layerId]),
    ]);

    http_response_code(201);
    echo json_encode([
        'layerId' => $layerId,
        'status'  => 'processing',
    ]);
}

/**
 * GET /api/layers
 */
function listLayers(): void
{
    $rows = db()->query('SELECT * FROM layers ORDER BY created_at DESC')->fetchAll();
    echo json_encode($rows);
}

/**
 * GET /api/layers/{id}
 */
function getLayer(int $id): void
{
    $stmt = db()->prepare('SELECT * FROM layers WHERE id = :id');
    $stmt->execute([':id' => $id]);
    $row = $stmt->fetch();

    if (!$row) {
        http_response_code(404);
        echo json_encode(['error' => 'Layer not found']);
        return;
    }

    echo json_encode($row);
}
